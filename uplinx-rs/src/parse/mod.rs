//! Import of everything: links, base64 subscriptions, WG/AWG .conf, Amnezia vpn://, Xray JSON (balancers kept whole),
//! sing-box, Clash/Mihomo YAML with rules, JSON link arrays, JSONC, proxy pools, nested subscriptions, Happ routing.

pub mod links;

use crate::model::{ProxyNode, RouteRule, RoutingSettings};
use serde_json::{json, Value};
use std::collections::BTreeMap;

#[derive(Default, Debug)]
pub struct ImportResult {
    pub nodes: Vec<ProxyNode>,
    pub rules: Vec<RouteRule>,
    pub nested: Vec<String>,
    pub routing_profile: Option<String>,
}

impl ImportResult {
    fn add(&mut self, o: ImportResult) {
        self.nodes.extend(o.nodes); self.rules.extend(o.rules); self.nested.extend(o.nested);
        if o.routing_profile.is_some() { self.routing_profile = o.routing_profile; }
    }
    pub fn is_empty(&self) -> bool { self.nodes.is_empty() && self.rules.is_empty() && self.nested.is_empty() && self.routing_profile.is_none() }
}

pub fn parse_many(text: &str) -> Vec<ProxyNode> { import(text, None).nodes }

pub fn import(text: &str, name_hint: Option<&str>) -> ImportResult {
    let body = text.trim().trim_start_matches('\u{feff}');
    if body.is_empty() { return ImportResult::default(); }
    if let Some(p) = happ_routing_extract(body) { return ImportResult { routing_profile: Some(p), ..Default::default() }; }
    if wg_looks_like(body) { return ImportResult { nodes: wg_parse(body, name_hint).into_iter().collect(), ..Default::default() }; }
    let no_comments = strip_leading_comments(body);
    if no_comments.starts_with('{') || no_comments.starts_with('[') {
        if let Some(r) = json_document(body) { return r; }
    }
    if clash_looks_like(body) { return clash_parse(body); }
    if !body.contains("://") && !body.contains('\n') {
        if let Some(d) = links::b64(body).filter(|d| !d.trim().is_empty() && d != body) {
            let r = import(&d, name_hint);
            if !r.is_empty() { return r; }
        }
    }
    lines(body)
}

fn strip_leading_comments(s: &str) -> &str {
    let mut t = s.trim_start();
    loop {
        if let Some(r) = t.strip_prefix("//") { t = r.split_once('\n').map(|x| x.1).unwrap_or("").trim_start(); }
        else if let Some(r) = t.strip_prefix("/*") { t = r.split_once("*/").map(|x| x.1).unwrap_or("").trim_start(); }
        else { return t; }
    }
}

/// JSONC → JSON: strips // and /* */ comments outside strings, and trailing commas.
fn jsonc_to_json(s: &str) -> String {
    let (mut out, mut in_str, mut esc) = (String::with_capacity(s.len()), false, false);
    let b: Vec<char> = s.chars().collect();
    let mut i = 0;
    while i < b.len() {
        let c = b[i];
        if in_str {
            out.push(c);
            if esc { esc = false } else if c == '\\' { esc = true } else if c == '"' { in_str = false }
            i += 1; continue;
        }
        if c == '"' { in_str = true; out.push(c); i += 1; continue; }
        if c == '/' && i + 1 < b.len() && b[i + 1] == '/' { while i < b.len() && b[i] != '\n' { i += 1; } continue; }
        if c == '/' && i + 1 < b.len() && b[i + 1] == '*' { i += 2; while i + 1 < b.len() && !(b[i] == '*' && b[i + 1] == '/') { i += 1; } i += 2; continue; }
        if c == ',' {
            let mut j = i + 1;
            while j < b.len() && b[j].is_whitespace() { j += 1; }
            if j < b.len() && (b[j] == '}' || b[j] == ']') { i += 1; continue; }
        }
        out.push(c); i += 1;
    }
    out
}

fn json_document(body: &str) -> Option<ImportResult> {
    let v: Value = serde_json::from_str(body).or_else(|_| serde_json::from_str(&jsonc_to_json(body))).ok()?;
    if let Value::Array(a) = &v {
        if !a.is_empty() && a.iter().all(|x| x.is_string()) {
            return Some(lines(&a.iter().filter_map(|x| x.as_str()).collect::<Vec<_>>().join("\n")));
        }
    }
    let objs: Vec<&Value> = match &v { Value::Array(a) => a.iter().filter(|x| x.is_object()).collect(), Value::Object(_) => vec![&v], _ => vec![] };
    if objs.is_empty() { return None; }
    let is_singbox = objs.iter().any(|o| o["outbounds"].as_array().map(|a| a.iter().any(|x| x.get("type").is_some())).unwrap_or(false));
    if is_singbox { return Some(ImportResult { nodes: objs.iter().flat_map(|o| singbox_parse(o)).collect(), ..Default::default() }); }
    if objs.iter().any(|o| o.get("outbounds").is_some()) {
        return Some(ImportResult { nodes: objs.iter().flat_map(|o| xray_config(o)).collect(), ..Default::default() });
    }
    None
}

