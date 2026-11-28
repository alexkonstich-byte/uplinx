//! sing-box as an alternative engine (1.12+ config format): same servers, modes, routing and presets as the Xray
//! builder. Servers sing-box can't run (xhttp, Xray JSON, AmneziaWG obfuscation) stay on Xray.

use crate::model::{AppSettings, ProxyNode, RoutingSettings};
use crate::state::core_dir;
use serde_json::{json, Value};
use std::path::PathBuf;

pub fn exe() -> PathBuf { core_dir().join("sing-box.exe") }
pub fn installed() -> bool { exe().exists() }

/// Latest release tag via the /releases/latest redirect (no API call, so no rate limit).
pub fn latest_version() -> anyhow::Result<String> {
    let agent: ureq::Agent = ureq::Agent::config_builder().max_redirects(0).http_status_as_error(false)
        .timeout_global(Some(std::time::Duration::from_secs(20))).user_agent("Uplinx").build().into();
    let r = agent.get("https://github.com/SagerNet/sing-box/releases/latest").call()?;
    let loc = r.headers().get("location").and_then(|v| v.to_str().ok()).unwrap_or("").to_string();
    let tag = loc.rsplit('/').next().unwrap_or("").trim_start_matches('v').to_string();
    anyhow::ensure!(!tag.is_empty() && tag.chars().next().is_some_and(|c| c.is_ascii_digit()), "couldn't find the sing-box release");
    Ok(tag)
}

pub fn download_url(version: &str) -> String {
    let arch = if cfg!(target_arch = "aarch64") { "arm64" } else { "amd64" };
    format!("https://github.com/SagerNet/sing-box/releases/download/v{version}/sing-box-{version}-windows-{arch}.zip")
}

/// Unpacks sing-box.exe (and any DLL next to it) from the release zip into the core folder.
pub fn unpack(zip: Vec<u8>) -> anyhow::Result<()> {
    let mut ar = zip::ZipArchive::new(std::io::Cursor::new(zip))?;
    let mut found = false;
    for i in 0..ar.len() {
        let mut f = ar.by_index(i)?;
        let name = f.name().rsplit('/').next().unwrap_or("").to_string();
        let lower = name.to_lowercase();
        if !(lower.ends_with(".exe") || lower.ends_with(".dll")) { continue; }
        let mut data = vec![];
        std::io::Read::read_to_end(&mut f, &mut data)?;
        std::fs::write(core_dir().join(&name), data)?;
        found |= lower == "sing-box.exe";
    }
    anyhow::ensure!(found, "sing-box.exe not found in the release");
    Ok(())
}

/// Can sing-box run this server the way Xray would?
pub fn supports(n: &ProxyNode) -> bool {
    match n.protocol.as_str() {
        "XRAY_CONFIG" => false,
        "WIREGUARD" => !n.needs_amnezia_engine() && n.awg.get("Jc").is_none_or(|j| j == "0"),
        _ => !matches!(n.network.as_str(), "xhttp" | "splithttp" | "kcp" | "quic") && !(n.network == "tcp" && n.header_type == "http"),
    }
}

fn list(v: &str) -> Vec<String> { v.split(',').map(|x| x.trim().to_string()).filter(|x| !x.is_empty()).collect() }

fn tls(n: &ProxyNode) -> Option<Value> {
    if n.security != "tls" && n.security != "reality" { return None; }
    let mut t = json!({ "enabled": true });
    let sni = if !n.sni.is_empty() { &n.sni } else if !n.host.is_empty() { &n.host } else { &n.address };
    t["server_name"] = json!(sni);
    if !n.alpn.is_empty() { t["alpn"] = json!(list(&n.alpn)); }
    if n.allow_insecure { t["insecure"] = json!(true); }
    let fp = if n.fingerprint.is_empty() && n.security == "reality" { "chrome" } else { &n.fingerprint };
    if !fp.is_empty() { t["utls"] = json!({ "enabled": true, "fingerprint": fp }); }
    if n.security == "reality" { t["reality"] = json!({ "enabled": true, "public_key": n.public_key, "short_id": n.short_id }); }
    Some(t)
}

