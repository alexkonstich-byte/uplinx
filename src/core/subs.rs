//! Subscription download: headers (userinfo, title, interval, links, announce, logo, routing),
//! User-Agent fallback when a panel answers with an "App not supported" stub, nested subscriptions.

use crate::model::{now_ms, ProxyNode, RouteRule, Subscription};
use crate::parse::{self, links::b64};
use crate::t;
use std::time::Duration;

pub struct FetchResult { pub subscription: Subscription, pub nodes: Vec<ProxyNode>, pub rules: Vec<RouteRule> }

pub const FALLBACK_AGENTS: [&str; 5] = ["Happ/3.1.0", "v2rayN/7.13", "sing-box 1.12.0", "clash-verge/v2.3.0", "v2rayNG/1.10.5"];

fn agent() -> ureq::Agent {
    ureq::Agent::config_builder().timeout_global(Some(Duration::from_secs(30))).timeout_connect(Some(Duration::from_secs(15))).build().into()
}

struct Resp { body: String, headers: Vec<(String, String)> }
impl Resp { fn h(&self, name: &str) -> String { self.headers.iter().find(|(k, _)| k.eq_ignore_ascii_case(name)).map(|(_, v)| v.clone()).unwrap_or_default() } }

fn get(url: &str, ua: &str, hwid: &str) -> anyhow::Result<Resp> {
    let mut req = agent().get(url.trim()).header("User-Agent", ua).header("Accept", "*/*")
        .header("x-device-os", "Windows").header("x-ver-os", "10.0").header("x-device-model", "PC")
        .header("Accept-Language", if crate::i18n::is_ru() { "ru" } else { "en" });
    if !hwid.is_empty() { req = req.header("x-hwid", hwid); }
    let resp = req.call().map_err(|e| match e { ureq::Error::StatusCode(c) => anyhow::anyhow!("HTTP {c}"), e => e.into() })?;
    let headers = resp.headers().iter().map(|(k, v)| (k.to_string(), String::from_utf8_lossy(v.as_bytes()).to_string())).collect();
    let body = resp.into_body().with_config().limit(32 * 1024 * 1024).read_to_string()?;
    Ok(Resp { body, headers })
}

/// Panels answer unknown clients with a stub (0.0.0.0:1, "App not supported") or nothing.
pub fn is_stub(nodes: &[ProxyNode]) -> bool {
    nodes.is_empty() || nodes.iter().all(|n| {
        let name = n.name.to_lowercase();
        n.port <= 1 || ["0.0.0.0", "127.0.0.1", "localhost", "::", ""].contains(&n.address.as_str()) || name.contains("not supported") || name.contains("не поддерж")
    })
}

pub fn fetch(sub: &Subscription, user_agent: &str, hwid: &str) -> anyhow::Result<FetchResult> {
    let first = if sub.user_agent.is_empty() { user_agent } else { &sub.user_agent };
    let mut agents = vec![first.to_string()];
    for a in FALLBACK_AGENTS { if !agents.iter().any(|x| x == a) { agents.push(a.into()); } }
    let mut last: Option<anyhow::Error> = None;
    for ua in &agents {
        match fetch_with(sub, ua, hwid) {
            Ok(mut r) if !is_stub(&r.nodes) => {
                r.subscription.user_agent = if ua == user_agent { String::new() } else { ua.clone() };
                return Ok(r);
            }
            Ok(_) => {}
            Err(e) => last = Some(e),
        }
    }
    Err(last.unwrap_or_else(|| anyhow::anyhow!(t!("The service returned no servers to any client. The subscription may have expired or the device limit is reached"))))
}