fn lines(body: &str) -> ImportResult {
    let mut r = ImportResult::default();
    for line in body.lines().map(str::trim).filter(|l| !l.is_empty() && !l.starts_with('#') && !l.starts_with("//")) {
        let scheme = line.split("://").next().unwrap_or("").to_lowercase();
        if scheme == "vpn" {
            r.nodes.extend(amnezia_parse(line).unwrap_or_default());
        } else if let Some(p) = happ_routing_extract(line) {
            r.routing_profile = Some(p);
        } else if (scheme == "http" || scheme == "https") && line.contains("://") && !line.contains('@')
            && line.split_once("://").map(|x| x.1.contains('/')).unwrap_or(false) {
            r.nested.push(line.to_string());
        } else if links::SCHEMES.contains(&scheme.as_str()) {
            if let Some(n) = links::parse(line) { r.nodes.push(n); }
        } else if let Some(n) = pool_line(line) {
            r.nodes.push(n);
        }
    }
    r
}

/// host:port, host:port:user:pass or user:pass@host:port — plain proxy lists.
fn pool_line(line: &str) -> Option<ProxyNode> {
    let socks = |host: &str, port: &str, user: &str, pass: &str| -> Option<ProxyNode> {
        let port: u16 = port.parse().ok().filter(|p| *p > 0)?;
        let host = host.trim_matches(|c| c == '[' || c == ']');
        if host.is_empty() || !host.chars().all(|c| c.is_ascii_alphanumeric() || ".-:_".contains(c)) { return None; }
        Some(ProxyNode { name: format!("{host}:{port}"), protocol: "SOCKS".into(), address: host.into(), port,
            username: user.into(), password: pass.into(), raw_link: line.into(), ..Default::default() })
    };
    if line.contains(char::is_whitespace) { return None; }
    if let Some((cred, hp)) = line.split_once('@') {
        let (u, p) = cred.split_once(':')?;
        let (h, port) = hp.rsplit_once(':')?;
        return socks(h, port, u, p);
    }
    let parts: Vec<&str> = line.split(':').collect();
    match parts.len() { 2 => socks(parts[0], parts[1], "", ""), 4 => socks(parts[0], parts[1], parts[2], parts[3]), _ => None }
}

// ------------------------------------------------------------------ WireGuard / AmneziaWG .conf

pub fn wg_looks_like(t: &str) -> bool { let l = t.to_lowercase(); l.contains("[interface]") && l.contains("[peer]") }

pub fn wg_parse(text: &str, name_hint: Option<&str>) -> Option<ProxyNode> {
    let (mut iface, mut peer, mut awg) = (BTreeMap::new(), BTreeMap::new(), BTreeMap::new());
    let mut section = String::new();
    for raw in text.lines() {
        let line = raw.split('#').next().unwrap_or("").trim();
        if line.is_empty() { continue; }
        if line.starts_with('[') { section = line.trim_matches(|c| c == '[' || c == ']').to_lowercase(); continue; }
        let (k, v) = line.split_once('=').map(|(a, b)| (a.trim(), b.trim())).unwrap_or((line, ""));
        let awg_key = matches!(k.to_lowercase().as_str(), "jc" | "jmin" | "jmax") || (k.len() == 2 && "SHIshi".contains(&k[..1]) && k[1..].chars().all(|c| c.is_ascii_digit()));
        match section.as_str() {
            "interface" if awg_key => { let mut key = k.to_string(); key[..1].make_ascii_uppercase(); awg.insert(key, v.to_string()); }
            "interface" => { iface.insert(k.to_lowercase(), v.to_string()); }
            "peer" => { peer.entry(k.to_lowercase()).or_insert(v.to_string()); }
            _ => {}
        }
    }
    let ep = peer.get("endpoint")?;
    let (host, port) = if let Some(r) = ep.strip_prefix('[') {
        (r.split(']').next().unwrap_or("").to_string(), ep.rsplit(':').next().and_then(|p| p.parse().ok()).unwrap_or(51820))
    } else { let (h, p) = ep.rsplit_once(':')?; (h.to_string(), p.parse().unwrap_or(51820)) };
    let g = |m: &BTreeMap<String, String>, k: &str| m.get(k).cloned().unwrap_or_default();
    Some(ProxyNode {
        name: name_hint.filter(|s| !s.is_empty()).map(str::to_string).unwrap_or(host.clone()),
        protocol: "WIREGUARD".into(), address: host, port, network: "udp".into(),
        wg_private_key: g(&iface, "privatekey"), wg_addresses: g(&iface, "address"), wg_dns: g(&iface, "dns"),
        wg_mtu: iface.get("mtu").and_then(|m| m.parse().ok()).unwrap_or(1420),
        wg_public_key: g(&peer, "publickey"), wg_pre_shared_key: g(&peer, "presharedkey"),
        wg_allowed_ips: peer.get("allowedips").cloned().unwrap_or("0.0.0.0/0, ::/0".into()),
        wg_keepalive: peer.get("persistentkeepalive").and_then(|k| k.parse().ok()).unwrap_or(0),
        wg_conf_text: if awg.is_empty() { String::new() } else { text.trim().to_string() },
        awg,
        ..Default::default()
    })
}