fn transport(n: &ProxyNode) -> Option<Value> {
    let host = if n.host.is_empty() { None } else { Some(n.host.clone()) };
    let path = if n.path.is_empty() { "/".to_string() } else { n.path.clone() };
    match n.network.as_str() {
        "ws" => {
            let mut t = json!({ "type": "ws", "path": path });
            if let Some(h) = host { t["headers"] = json!({ "Host": h }); }
            Some(t)
        }
        "grpc" => Some(json!({ "type": "grpc", "service_name": n.service_name })),
        "h2" | "http" => {
            let mut t = json!({ "type": "http", "path": path });
            if let Some(h) = host { t["host"] = json!(list(&h)); }
            Some(t)
        }
        "httpupgrade" => {
            let mut t = json!({ "type": "httpupgrade", "path": path });
            if let Some(h) = host { t["host"] = json!(h); }
            Some(t)
        }
        _ => None,
    }
}

/// The server as a sing-box outbound (or, for WireGuard, an endpoint), tagged "proxy".
pub fn outbound(n: &ProxyNode) -> (Value, bool) {
    let mut o = match n.protocol.as_str() {
        "VLESS" => {
            let mut o = json!({ "type": "vless", "uuid": n.uuid, "packet_encoding": "xudp" });
            if !n.flow.is_empty() { o["flow"] = json!(n.flow); }
            o
        }
        "VMESS" => json!({ "type": "vmess", "uuid": n.uuid, "alter_id": n.alter_id,
            "security": if n.vmess_security.is_empty() { "auto" } else { &n.vmess_security } }),
        "TROJAN" => json!({ "type": "trojan", "password": n.password }),
        "SHADOWSOCKS" => json!({ "type": "shadowsocks", "method": n.method, "password": n.password }),
        "SOCKS" => {
            let mut o = json!({ "type": "socks", "version": "5" });
            if !n.username.is_empty() { o["username"] = json!(n.username); o["password"] = json!(n.password); }
            o
        }
        "HTTP" => {
            let mut o = json!({ "type": "http" });
            if !n.username.is_empty() { o["username"] = json!(n.username); o["password"] = json!(n.password); }
            o
        }
        "HYSTERIA2" => {
            let mut o = json!({ "type": "hysteria2", "password": n.password });
            if !n.obfs_password.is_empty() { o["obfs"] = json!({ "type": "salamander", "password": n.obfs_password }); }
            if n.up_mbps > 0 { o["up_mbps"] = json!(n.up_mbps); }
            if n.down_mbps > 0 { o["down_mbps"] = json!(n.down_mbps); }
            let mut t = tls(n).unwrap_or_else(|| json!({ "enabled": true, "server_name": if n.sni.is_empty() { &n.address } else { &n.sni } }));
            if let Some(u) = t.as_object_mut() { u.remove("utls"); } // QUIC: no uTLS
            o["tls"] = t;
            o
        }
        "WIREGUARD" => {
            let mut peer = json!({ "address": n.address, "port": n.port, "public_key": n.wg_public_key, "allowed_ips": ["0.0.0.0/0", "::/0"] });
            if !n.wg_pre_shared_key.is_empty() { peer["pre_shared_key"] = json!(n.wg_pre_shared_key); }
            let reserved: Vec<i64> = n.wg_reserved.split(',').filter_map(|x| x.trim().parse().ok()).collect();
            if reserved.len() == 3 { peer["reserved"] = json!(reserved); }
            if n.wg_keepalive > 0 { peer["persistent_keepalive_interval"] = json!(n.wg_keepalive); }
            let mut e = json!({ "type": "wireguard", "tag": "proxy", "address": list(&n.wg_addresses), "private_key": n.wg_private_key, "peers": [peer] });
            if n.wg_mtu > 0 { e["mtu"] = json!(n.wg_mtu); }
            return (e, true);
        }
        _ => json!({ "type": "direct" }),
    };
    o["tag"] = json!("proxy");
    if n.protocol != "HYSTERIA2" {
        o["server"] = json!(n.address);
        o["server_port"] = json!(n.port);
        if let Some(t) = tls(n) { o["tls"] = t; }
        if let Some(t) = transport(n) { o["transport"] = t; }
    } else {
        o["server"] = json!(n.address);
        o["server_port"] = json!(n.port);
    }
    (o, false)
}

