//! Share links: vless, vmess, trojan, ss, socks, http(s), hysteria2/hy2, wireguard. Plus export back to links.

use crate::model::ProxyNode;
use base64::Engine;
use std::collections::BTreeMap;

pub const SCHEMES: &[&str] = &["vless", "vmess", "trojan", "ss", "socks", "socks5", "http", "https", "hysteria2", "hy2", "wireguard", "wg", "vpn"];

pub fn url_decode(s: &str) -> String {
    // keep '+' literal: passwords in base64 use it
    percent_encoding::percent_decode_str(s).decode_utf8_lossy().to_string()
}

pub fn url_encode(s: &str) -> String {
    use percent_encoding::{utf8_percent_encode, NON_ALPHANUMERIC, AsciiSet};
    const SET: &AsciiSet = &NON_ALPHANUMERIC.remove(b'-').remove(b'_').remove(b'.').remove(b'~');
    utf8_percent_encode(s, SET).to_string()
}

/// Lenient base64: standard or URL alphabet, with or without padding, whitespace ignored.
pub fn b64(s: &str) -> Option<String> {
    let t: String = s.chars().filter(|c| !c.is_whitespace()).collect();
    if t.is_empty() { return None; }
    let t = t.trim_end_matches('=');
    let engines = [
        base64::engine::general_purpose::STANDARD_NO_PAD,
        base64::engine::general_purpose::URL_SAFE_NO_PAD,
    ];
    for e in engines {
        if let Ok(bytes) = e.decode(t) {
            if let Ok(text) = String::from_utf8(bytes) { return Some(text); }
        }
    }
    None
}

pub struct RawUri { pub user: String, pub host: String, pub port: u16, pub query: BTreeMap<String, String>, pub fragment: String }

fn split_host_port(s: &str) -> (String, u16) {
    let s = s.trim_end_matches('/');
    if let Some(rest) = s.strip_prefix('[') {
        let host = rest.split(']').next().unwrap_or("").to_string();
        let port = rest.split("]:").nth(1).and_then(|p| p.parse().ok()).unwrap_or(443);
        (host, port)
    } else if let Some(i) = s.rfind(':') {
        (s[..i].to_string(), s[i + 1..].parse().unwrap_or(443))
    } else { (s.to_string(), 443) }
}

pub fn split_uri(link: &str) -> RawUri {
    let rest = link.split_once("://").map(|x| x.1).unwrap_or(link);
    let (rest, fragment) = rest.split_once('#').map(|(a, b)| (a, url_decode(b))).unwrap_or((rest, String::new()));
    let (main, q) = rest.split_once('?').unwrap_or((rest, ""));
    let main = main.trim_end_matches('/');
    let (user, hostport) = match main.rfind('@') { Some(i) => (url_decode(&main[..i]), &main[i + 1..]), None => (String::new(), main) };
    let (host, port) = split_host_port(hostport);
    let query = q.split('&').filter(|x| !x.is_empty()).map(|kv| {
        let (k, v) = kv.split_once('=').unwrap_or((kv, ""));
        (url_decode(k), url_decode(v))
    }).collect();
    RawUri { user, host, port, query, fragment }
}

fn q(u: &RawUri, k: &str) -> String { u.query.get(k).cloned().unwrap_or_default() }

pub fn parse(link: &str) -> Option<ProxyNode> {
    let scheme = link.split("://").next()?.to_lowercase();
    let mut n = match scheme.as_str() {
        "vless" => vless_like(link, "VLESS"),
        "trojan" => vless_like(link, "TROJAN"),
        "vmess" => vmess(link),
        "ss" => shadowsocks(link)?,
        "socks" | "socks5" => socks_http(link, "SOCKS"),
        "http" | "https" => socks_http(link, "HTTP"),
        "hysteria2" | "hy2" => hysteria2(link),
        "wireguard" | "wg" => wireguard(link),
        _ => return None,
    };
    if n.address.is_empty() { return None; }
    n.raw_link = link.to_string();
    Some(n)
}

fn vless_like(link: &str, proto: &str) -> ProxyNode {
    let u = split_uri(link);
    let security = { let s = q(&u, "security"); if s.is_empty() { if proto == "TROJAN" { "tls".into() } else { "none".into() } } else { s } };
    let net = { let t = q(&u, "type"); if t.is_empty() || t == "raw" { "tcp".to_string() } else { t } };
    let mode = q(&u, "mode");
    ProxyNode {
        name: if u.fragment.is_empty() { u.host.clone() } else { u.fragment.clone() },
        protocol: proto.into(),
        address: u.host.clone(), port: u.port,
        uuid: if proto == "VLESS" { u.user.clone() } else { String::new() },
        password: if proto == "TROJAN" { u.user.clone() } else { String::new() },
        flow: q(&u, "flow"),
        encryption: { let e = q(&u, "encryption"); if e.is_empty() { "none".into() } else { e } },
        network: net.clone(),
        header_type: { let h = q(&u, "headerType"); if h.is_empty() { "none".into() } else { h } },
        host: q(&u, "host"), path: q(&u, "path"), service_name: q(&u, "serviceName"),
        grpc_mode: if net == "grpc" && !mode.is_empty() { mode.clone() } else { "gun".into() },
        xhttp_mode: if net == "xhttp" && !mode.is_empty() { mode } else { "auto".into() },
        xhttp_extra: q(&u, "extra"),
        security,
        sni: { let s = q(&u, "sni"); if s.is_empty() { q(&u, "peer") } else { s } },
        alpn: q(&u, "alpn"), fingerprint: q(&u, "fp"), public_key: q(&u, "pbk"), short_id: q(&u, "sid"), spider_x: q(&u, "spx"),
        allow_insecure: ["1", "true"].contains(&q(&u, "allowInsecure").as_str()) || q(&u, "insecure") == "1",
        pinned_peer_cert_sha256: q(&u, "pcs"), verify_peer_cert_by_name: q(&u, "vcn"), ech_config_list: q(&u, "ech"),
        ..Default::default()
    }
}