pub fn wg_to_conf(n: &ProxyNode) -> String {
    if !n.wg_conf_text.is_empty() { return n.wg_conf_text.clone(); }
    let mut s = format!("[Interface]\nPrivateKey = {}\nAddress = {}\n", n.wg_private_key, n.wg_addresses);
    if !n.wg_dns.is_empty() { s += &format!("DNS = {}\n", n.wg_dns); }
    s += &format!("MTU = {}\n", n.wg_mtu);
    for (k, v) in &n.awg { s += &format!("{k} = {v}\n"); }
    s += &format!("\n[Peer]\nPublicKey = {}\n", n.wg_public_key);
    if !n.wg_pre_shared_key.is_empty() { s += &format!("PresharedKey = {}\n", n.wg_pre_shared_key); }
    s += &format!("AllowedIPs = {}\nEndpoint = {}\n", n.wg_allowed_ips, n.endpoint());
    if n.wg_keepalive > 0 { s += &format!("PersistentKeepalive = {}\n", n.wg_keepalive); }
    s
}

// ------------------------------------------------------------------ Amnezia vpn://

pub fn amnezia_decode(link: &str) -> Option<String> {
    use base64::Engine;
    let b = link.trim().split_once("://")?.1.replace('\n', "").replace('+', "-").replace('/', "_");
    let bytes = base64::engine::general_purpose::URL_SAFE_NO_PAD.decode(b.trim_end_matches('=')).ok()?;
    if bytes.first() == Some(&b'{') { return String::from_utf8(bytes).ok(); }
    // qCompress: 4-byte big-endian size, then zlib
    use std::io::Read;
    let mut out = String::new();
    flate2::read::ZlibDecoder::new(bytes.get(4..)?).read_to_string(&mut out).ok()?;
    Some(out)
}

pub fn amnezia_parse(link: &str) -> Option<Vec<ProxyNode>> {
    let root: Value = serde_json::from_str(&amnezia_decode(link)?).ok()?;
    let st = |k: &str| root[k].as_str().unwrap_or("").to_string();
    let server = [st("description"), st("name"), st("hostName")].into_iter().find(|x| !x.is_empty()).unwrap_or("Amnezia".into());
    let (dns1, dns2) = (st("dns1"), st("dns2"));
    let mut out = vec![];
    for c in root["containers"].as_array().into_iter().flatten() {
        let kind = c["container"].as_str().unwrap_or("");
        let key = kind.trim_start_matches("amnezia-");
        let proto = c.get(key).or_else(|| c.as_object()?.values().find(|v| v.get("last_config").is_some()));
        let Some(last) = proto.and_then(|p| p["last_config"].as_str()) else { continue };
        let label = format!("{server} · {}", key.to_uppercase());
        if key == "awg" || key == "wireguard" || key.starts_with("awg") {
            let conf = serde_json::from_str::<Value>(last).ok().and_then(|v| v["config"].as_str().map(str::to_string)).unwrap_or(last.to_string());
            let conf = conf.replace("$PRIMARY_DNS", if dns1.is_empty() { "1.1.1.1" } else { &dns1 })
                .replace("$SECONDARY_DNS", if dns2.is_empty() { "1.0.0.1" } else { &dns2 });
            if wg_looks_like(&conf) { out.extend(wg_parse(&conf, Some(&label))); }
        } else if key.contains("xray") {
            if let Ok(v) = serde_json::from_str::<Value>(last) {
                out.extend(xray_config(&v).into_iter().map(|mut n| { n.name = label.clone(); n }));
            }
        }
    }
    if out.is_empty() { None } else { Some(out) }
}