fn dns_server(addr: &str, tag: &str, detour: Option<&str>) -> Value {
    let a = addr.trim();
    let (kind, rest) = match a.split_once("://") {
        Some(("https", r)) => ("https", r), Some(("tls", r)) => ("tls", r), Some(("quic", r)) => ("quic", r),
        Some(("tcp", r)) => ("tcp", r), Some((_, r)) => ("udp", r), None => ("udp", a),
    };
    let path = rest.split_once('/').map(|(_, p)| format!("/{p}")).unwrap_or_default();
    let (host, port) = super::xray::dns_endpoint(a);
    let mut s = json!({ "type": kind, "tag": tag, "server": host });
    if let Some(p) = port { s["server_port"] = json!(p); }
    if kind == "https" && !path.is_empty() && path != "/dns-query" { s["path"] = json!(path); }
    if let Some(d) = detour { s["detour"] = json!(d); }
    s
}

struct Sets { names: Vec<String> }
impl Sets {
    /// geosite:x / geoip:x → a remote binary rule set (downloaded through the proxy, cached by sing-box).
    fn tag(&mut self, kind: &str, name: &str) -> String {
        let tag = format!("{kind}-{}", name.to_lowercase());
        if !self.names.contains(&tag) { self.names.push(tag.clone()); }
        tag
    }
    fn defs(&self, runetfreedom: bool) -> Vec<Value> {
        self.names.iter().map(|t| {
            let url = if runetfreedom && (t == "geosite-ru-blocked" || t == "geoip-ru-blocked") {
                let dir = if t.starts_with("geosite") { "rule-set-geosite" } else { "rule-set-geoip" };
                format!("https://raw.githubusercontent.com/runetfreedom/russia-v2ray-rules-dat/release/sing-box/{dir}/{t}.srs")
            } else if t.starts_with("geosite-") {
                format!("https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/{t}.srs")
            } else {
                format!("https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/{t}.srs")
            };
            json!({ "type": "remote", "tag": t, "format": "binary", "url": url, "download_detour": "proxy", "update_interval": "7d" })
        }).collect()
    }
}

fn action(tag: &str) -> Value {
    match tag { "block" => json!({ "action": "reject" }), t => json!({ "action": "route", "outbound": t }) }
}

fn rule(mut cond: Value, out: &str) -> Value {
    if let (Some(c), Some(a)) = (cond.as_object_mut(), action(out).as_object()) { for (k, v) in a { c.insert(k.clone(), v.clone()); } }
    cond
}

fn add(c: &mut serde_json::Map<String, Value>, k: &str, v: Value) {
    c.entry(k.to_string()).or_insert_with(|| json!([])).as_array_mut().unwrap().push(v);
}