fn vmess(link: &str) -> ProxyNode {
    let payload = link.split_once("://").map(|x| x.1).unwrap_or("").split('#').next().unwrap_or("");
    let Some(json) = b64(payload).filter(|d| d.trim_start().starts_with('{')) else {
        let mut n = vless_like(link, "VLESS");
        n.protocol = "VMESS".into();
        return n;
    };
    let o: serde_json::Value = serde_json::from_str(&json).unwrap_or_default();
    let g = |k: &str| match &o[k] { serde_json::Value::String(s) => s.clone(), serde_json::Value::Number(n) => n.to_string(), _ => String::new() };
    let net = { let n = g("net"); if n.is_empty() || n == "raw" { "tcp".to_string() } else { n } };
    let tls = g("tls");
    ProxyNode {
        name: { let n = g("ps"); if n.is_empty() { g("add") } else { n } },
        protocol: "VMESS".into(), address: g("add"), port: g("port").parse().unwrap_or(443),
        uuid: g("id"), alter_id: g("aid").parse().unwrap_or(0),
        vmess_security: { let s = g("scy"); if s.is_empty() { "auto".into() } else { s } },
        header_type: { let t = g("type"); if t.is_empty() { "none".into() } else { t } },
        host: g("host"), path: g("path"),
        service_name: if net == "grpc" { g("path") } else { String::new() },
        network: net,
        security: if tls == "tls" || tls == "reality" { tls } else { "none".into() },
        sni: g("sni"), alpn: g("alpn"), fingerprint: g("fp"),
        ..Default::default()
    }
}

fn shadowsocks(link: &str) -> Option<ProxyNode> {
    let body = link.split_once("://")?.1;
    let (body, frag) = body.split_once('#').map(|(a, b)| (a, url_decode(b))).unwrap_or((body, String::new()));
    let main = body.split('?').next().unwrap_or("");
    let (method, password, hostport) = if let Some(i) = main.rfind('@') {
        let info = &main[..i];
        let creds = b64(&url_decode(info)).filter(|c| c.contains(':')).unwrap_or_else(|| url_decode(info));
        let (m, p) = creds.split_once(':')?;
        (m.to_string(), p.to_string(), main[i + 1..].to_string())
    } else {
        let d = b64(main)?;
        let i = d.rfind('@')?;
        let (m, p) = d[..i].split_once(':')?;
        (m.to_string(), p.to_string(), d[i + 1..].to_string())
    };
    let (host, port) = split_host_port(&hostport);
    Some(ProxyNode {
        name: if frag.is_empty() { host.clone() } else { frag }, protocol: "SHADOWSOCKS".into(),
        address: host, port, method, password, ..Default::default()
    })
}

fn socks_http(link: &str, proto: &str) -> ProxyNode {
    let u = split_uri(link);
    let (mut user, mut pass) = (String::new(), String::new());
    if !u.user.is_empty() {
        let creds = if u.user.contains(':') { u.user.clone() } else { b64(&u.user).unwrap_or(u.user.clone()) };
        let (a, b) = creds.split_once(':').unwrap_or((&creds, ""));
        user = a.into(); pass = b.into();
    }
    ProxyNode {
        name: if u.fragment.is_empty() { u.host.clone() } else { u.fragment.clone() }, protocol: proto.into(),
        address: u.host.clone(), port: u.port, username: user, password: pass,
        security: if link.to_lowercase().starts_with("https") { "tls".into() } else { "none".into() },
        ..Default::default()
    }
}

fn hysteria2(link: &str) -> ProxyNode {
    let u = split_uri(link);
    let digits = |k: &str| q(&u, k).chars().filter(|c| c.is_ascii_digit()).collect::<String>().parse().unwrap_or(0);
    ProxyNode {
        name: if u.fragment.is_empty() { u.host.clone() } else { u.fragment.clone() }, protocol: "HYSTERIA2".into(),
        address: u.host.clone(), port: u.port, password: u.user.clone(), network: "hysteria".into(), security: "tls".into(),
        sni: q(&u, "sni"), alpn: q(&u, "alpn"), allow_insecure: q(&u, "insecure") == "1",
        pinned_peer_cert_sha256: { let p = q(&u, "pcs"); if p.is_empty() { q(&u, "pinSHA256") } else { p } },
        obfs: q(&u, "obfs"), obfs_password: q(&u, "obfs-password"), up_mbps: digits("up"), down_mbps: digits("down"),
        ..Default::default()
    }
}