// ------------------------------------------------------------------ Xray JSON

const NOT_PROXY: &[&str] = &["freedom", "blackhole", "dns", "loopback", "dokodemo-door", "tunnel"];

fn vs(v: &Value) -> String { match v { Value::String(s) => s.clone(), Value::Number(n) => n.to_string(), Value::Bool(b) => b.to_string(), _ => String::new() } }

pub fn xray_config(cfg: &Value) -> Vec<ProxyNode> {
    let remarks = cfg["remarks"].as_str().map(str::to_string);
    let outs: Vec<&Value> = cfg["outbounds"].as_array().map(|a| a.iter().filter(|o| o.is_object()).collect()).unwrap_or_default();
    let proxies: Vec<&Value> = outs.iter().copied().filter(|o| !NOT_PROXY.contains(&o["protocol"].as_str().unwrap_or(""))).collect();
    let balancers = cfg["routing"]["balancers"].as_array().cloned().unwrap_or_default();
    let chained = proxies.iter().any(|o| o.get("proxySettings").is_some() || !vs(&o["streamSettings"]["sockopt"]["dialerProxy"]).is_empty());
    if !balancers.is_empty() || chained {
        let first = proxies.iter().find_map(|o| xray_outbound(o));
        let selectors: Vec<String> = balancers.iter().flat_map(|b| b["selector"].as_array().cloned().unwrap_or_default()).map(|x| vs(&x)).collect();
        let members = proxies.iter().map(|o| vs(&o["tag"])).filter(|t| !t.is_empty() && selectors.iter().any(|s| t.starts_with(s))).collect();
        return vec![ProxyNode {
            name: remarks.clone().or(first.as_ref().map(|f| f.name.clone())).unwrap_or("Xray config".into()),
            protocol: "XRAY_CONFIG".into(),
            address: first.as_ref().map(|f| f.address.clone()).unwrap_or_default(),
            port: first.as_ref().map(|f| f.port).unwrap_or(0),
            network: if balancers.is_empty() { "chain".into() } else { "balancer".into() },
            raw_config: cfg.to_string(), balancer_members: members,
            ..Default::default()
        }];
    }
    let nodes: Vec<ProxyNode> = proxies.iter().filter_map(|o| xray_outbound(o)).collect();
    let count = nodes.len();
    nodes.into_iter().enumerate().map(|(i, mut n)| {
        if let Some(r) = &remarks { n.name = if count == 1 { r.clone() } else { format!("{r} · {}", i + 1) }; }
        n
    }).collect()
}