/// Our rule values → sing-box conditions. Returns None when nothing usable remains.
fn user_rule(kind: &str, value: &str, sets: &mut Sets) -> Option<Value> {
    let vals = list(value);
    let mut c = serde_json::Map::new();
    match kind {
        "DOMAIN" => for v in &vals {
            if let Some(x) = v.strip_prefix("full:") { add(&mut c, "domain", json!(x)) }
            else if let Some(x) = v.strip_prefix("keyword:") { add(&mut c, "domain_keyword", json!(x)) }
            else if let Some(x) = v.strip_prefix("regexp:") { add(&mut c, "domain_regex", json!(x)) }
            else if let Some(x) = v.strip_prefix("geosite:") { let t = sets.tag("geosite", x); add(&mut c, "rule_set", json!(t)) }
            else { add(&mut c, "domain_suffix", json!(v.trim_start_matches("domain:"))) }
        },
        "GEOSITE" => for v in &vals { let t = sets.tag("geosite", v.trim_start_matches("geosite:")); add(&mut c, "rule_set", json!(t)) },
        "IP" => for v in &vals { add(&mut c, "ip_cidr", json!(v)) },
        "GEOIP" => for v in &vals {
            let x = v.trim_start_matches("geoip:");
            if x == "private" { c.insert("ip_is_private".into(), json!(true)); } else { let t = sets.tag("geoip", x); add(&mut c, "rule_set", json!(t)) }
        },
        "PROCESS" => for v in &vals { add(&mut c, "process_name", json!(v)) },
        "PORT" => for v in &vals {
            match v.split_once('-') {
                Some((a, b)) => add(&mut c, "port_range", json!(format!("{}:{}", a.trim(), b.trim()))),
                None => if let Ok(p) = v.parse::<u16>() { add(&mut c, "port", json!(p)) },
            }
        },
        _ => return None,
    }
    (!c.is_empty()).then(|| Value::Object(c))
}

pub fn build(n: &ProxyNode, s: &AppSettings, r: &RoutingSettings) -> String {
    serde_json::to_string_pretty(&build_value(n, s, r)).unwrap_or_default()
}