fn fetch_with(sub: &Subscription, ua: &str, hwid: &str) -> anyhow::Result<FetchResult> {
    let resp = get(&sub.url, ua, hwid)?;
    let mut parsed = parse::import(&resp.body, None);
    let nested: Vec<String> = { let mut v = parsed.nested.clone(); v.dedup(); v.truncate(20); v };
    for url in nested {
        if let Ok(r) = get(&url, ua, hwid) {
            let p = parse::import(&r.body, None);
            parsed.nodes.extend(p.nodes); parsed.rules.extend(p.rules);
        }
    }
    let mut nodes = parsed.nodes;
    for n in &mut nodes { n.subscription_id = Some(sub.id.clone()); }
    anyhow::ensure!(!nodes.is_empty(), t!("No supported servers found in the subscription"));

    let mut u = sub.clone();
    u.updated_at = now_ms();
    u.last_error.clear();
    if let Some([up, down, total, exp]) = parse_user_info(&resp.h("subscription-userinfo")) { u.upload = up; u.download = down; u.total = total; u.expire = exp; }
    let title = decode_header(&resp.h("profile-title"));
    if !title.is_empty() && (sub.name == sub.url || sub.name.is_empty() || sub.name == "New subscription" || sub.name == "Новая подписка") { u.name = title; }
    if let Ok(h) = resp.h("profile-update-interval").trim().parse() { u.auto_update_hours = h; }
    u.links.clear();
    let support = decode_header(&resp.h("support-url"));
    if !support.is_empty() { u.links.insert("support".into(), support.clone()); u.support_url = support; }
    let web = decode_header(&resp.h("profile-web-page-url"));
    if !web.is_empty() { u.links.insert("website".into(), web.clone()); u.web_page_url = web; }
    u.announce = decode_header(&resp.h("announce"));
    let rh = decode_header(&resp.h("routing"));
    u.routing_profile = parsed.routing_profile
        .or_else(|| parse::happ_routing_extract(&rh))
        .or_else(|| b64(&rh).filter(|s| s.trim_start().starts_with('{')))
        .or_else(|| Some(rh.clone()).filter(|s| s.trim_start().starts_with('{')))
        .unwrap_or_default();
    let logo = decode_header(&resp.h("profile-logo"));
    if logo.starts_with("http") { if let Some(p) = download_logo(&sub.id, &logo, ua) { u.logo_file = p; } }
    Ok(FetchResult { subscription: u, nodes, rules: parsed.rules })
}

/// PNG/JPEG/SVG up to 2 MB, cached in app data.
fn download_logo(id: &str, url: &str, ua: &str) -> Option<String> {
    let resp = agent().get(url).header("User-Agent", ua).call().ok()?;
    let ctype = resp.headers().get("content-type").and_then(|v| v.to_str().ok()).unwrap_or("").to_string();
    let bytes = resp.into_body().with_config().limit(2 * 1024 * 1024).read_to_vec().ok()?;
    if bytes.is_empty() { return None; }
    let head = String::from_utf8_lossy(&bytes[..bytes.len().min(256)]).to_string();
    let ext = if ctype.contains("svg") || head.contains("<svg") { "svg" }
        else if bytes.starts_with(&[0x89, b'P']) { "png" } else if bytes.starts_with(&[0xFF, 0xD8]) { "jpg" } else { return None };
    let dir = crate::state::home().join("logos");
    std::fs::create_dir_all(&dir).ok()?;
    for e in ["svg", "png", "jpg"] { let _ = std::fs::remove_file(dir.join(format!("{id}.{e}"))); }
    let f = dir.join(format!("{id}.{ext}"));
    std::fs::write(&f, bytes).ok()?;
    Some(f.to_string_lossy().to_string())
}

/// `upload=1; download=2; total=3; expire=4`
pub fn parse_user_info(v: &str) -> Option<[i64; 4]> {
    if v.trim().is_empty() { return None; }
    let mut r = [0i64; 4];
    for part in v.split(';') {
        let (k, val) = part.split_once('=').unwrap_or((part, ""));
        let n = val.trim().parse::<f64>().map(|f| f as i64).unwrap_or(0);
        match k.trim().to_lowercase().as_str() { "upload" => r[0] = n, "download" => r[1] = n, "total" => r[2] = n, "expire" => r[3] = n, _ => {} }
    }
    Some(r)
}

pub fn decode_header(v: &str) -> String {
    match v.strip_prefix("base64:") { Some(rest) => b64(rest).unwrap_or_else(|| v.to_string()), None => v.to_string() }
}