pub fn xray_outbound(o: &Value) -> Option<ProxyNode> {
    let proto = o["protocol"].as_str().unwrap_or("");
    let st = &o["settings"];
    let tag = vs(&o["tag"]);
    let first = |v: &Value| v.as_array().and_then(|a| a.first()).cloned();
    let mut n = match proto {
        "vless" | "vmess" => {
            let srv = first(&st["vnext"]).unwrap_or(st.clone());
            let user = first(&srv["users"]).unwrap_or(srv.clone());
            ProxyNode { name: tag.clone(), protocol: if proto == "vless" { "VLESS".into() } else { "VMESS".into() },
                address: vs(&srv["address"]), port: vs(&srv["port"]).parse().unwrap_or(443), uuid: vs(&user["id"]), flow: vs(&user["flow"]),
                encryption: Some(vs(&user["encryption"])).filter(|x| !x.is_empty()).unwrap_or("none".into()),
                vmess_security: Some(vs(&user["security"])).filter(|x| !x.is_empty()).unwrap_or("auto".into()),
                alter_id: vs(&user["alterId"]).parse().unwrap_or(0), ..Default::default() }
        }
        "trojan" | "shadowsocks" | "socks" | "http" => {
            let srv = first(&st["servers"]).unwrap_or(st.clone());
            let user = first(&srv["users"]);
            ProxyNode { name: tag.clone(),
                protocol: match proto { "trojan" => "TROJAN", "shadowsocks" => "SHADOWSOCKS", "socks" => "SOCKS", _ => "HTTP" }.into(),
                address: vs(&srv["address"]), port: vs(&srv["port"]).parse().unwrap_or(443),
                password: Some(vs(&srv["password"])).filter(|x| !x.is_empty()).unwrap_or(user.as_ref().map(|u| vs(&u["pass"])).unwrap_or_default()),
                username: user.as_ref().map(|u| vs(&u["user"])).unwrap_or_default(), method: vs(&srv["method"]), ..Default::default() }
        }
        "hysteria" => ProxyNode { name: tag.clone(), protocol: "HYSTERIA2".into(), address: vs(&st["address"]),
            port: vs(&st["port"]).parse().unwrap_or(443), network: "hysteria".into(), security: "tls".into(), ..Default::default() },
        "wireguard" => {
            let peer = first(&st["peers"])?;
            let ep = vs(&peer["endpoint"]);
            let (h, p) = ep.rsplit_once(':').unwrap_or((&ep, "51820"));
            ProxyNode { name: tag.clone(), protocol: "WIREGUARD".into(), address: h.trim_matches(|c| c == '[' || c == ']').into(),
                port: p.parse().unwrap_or(51820), network: "udp".into(), wg_private_key: vs(&st["secretKey"]),
                wg_addresses: st["address"].as_array().map(|a| a.iter().map(vs).collect::<Vec<_>>().join(", ")).unwrap_or_default(),
                wg_public_key: vs(&peer["publicKey"]), wg_pre_shared_key: vs(&peer["preSharedKey"]),
                wg_mtu: vs(&st["mtu"]).parse().unwrap_or(1420),
                wg_reserved: st["reserved"].as_array().map(|a| a.iter().map(vs).collect::<Vec<_>>().join(",")).unwrap_or_default(),
                ..Default::default() }
        }
        _ => return None,
    };
    if n.address.is_empty() { return None; }
    let ss = &o["streamSettings"];
    if ss.is_object() {
        let net = { let x = vs(&ss["network"]); match x.as_str() { "" | "raw" => "tcp".to_string(), "splithttp" => "xhttp".into(), _ => x } };
        let tls = &ss["tlsSettings"]; let rl = &ss["realitySettings"];
        let pick = |a: &Value, b: &Value| { let x = vs(a); if x.is_empty() { vs(b) } else { x } };
        let xh = if ss["xhttpSettings"].is_object() { &ss["xhttpSettings"] } else { &ss["splithttpSettings"] };
        let raw = if ss["rawSettings"].is_object() { &ss["rawSettings"] } else { &ss["tcpSettings"] };
        let req = &raw["header"]["request"];
        n.network = if n.protocol == "HYSTERIA2" { "hysteria".into() } else { net };
        n.security = Some(vs(&ss["security"])).filter(|x| !x.is_empty()).unwrap_or("none".into());
        n.sni = pick(&tls["serverName"], &rl["serverName"]);
        n.fingerprint = pick(&tls["fingerprint"], &rl["fingerprint"]);
        n.alpn = tls["alpn"].as_array().map(|a| a.iter().map(vs).collect::<Vec<_>>().join(",")).unwrap_or_default();
        n.pinned_peer_cert_sha256 = vs(&tls["pinnedPeerCertSha256"]);
        n.verify_peer_cert_by_name = vs(&tls["verifyPeerCertByName"]);
        n.ech_config_list = vs(&tls["echConfigList"]);
        n.public_key = pick(&rl["publicKey"], &rl["password"]);
        n.short_id = vs(&rl["shortId"]); n.spider_x = vs(&rl["spiderX"]);
        n.path = [vs(&ss["wsSettings"]["path"]), vs(&ss["httpupgradeSettings"]["path"]), vs(&xh["path"]),
            req["path"].as_array().and_then(|a| a.first()).map(vs).unwrap_or_default()].into_iter().find(|x| !x.is_empty()).unwrap_or_default();
        n.host = [vs(&ss["wsSettings"]["host"]), vs(&ss["httpupgradeSettings"]["host"]), vs(&xh["host"]), vs(&ss["grpcSettings"]["authority"]),
            req["headers"]["Host"].as_array().map(|a| a.iter().map(vs).collect::<Vec<_>>().join(",")).unwrap_or_default()]
            .into_iter().find(|x| !x.is_empty()).unwrap_or_default();
        n.header_type = Some(vs(&raw["header"]["type"])).filter(|x| !x.is_empty()).unwrap_or("none".into());
        n.service_name = vs(&ss["grpcSettings"]["serviceName"]);
        n.grpc_mode = if ss["grpcSettings"]["multiMode"].as_bool() == Some(true) { "multi".into() } else { "gun".into() };
        n.xhttp_mode = Some(vs(&xh["mode"])).filter(|x| !x.is_empty()).unwrap_or("auto".into());
        n.xhttp_extra = if xh["extra"].is_null() { String::new() } else { xh["extra"].to_string() };
        if ss["hysteriaSettings"].is_object() { n.password = vs(&ss["hysteriaSettings"]["auth"]); }
        if let Some(m) = ss["finalmask"]["udp"].as_array().and_then(|a| a.iter().find(|m| m["type"] == "salamander")) {
            n.obfs = "salamander".into(); n.obfs_password = vs(&m["settings"]["password"]);
        }
    }
    if n.name.is_empty() { n.name = n.address.clone(); }
    n.raw_link.clear();
    Some(n)
}