fn wireguard(link: &str) -> ProxyNode {
    let u = split_uri(link);
    let or = |a: &str, b: &str| { let x = q(&u, a); if x.is_empty() { q(&u, b) } else { x } };
    ProxyNode {
        name: if u.fragment.is_empty() { u.host.clone() } else { u.fragment.clone() }, protocol: "WIREGUARD".into(),
        address: u.host.clone(), port: u.port, network: "udp".into(), wg_private_key: u.user.clone(),
        wg_public_key: or("publickey", "publicKey"), wg_pre_shared_key: or("presharedkey", "psk"),
        wg_addresses: { let a = or("address", "ip"); if a.is_empty() { "10.0.0.2/32".into() } else { a } },
        wg_reserved: q(&u, "reserved"), wg_mtu: q(&u, "mtu").parse().unwrap_or(1420),
        ..Default::default()
    }
}

// ------------------------------------------------------------------ export

fn host(n: &ProxyNode) -> String { if n.address.contains(':') { format!("[{}]", n.address) } else { n.address.clone() } }

fn query(pairs: &[(&str, &str)]) -> String {
    pairs.iter().filter(|(_, v)| !v.is_empty()).map(|(k, v)| format!("{}={}", k, url_encode(v))).collect::<Vec<_>>().join("&")
}

pub fn to_link(n: &ProxyNode) -> String {
    match n.protocol.as_str() {
        "VLESS" | "TROJAN" => {
            let user = if n.protocol == "VLESS" { &n.uuid } else { &n.password };
            let mode = match n.network.as_str() { "grpc" => n.grpc_mode.as_str(), "xhttp" => n.xhttp_mode.as_str(), _ => "" };
            let enc = if n.protocol == "VLESS" { n.encryption.as_str() } else { "" };
            let ht = if n.header_type == "none" { "" } else { n.header_type.as_str() };
            let qs = query(&[("type", &n.network), ("security", &n.security), ("encryption", enc), ("flow", &n.flow), ("sni", &n.sni),
                ("fp", &n.fingerprint), ("alpn", &n.alpn), ("pbk", &n.public_key), ("sid", &n.short_id), ("spx", &n.spider_x),
                ("host", &n.host), ("path", &n.path), ("serviceName", &n.service_name), ("headerType", ht), ("mode", mode),
                ("extra", &n.xhttp_extra), ("pcs", &n.pinned_peer_cert_sha256), ("vcn", &n.verify_peer_cert_by_name), ("ech", &n.ech_config_list)]);
            format!("{}://{}@{}:{}?{}#{}", n.protocol.to_lowercase(), url_encode(user), host(n), n.port, qs, url_encode(&n.name))
        }
        "VMESS" => {
            let o = serde_json::json!({ "v": "2", "ps": n.name, "add": n.address, "port": n.port.to_string(), "id": n.uuid,
                "aid": n.alter_id.to_string(), "scy": n.vmess_security, "net": n.network, "type": n.header_type, "host": n.host,
                "path": if n.network == "grpc" { &n.service_name } else { &n.path },
                "tls": if n.security == "none" { "" } else { n.security.as_str() }, "sni": n.sni, "alpn": n.alpn, "fp": n.fingerprint });
            format!("vmess://{}", base64::engine::general_purpose::STANDARD.encode(o.to_string()))
        }
        "SHADOWSOCKS" => {
            let creds = base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(format!("{}:{}", n.method, n.password));
            format!("ss://{}@{}:{}#{}", creds, host(n), n.port, url_encode(&n.name))
        }
        "SOCKS" | "HTTP" => {
            let scheme = if n.protocol == "SOCKS" { "socks" } else if n.security == "tls" { "https" } else { "http" };
            let auth = if n.username.is_empty() { String::new() } else { format!("{}:{}@", url_encode(&n.username), url_encode(&n.password)) };
            format!("{}://{}{}:{}#{}", scheme, auth, host(n), n.port, url_encode(&n.name))
        }
        "HYSTERIA2" => {
            let qs = query(&[("sni", &n.sni), ("alpn", &n.alpn), ("obfs", &n.obfs), ("obfs-password", &n.obfs_password), ("pcs", &n.pinned_peer_cert_sha256)]);
            format!("hysteria2://{}@{}:{}?{}#{}", url_encode(&n.password), host(n), n.port, qs, url_encode(&n.name))
        }
        "WIREGUARD" => {
            let mtu = n.wg_mtu.to_string();
            let qs = query(&[("publickey", &n.wg_public_key), ("presharedkey", &n.wg_pre_shared_key), ("address", &n.wg_addresses),
                ("reserved", &n.wg_reserved), ("mtu", &mtu)]);
            format!("wireguard://{}@{}:{}?{}#{}", url_encode(&n.wg_private_key), host(n), n.port, qs, url_encode(&n.name))
        }
        _ => n.raw_config.clone(),
    }
}