pub fn build_value(n: &ProxyNode, s: &AppSettings, r: &RoutingSettings) -> Value {
    let tun = s.connect_mode == "TUN";
    let listen = if s.allow_lan { "0.0.0.0" } else { "127.0.0.1" };
    let mut sets = Sets { names: vec![] };
    let mut rules: Vec<Value> = vec![json!({ "action": "sniff" }), json!({ "protocol": "dns", "action": "hijack-dns" })];

    let split = &s.split;
    let mut uses_process = false;
    if split.enabled && !split.apps.is_empty() && r.mode != "DIRECT" {
        uses_process = true;
        if split.mode == "INCLUDE" {
            rules.push(rule(json!({ "process_name": split.apps }), "proxy"));
            rules.push(rule(json!({ "network": ["tcp", "udp"] }), "direct"));
        } else {
            rules.push(rule(json!({ "process_name": split.apps }), "direct"));
        }
    }
    let final_out = match r.mode.as_str() {
        "DIRECT" => "direct",
        "GLOBAL" => {
            if r.bypass_lan { rules.push(rule(json!({ "ip_is_private": true }), "direct")); }
            "proxy"
        }
        _ => {
            if r.block_ads { let t = sets.tag("geosite", "category-ads-all"); rules.push(rule(json!({ "rule_set": [t] }), "block")); }
            for ur in r.rules.iter().filter(|x| x.enabled && !x.value.trim().is_empty()) {
                if ur.kind == "PROCESS" { uses_process = true; }
                if let Some(c) = user_rule(&ur.kind, &ur.value, &mut sets) {
                    rules.push(rule(c, match ur.action.as_str() { "DIRECT" => "direct", "BLOCK" => "block", _ => "proxy" }));
                }
            }
            for (on, out, cats) in super::xray::PRESETS(r) {
                if on {
                    let tags: Vec<String> = cats.iter().map(|c| sets.tag("geosite", c)).collect();
                    rules.push(rule(json!({ "rule_set": tags }), out));
                }
            }
            if r.preset_block_torrents { rules.push(rule(json!({ "protocol": ["bittorrent"] }), "block")); }
            if r.bypass_lan { rules.push(rule(json!({ "ip_is_private": true }), "direct")); }
            if r.proxy_blocked_ru && s.geo_source == "RUNETFREEDOM" {
                let (a, b) = (sets.tag("geosite", "ru-blocked"), sets.tag("geoip", "ru-blocked"));
                rules.push(rule(json!({ "rule_set": [a, b] }), "proxy"));
                rules.push(rule(json!({ "network": ["tcp", "udp"] }), "direct"));
            } else if r.bypass_ru {
                let (a, b) = (sets.tag("geosite", "category-ru"), sets.tag("geoip", "ru"));
                rules.push(rule(json!({ "domain_suffix": ["ru", "su", "xn--p1ai"] }), "direct"));
                rules.push(rule(json!({ "rule_set": [a, b] }), "direct"));
            }
            "proxy"
        }
    };

    let mut inbounds = vec![];
    if tun {
        let mut addr = vec!["172.19.0.1/30"];
        if s.ipv6 { addr.push("fdfe:dcba:9876::1/126"); }
        inbounds.push(json!({ "type": "tun", "tag": "tun-in", "interface_name": super::xray::TUN_NAME, "address": addr, "mtu": 1500,
            "auto_route": true, "strict_route": s.tun_strict_dns, "stack": "mixed" }));
    } else {
        inbounds.push(json!({ "type": "mixed", "tag": "socks-in", "listen": listen, "listen_port": s.socks_port }));
        inbounds.push(json!({ "type": "mixed", "tag": "http-in", "listen": listen, "listen_port": s.http_port }));
    }

    let (proxy, is_endpoint) = outbound(n);
    let mut outbounds = vec![];
    if !is_endpoint { outbounds.push(proxy.clone()); }
    outbounds.push(json!({ "type": "direct", "tag": "direct" }));

    let mut dns_rules = vec![];
    if r.mode == "RULE" && r.bypass_ru { let t = sets.tag("geosite", "category-ru"); dns_rules.push(json!({ "rule_set": [t], "server": "direct-dns" })); }
    let dns = json!({
        "servers": [dns_server(&s.remote_dns, "remote-dns", Some("proxy")), dns_server(&s.direct_dns, "direct-dns", None)],
        "rules": dns_rules,
        "final": if r.mode == "DIRECT" { "direct-dns" } else { "remote-dns" },
        "strategy": if s.ipv6 { "prefer_ipv4" } else { "ipv4_only" },
    });

    let mut route = json!({ "rules": rules, "final": final_out, "auto_detect_interface": true,
        "default_domain_resolver": { "server": "direct-dns" } });
    if uses_process { route["find_process"] = json!(true); }
    let defs = sets.defs(s.geo_source == "RUNETFREEDOM");
    if !defs.is_empty() { route["rule_set"] = json!(defs); }

    let mut cfg = json!({
        "log": { "level": match s.log_level.as_str() { "debug" => "debug", "info" => "info", "error" => "error", "none" => "panic", _ => "warn" }, "timestamp": true },
        "dns": dns,
        "inbounds": inbounds,
        "outbounds": outbounds,
        "route": route,
        "experimental": {
            "clash_api": { "external_controller": format!("127.0.0.1:{}", s.api_port) },
            "cache_file": { "enabled": true, "path": core_dir().join("sing-box-cache.db").to_string_lossy() },
        },
    });
    if is_endpoint { cfg["endpoints"] = json!([proxy]); }
    cfg
}

/// Up/down byte totals from the Clash API.
pub fn totals(api_port: u16) -> Option<(u64, u64)> {
    let agent: ureq::Agent = ureq::Agent::config_builder().timeout_global(Some(std::time::Duration::from_secs(2))).build().into();
    let body = agent.get(&format!("http://127.0.0.1:{api_port}/connections")).call().ok()?.into_body().read_to_string().ok()?;
    let v: Value = serde_json::from_str(&body).ok()?;
    Some((v["uploadTotal"].as_u64()?, v["downloadTotal"].as_u64()?))
}