// ------------------------------------------------------------------ sing-box

pub fn singbox_parse(cfg: &Value) -> Vec<ProxyNode> {
    let mut outs: Vec<&Value> = cfg["outbounds"].as_array().map(|a| a.iter().collect()).unwrap_or_default();
    outs.extend(cfg["endpoints"].as_array().map(|a| a.iter().collect::<Vec<_>>()).unwrap_or_default());
    outs.into_iter().filter_map(singbox_outbound).collect()
}

fn singbox_outbound(o: &Value) -> Option<ProxyNode> {
    let t = o["type"].as_str()?;
    let tls = &o["tls"]; let reality = &tls["reality"]; let tr = &o["transport"];
    let mut n = ProxyNode { name: vs(&o["tag"]), address: vs(&o["server"]), port: vs(&o["server_port"]).parse().unwrap_or(443), ..Default::default() };
    let stream = |n: &mut ProxyNode| {
        if reality["enabled"].as_bool() == Some(true) { n.security = "reality".into() } else if tls["enabled"].as_bool() == Some(true) { n.security = "tls".into() }
        n.sni = vs(&tls["server_name"]);
        n.alpn = tls["alpn"].as_array().map(|a| a.iter().map(vs).collect::<Vec<_>>().join(",")).unwrap_or_default();
        n.fingerprint = vs(&tls["utls"]["fingerprint"]);
        n.public_key = vs(&reality["public_key"]); n.short_id = vs(&reality["short_id"]);
        match tr["type"].as_str() { Some("ws") => n.network = "ws".into(), Some("grpc") => n.network = "grpc".into(),
            Some("httpupgrade") => n.network = "httpupgrade".into(), Some("http") => n.network = "xhttp".into(), _ => {} }
        n.path = vs(&tr["path"]); n.service_name = vs(&tr["service_name"]);
        n.host = { let h = vs(&tr["headers"]["Host"]); if h.is_empty() { vs(&tr["host"]) } else { h } };
    };
    match t {
        "vless" => { n.protocol = "VLESS".into(); n.uuid = vs(&o["uuid"]); n.flow = vs(&o["flow"]); stream(&mut n); }
        "vmess" => { n.protocol = "VMESS".into(); n.uuid = vs(&o["uuid"]); n.alter_id = vs(&o["alter_id"]).parse().unwrap_or(0);
            n.vmess_security = Some(vs(&o["security"])).filter(|x| !x.is_empty()).unwrap_or("auto".into()); stream(&mut n); }
        "trojan" => { n.protocol = "TROJAN".into(); n.password = vs(&o["password"]); n.security = "tls".into(); stream(&mut n); }
        "shadowsocks" => { n.protocol = "SHADOWSOCKS".into(); n.method = vs(&o["method"]); n.password = vs(&o["password"]); }
        "socks" => { n.protocol = "SOCKS".into(); n.username = vs(&o["username"]); n.password = vs(&o["password"]); }
        "http" => { n.protocol = "HTTP".into(); n.username = vs(&o["username"]); n.password = vs(&o["password"]); stream(&mut n); }
        "hysteria2" => { n.protocol = "HYSTERIA2".into(); n.password = vs(&o["password"]); n.network = "hysteria".into(); stream(&mut n);
            n.security = "tls".into(); n.obfs = vs(&o["obfs"]["type"]); n.obfs_password = vs(&o["obfs"]["password"]); }
        "wireguard" => {
            let peer = o["peers"].as_array().and_then(|a| a.first()).cloned().unwrap_or(Value::Null);
            n.protocol = "WIREGUARD".into(); n.network = "udp".into();
            if !vs(&peer["address"]).is_empty() { n.address = vs(&peer["address"]); n.port = vs(&peer["port"]).parse().unwrap_or(51820); }
            n.wg_private_key = vs(&o["private_key"]);
            n.wg_public_key = { let p = vs(&peer["public_key"]); if p.is_empty() { vs(&o["peer_public_key"]) } else { p } };
            n.wg_addresses = o["address"].as_array().or(o["local_address"].as_array()).map(|a| a.iter().map(vs).collect::<Vec<_>>().join(", ")).unwrap_or_default();
            n.wg_mtu = vs(&o["mtu"]).parse().unwrap_or(1420);
        }
        _ => return None,
    }
    if n.address.is_empty() { return None; }
    if n.name.is_empty() { n.name = n.address.clone(); }
    Some(n)
}

// ------------------------------------------------------------------ Clash / Mihomo

pub fn clash_looks_like(t: &str) -> bool { t.lines().any(|l| l.starts_with("proxies:") || l.starts_with("proxy-providers:") || l.starts_with("rules:")) }

pub fn clash_parse(text: &str) -> ImportResult {
    let Ok(root) = serde_yaml::from_str::<Value>(text) else { return ImportResult::default() };
    let nodes = root["proxies"].as_array().map(|a| a.iter().filter_map(clash_proxy).collect()).unwrap_or_default();
    let rules = root["rules"].as_array().map(|a| a.iter().filter_map(|r| r.as_str().and_then(clash_rule)).collect()).unwrap_or_default();
    ImportResult { nodes, rules, ..Default::default() }
}

fn clash_proxy(p: &Value) -> Option<ProxyNode> {
    let t = p["type"].as_str()?.to_lowercase();
    let b = |k: &str| p[k].as_bool() == Some(true) || p[k].as_str() == Some("true");
    let mut n = ProxyNode { name: vs(&p["name"]), address: vs(&p["server"]), port: vs(&p["port"]).parse().ok()?, ..Default::default() };
    let tls = b("tls");
    let reality = &p["reality-opts"];
    let net = { let x = vs(&p["network"]); match x.as_str() { "" => "tcp".to_string(), "h2" | "http" => "xhttp".into(), _ => x } };
    let transport = |n: &mut ProxyNode| {
        n.network = net.clone();
        n.security = if reality.is_object() { "reality".into() } else if tls || t == "trojan" { "tls".into() } else { "none".into() };
        n.sni = { let x = vs(&p["servername"]); if x.is_empty() { vs(&p["sni"]) } else { x } };
        n.fingerprint = vs(&p["client-fingerprint"]);
        n.alpn = p["alpn"].as_array().map(|a| a.iter().map(vs).collect::<Vec<_>>().join(",")).unwrap_or_default();
        n.public_key = vs(&reality["public-key"]); n.short_id = vs(&reality["short-id"]);
        n.path = vs(&p["ws-opts"]["path"]); n.host = vs(&p["ws-opts"]["headers"]["Host"]);
        n.service_name = vs(&p["grpc-opts"]["grpc-service-name"]); n.allow_insecure = b("skip-cert-verify");
    };
    match t.as_str() {
        "vless" => { n.protocol = "VLESS".into(); n.uuid = vs(&p["uuid"]); n.flow = vs(&p["flow"]); transport(&mut n); }
        "vmess" => { n.protocol = "VMESS".into(); n.uuid = vs(&p["uuid"]); n.alter_id = vs(&p["alterId"]).parse().unwrap_or(0);
            n.vmess_security = Some(vs(&p["cipher"])).filter(|x| !x.is_empty()).unwrap_or("auto".into()); transport(&mut n); }
        "trojan" => { n.protocol = "TROJAN".into(); n.password = vs(&p["password"]); transport(&mut n); }
        "ss" => { n.protocol = "SHADOWSOCKS".into(); n.method = vs(&p["cipher"]); n.password = vs(&p["password"]); }
        "socks5" => { n.protocol = "SOCKS".into(); n.username = vs(&p["username"]); n.password = vs(&p["password"]); }
        "http" => { n.protocol = "HTTP".into(); n.username = vs(&p["username"]); n.password = vs(&p["password"]); if tls { n.security = "tls".into(); } }
        "hysteria2" => { n.protocol = "HYSTERIA2".into(); n.password = vs(&p["password"]); n.network = "hysteria".into(); n.security = "tls".into();
            n.sni = vs(&p["sni"]); n.obfs = vs(&p["obfs"]); n.obfs_password = vs(&p["obfs-password"]); n.allow_insecure = b("skip-cert-verify"); }
        "wireguard" => {
            n.protocol = "WIREGUARD".into(); n.network = "udp".into(); n.wg_private_key = vs(&p["private-key"]); n.wg_public_key = vs(&p["public-key"]);
            n.wg_pre_shared_key = vs(&p["pre-shared-key"]);
            n.wg_addresses = [vs(&p["ip"]), vs(&p["ipv6"])].into_iter().filter(|x| !x.is_empty()).collect::<Vec<_>>().join(", ");
            n.wg_mtu = vs(&p["mtu"]).parse().unwrap_or(1420);
            n.wg_reserved = p["reserved"].as_array().map(|a| a.iter().map(vs).collect::<Vec<_>>().join(",")).unwrap_or_default();
            if let Some(o) = p["amnezia-wg-option"].as_object() {
                for (k, v) in o { let mut key = k.clone(); key[..1].make_ascii_uppercase(); n.awg.insert(key, vs(v)); }
            }
        }
        _ => return None,
    }
    if n.address.is_empty() { return None; }
    if n.name.is_empty() { n.name = n.address.clone(); }
    Some(n)
}

fn clash_rule(line: &str) -> Option<RouteRule> {
    let parts: Vec<&str> = line.split(',').map(str::trim).collect();
    if parts.len() < 3 { return None; }
    let action = match parts[2].to_uppercase().as_str() { "DIRECT" => "DIRECT", "REJECT" | "REJECT-DROP" | "BLOCK" => "BLOCK", _ => "PROXY" };
    let (kind, value) = match parts[0].to_uppercase().as_str() {
        "DOMAIN" => ("DOMAIN", format!("full:{}", parts[1])),
        "DOMAIN-SUFFIX" => ("DOMAIN", format!("domain:{}", parts[1])),
        "DOMAIN-KEYWORD" => ("DOMAIN", format!("keyword:{}", parts[1])),
        "DOMAIN-REGEX" => ("DOMAIN", format!("regexp:{}", parts[1])),
        "GEOSITE" => ("GEOSITE", parts[1].to_string()),
        "GEOIP" => ("GEOIP", parts[1].to_lowercase()),
        "IP-CIDR" | "IP-CIDR6" => ("IP", parts[1].to_string()),
        "DST-PORT" => ("PORT", parts[1].to_string()),
        "PROCESS-NAME" => ("PROCESS", parts[1].to_string()),
        _ => return None,
    };
    Some(RouteRule { kind: kind.into(), value, action: action.into(), ..Default::default() })
}

// ------------------------------------------------------------------ Happ-compatible routing profiles

pub fn happ_routing_extract(text: &str) -> Option<String> {
    let t = text.trim();
    let rest = t.split_once("://routing/")?.1;
    let payload = rest.strip_prefix("add/").or_else(|| rest.strip_prefix("onadd/"))?;
    if payload.contains(char::is_whitespace) { return None; }
    links::b64(payload).filter(|d| d.trim_start().starts_with('{'))
}

pub fn happ_routing_apply(profile: &str, current: &RoutingSettings) -> Option<RoutingSettings> {
    let o: Value = serde_json::from_str(profile).ok()?;
    let list = |k: &str| o[k].as_array().map(|a| a.iter().filter_map(|x| x.as_str()).map(|x| x.trim().to_string()).filter(|x| !x.is_empty()).collect::<Vec<_>>()).unwrap_or_default();
    let mut panel = vec![];
    for (key, kind, action) in [("BlockSites", "DOMAIN", "BLOCK"), ("BlockIp", "IP", "BLOCK"), ("DirectSites", "DOMAIN", "DIRECT"),
                                ("DirectIp", "IP", "DIRECT"), ("ProxySites", "DOMAIN", "PROXY"), ("ProxyIp", "IP", "PROXY")] {
        let v = list(key);
        if !v.is_empty() {
            panel.push(RouteRule { id: format!("panel-{}-{}", if kind == "DOMAIN" { "site" } else { "ip" }, action),
                kind: kind.into(), value: v.join(", "), action: action.into(), enabled: true });
        }
    }
    let global = vs(&o["GlobalProxy"]).to_lowercase() == "true";
    let mut r = current.clone();
    r.mode = if global && panel.is_empty() { "GLOBAL".into() } else { "RULE".into() };
    if let Some(ds) = o["DomainStrategy"].as_str().filter(|d| ["AsIs", "IPIfNonMatch", "IPOnDemand"].contains(d)) { r.domain_strategy = ds.into(); }
    if !panel.is_empty() { r.bypass_ru = false; }
    let user: Vec<RouteRule> = current.rules.iter().filter(|x| !x.id.starts_with("panel-")).cloned().collect();
    r.rules = panel.into_iter().chain(user).collect();
    Some(r)
}

