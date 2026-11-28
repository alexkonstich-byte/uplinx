//! App state → Xray JSON config. Pure: no IO, unit-testable, validated with `xray run -test` in tests.

use crate::model::{AppSettings, ProxyNode, RoutingSettings};
use serde_json::{json, Map, Value};

pub const TUN_NAME: &str = "Uplinx";

fn split_list(v: &str) -> Vec<String> { v.split(',').map(|x| x.trim().to_string()).filter(|x| !x.is_empty()).collect() }

pub fn build(node: &ProxyNode, s: &AppSettings, r: &RoutingSettings) -> String {
    serde_json::to_string_pretty(&build_value(node, s, r)).unwrap_or_default()
}

pub fn build_value(node: &ProxyNode, s: &AppSettings, r: &RoutingSettings) -> Value {
    if node.protocol == "XRAY_CONFIG" { build_raw(node, s) } else { build_normal(node, s, r) }
}

fn tun(s: &AppSettings) -> bool { s.connect_mode == "TUN" }

fn common_head(s: &AppSettings) -> Map<String, Value> {
    let mut m = Map::new();
    m.insert("log".into(), json!({ "loglevel": s.log_level }));
    m.insert("api".into(), json!({ "tag": "api", "listen": format!("127.0.0.1:{}", s.api_port), "services": ["StatsService"] }));
    m.insert("stats".into(), json!({}));
    m.insert("policy".into(), json!({ "system": { "statsOutboundUplink": true, "statsOutboundDownlink": true } }));
    m
}

fn inbounds(s: &AppSettings) -> Value {
    let mut v = vec![];
    if tun(s) { v.push(tun_inbound(s)); }
    v.push(local_inbound("socks-in", "socks", s.socks_port, s));
    v.push(local_inbound("http-in", "http", s.http_port, s));
    Value::Array(v)
}

fn build_raw(node: &ProxyNode, s: &AppSettings) -> Value {
    let raw: Value = serde_json::from_str(&node.raw_config).unwrap_or(json!({}));
    let mut outbounds: Vec<Value> = raw["outbounds"].as_array().cloned().unwrap_or_default().into_iter().map(|mut o| {
        let p = o["protocol"].as_str().unwrap_or("");
        if tun(s) && !["freedom", "blackhole", "dns"].contains(&p) {
            if !o["streamSettings"].is_object() { o["streamSettings"] = json!({}); }
            o["streamSettings"]["sockopt"] = sockopt();
        }
        o
    }).collect();
    if !outbounds.iter().any(|o| o["tag"] == "dns-out") { outbounds.push(json!({ "tag": "dns-out", "protocol": "dns" })); }
    let mut routing = raw["routing"].clone();
    if !routing.is_object() { routing = json!({}); }
    let mut rules = vec![];
    if tun(s) { rules.push(json!({ "type": "field", "inboundTag": ["tun"], "port": "53", "outboundTag": "dns-out" })); }
    rules.extend(routing["rules"].as_array().cloned().unwrap_or_default());
    routing["rules"] = Value::Array(rules);
    let mut out: Map<String, Value> = raw.as_object().cloned().unwrap_or_default().into_iter()
        .filter(|(k, _)| !["inbounds", "api", "stats", "policy", "remarks", "log"].contains(&k.as_str())).collect();
    out.extend(common_head(s));
    out.insert("inbounds".into(), inbounds(s));
    out.insert("outbounds".into(), Value::Array(outbounds));
    out.insert("routing".into(), routing);
    Value::Object(out)
}

fn build_normal(node: &ProxyNode, s: &AppSettings, r: &RoutingSettings) -> Value {
    let mut m = common_head(s);
    m.insert("dns".into(), dns(s, r));
    m.insert("inbounds".into(), inbounds(s));
    m.insert("outbounds".into(), json!([
        outbound(node, s),
        { "tag": "direct", "protocol": "freedom", "settings": { "domainStrategy": "UseIP" } },
        { "tag": "block", "protocol": "blackhole" },
        { "tag": "dns-out", "protocol": "dns" }
    ]));
    m.insert("routing".into(), routing(s, r));
    Value::Object(m)
}

fn sniffing(s: &AppSettings) -> Value { json!({ "enabled": s.sniffing, "destOverride": ["http", "tls", "quic"], "routeOnly": true }) }

fn local_inbound(tag: &str, protocol: &str, port: u16, s: &AppSettings) -> Value {
    let settings = if protocol == "socks" { json!({ "auth": "noauth", "udp": true }) } else { json!({}) };
    json!({ "tag": tag, "protocol": protocol, "listen": if s.allow_lan { "0.0.0.0" } else { "127.0.0.1" }, "port": port,
            "settings": settings, "sniffing": sniffing(s) })
}

fn tun_inbound(s: &AppSettings) -> Value {
    let mut routes = vec!["0.0.0.0/0"];
    if s.ipv6 { routes.push("::/0"); }
    let mut settings = json!({ "name": TUN_NAME, "mtu": 1500, "gateway": ["172.19.0.1/30"], "dns": ["1.1.1.1"], "autoSystemRoutingTable": routes });
    if s.tun_strict_dns { settings["autoSystemWfpBlockLeak"] = json!(["dns", "misconfigtun"]); }
    json!({ "tag": "tun", "protocol": "tun", "port": 0, "settings": settings, "sniffing": sniffing(s) })
}

/// Resolve the server name through Xray's DNS rather than the OS, which would loop into the TUN.
fn sockopt() -> Value { json!({ "domainStrategy": "UseIP" }) }

pub fn outbound(n: &ProxyNode, s: &AppSettings) -> Value {
    let enc = if n.encryption.is_empty() { "none" } else { &n.encryption };
    let mut o = match n.protocol.as_str() {
        "VLESS" => {
            let mut user = json!({ "id": n.uuid, "encryption": enc });
            if !n.flow.is_empty() { user["flow"] = json!(n.flow); }
            json!({ "protocol": "vless", "settings": { "vnext": [{ "address": n.address, "port": n.port, "users": [user] }] } })
        }
        "VMESS" => json!({ "protocol": "vmess", "settings": { "vnext": [{ "address": n.address, "port": n.port,
            "users": [{ "id": n.uuid, "alterId": n.alter_id, "security": if n.vmess_security.is_empty() { "auto" } else { &n.vmess_security } }] }] } }),
        "TROJAN" => json!({ "protocol": "trojan", "settings": { "servers": [{ "address": n.address, "port": n.port, "password": n.password }] } }),
        "SHADOWSOCKS" => json!({ "protocol": "shadowsocks", "settings": { "servers": [{ "address": n.address, "port": n.port, "method": n.method, "password": n.password }] } }),
        "SOCKS" | "HTTP" => {
            let mut srv = json!({ "address": n.address, "port": n.port });
            if !n.username.is_empty() { srv["users"] = json!([{ "user": n.username, "pass": n.password }]); }
            json!({ "protocol": if n.protocol == "SOCKS" { "socks" } else { "http" }, "settings": { "servers": [srv] } })
        }
        "HYSTERIA2" => json!({ "protocol": "hysteria", "settings": { "version": 2, "address": n.address, "port": n.port } }),
        "WIREGUARD" => {
            let mut peer = json!({ "publicKey": n.wg_public_key, "endpoint": n.endpoint(), "allowedIPs": ["0.0.0.0/0", "::/0"] });
            if !n.wg_pre_shared_key.is_empty() { peer["preSharedKey"] = json!(n.wg_pre_shared_key); }
            let mut st = json!({ "secretKey": n.wg_private_key, "address": split_list(&n.wg_addresses), "peers": [peer], "mtu": n.wg_mtu, "noKernelTun": true });
            let reserved: Vec<i64> = n.wg_reserved.split(',').filter_map(|x| x.trim().parse().ok()).collect();
            if reserved.len() == 3 { st["reserved"] = json!(reserved); }
            json!({ "protocol": "wireguard", "settings": st })
        }
        _ => json!({ "protocol": "freedom" }),
    };
    o["tag"] = json!("proxy");
    if n.protocol != "WIREGUARD" {
        o["streamSettings"] = stream(n, s);
    } else {
        let jc: i32 = n.awg.get("Jc").and_then(|x| x.parse().ok()).unwrap_or(0);
        if jc > 0 || tun(s) {
            let mut ss = json!({});
            if jc > 0 {
                // AmneziaWG junk packets (Jc × random Jmin..Jmax bytes) before the handshake
                let jmin: i32 = n.awg.get("Jmin").and_then(|x| x.parse().ok()).unwrap_or(40);
                let jmax: i32 = n.awg.get("Jmax").and_then(|x| x.parse().ok()).unwrap_or(70).max(jmin);
                let noise: Vec<Value> = (0..jc.min(128)).map(|_| json!({ "rand": format!("{jmin}-{jmax}") })).collect();
                ss["finalmask"] = json!({ "udp": [{ "type": "noise", "settings": { "noise": noise } }] });
            }
            if tun(s) { ss["sockopt"] = sockopt(); }
            o["streamSettings"] = ss;
        }
    }
    if s.mux && ["VLESS", "VMESS", "TROJAN"].contains(&n.protocol.as_str()) && n.flow.is_empty() && n.network != "xhttp" {
        o["mux"] = json!({ "enabled": true, "concurrency": 8 });
    }
    o
}

fn stream(n: &ProxyNode, s: &AppSettings) -> Value {
    let hy = n.protocol == "HYSTERIA2";
    let network = match n.network.as_str() { "h2" | "http" => "xhttp", "" => "tcp", x => x };
    let network = if hy { "hysteria" } else { network };
    let security = if hy { "tls" } else if n.security.is_empty() { "none" } else { &n.security };
    let mut ss = json!({ "network": network, "security": security });
    match security {
        "tls" => {
            let sni = [&n.sni, &n.host, &n.address].into_iter().find(|x| !x.is_empty()).cloned().unwrap_or_default();
            let mut t = json!({ "serverName": sni });
            let mut alpn = split_list(&n.alpn);
            if alpn.is_empty() && hy { alpn.push("h3".into()); }
            if !alpn.is_empty() { t["alpn"] = json!(alpn); }
            if !n.fingerprint.is_empty() { t["fingerprint"] = json!(n.fingerprint); }
            if !n.pinned_peer_cert_sha256.is_empty() { t["pinnedPeerCertSha256"] = json!(n.pinned_peer_cert_sha256); }
            if !n.verify_peer_cert_by_name.is_empty() { t["verifyPeerCertByName"] = json!(n.verify_peer_cert_by_name); }
            if !n.ech_config_list.is_empty() { t["echConfigList"] = json!(n.ech_config_list); }
            ss["tlsSettings"] = t;
        }
        "reality" => {
            let mut rl = json!({ "serverName": n.sni, "fingerprint": if n.fingerprint.is_empty() { "chrome" } else { &n.fingerprint },
                                 "publicKey": n.public_key, "shortId": n.short_id });
            if !n.spider_x.is_empty() { rl["spiderX"] = json!(n.spider_x); }
            ss["realitySettings"] = rl;
        }
        _ => {}
    }
    let path = if n.path.is_empty() { "/" } else { &n.path };
    match network {
        "tcp" if n.header_type == "http" => {
            let mut req = json!({ "path": [path] });
            if !n.host.is_empty() { req["headers"] = json!({ "Host": split_list(&n.host) }); }
            ss["rawSettings"] = json!({ "header": { "type": "http", "request": req } });
        }
        "ws" | "httpupgrade" => {
            let mut w = json!({ "path": path });
            if !n.host.is_empty() { w["host"] = json!(n.host); }
            ss[if network == "ws" { "wsSettings" } else { "httpupgradeSettings" }] = w;
        }
        "grpc" => {
            let mut g = json!({ "serviceName": n.service_name, "multiMode": n.grpc_mode == "multi" });
            if !n.host.is_empty() { g["authority"] = json!(n.host); }
            ss["grpcSettings"] = g;
        }
        "xhttp" => {
            let mut x = json!({ "path": path, "mode": if n.xhttp_mode.is_empty() { "auto" } else { &n.xhttp_mode } });
            if !n.host.is_empty() { x["host"] = json!(n.host); }
            if let Ok(extra) = serde_json::from_str::<Value>(&n.xhttp_extra) { if !n.xhttp_extra.is_empty() { x["extra"] = extra; } }
            ss["xhttpSettings"] = x;
        }
        "kcp" => {
            let mut k = json!({ "header": { "type": if n.header_type.is_empty() { "none" } else { &n.header_type } } });
            if !n.path.is_empty() { k["seed"] = json!(n.path); }
            ss["kcpSettings"] = k;
        }
        "hysteria" => { ss["hysteriaSettings"] = json!({ "version": 2, "auth": n.password }); }
        _ => {}
    }
    if hy && n.obfs == "salamander" && !n.obfs_password.is_empty() {
        ss["finalmask"] = json!({ "udp": [{ "type": "salamander", "settings": { "password": n.obfs_password } }] });
    }
    if tun(s) { ss["sockopt"] = sockopt(); }
    ss
}

/// Host and port of a DNS server address in any form the settings accept:
/// `77.88.8.8`, `77.88.8.8:53`, `2a02:6b8::feed:ff`, `[2a02:6b8::feed:ff]:53`, `https://dns.example/dns-query`, `tcp://host:53`.
pub fn dns_endpoint(addr: &str) -> (String, Option<u16>) {
    let a = addr.trim();
    let rest = a.split_once("://").map_or(a, |(_, r)| r);
    let hostport = rest.split(['/', '?', '#']).next().unwrap_or("");
    if let Some(r) = hostport.strip_prefix('[') {
        let (h, tail) = r.split_once(']').unwrap_or((r, ""));
        return (h.to_string(), tail.strip_prefix(':').and_then(|p| p.parse().ok()));
    }
    if hostport.parse::<std::net::Ipv6Addr>().is_ok() { return (hostport.to_string(), None); }
    match hostport.rsplit_once(':') {
        Some((h, p)) => (h.to_string(), p.parse().ok()),
        None => (hostport.to_string(), None),
    }
}

/// A DNS address the cores can use: a host is there and nothing that can't be part of one.
pub fn valid_dns(addr: &str) -> bool {
    let (host, _) = dns_endpoint(addr);
    !host.is_empty() && !addr.trim().contains(char::is_whitespace)
}

/// A DNS server entry for Xray: URLs as they are; a plain `host:port` has to give the port separately.
fn dns_server(addr: &str) -> Value {
    let a = addr.trim();
    if a.contains("://") { return json!({ "address": a }); }
    let (host, port) = dns_endpoint(a);
    let mut v = json!({ "address": host });
    if let Some(p) = port { v["port"] = json!(p); }
    v
}

fn dns(s: &AppSettings, r: &RoutingSettings) -> Value {
    let mut servers = vec![dns_server(&s.remote_dns)];
    if r.mode == "RULE" && (r.bypass_ru || r.proxy_blocked_ru) && valid_dns(&s.direct_dns) {
        let dom = if s.geo_source == "RUNETFREEDOM" && r.proxy_blocked_ru { "geosite:ru-available-only-inside" } else { "geosite:category-ru" };
        let mut d = dns_server(&s.direct_dns);
        d["domains"] = json!([dom]);
        d["skipFallback"] = json!(true);
        servers.push(d);
    }
    json!({ "tag": "dns-internal", "servers": servers, "queryStrategy": if s.ipv6 { "UseIP" } else { "UseIPv4" } })
}

fn rule(outbound: &str, mut body: Value) -> Value {
    body["type"] = json!("field");
    body["outboundTag"] = json!(outbound);
    body
}

fn tag_for(action: &str) -> &'static str { match action { "DIRECT" => "direct", "BLOCK" => "block", _ => "proxy" } }

pub fn routing(s: &AppSettings, r: &RoutingSettings) -> Value {
    let mut rules = vec![];
    if tun(s) { rules.push(rule("dns-out", json!({ "inboundTag": ["tun"], "port": "53" }))); }
    let split = &s.split;
    if split.enabled && !split.apps.is_empty() && r.mode != "DIRECT" {
        if split.mode == "INCLUDE" {
            rules.push(rule("proxy", json!({ "process": split.apps })));
            rules.push(rule("direct", json!({ "network": "tcp,udp" })));
        } else {
            rules.push(rule("direct", json!({ "process": split.apps })));
        }
    }
    if r.mode == "RULE" && r.bypass_ru {
        // the direct resolver itself must never go through the proxy
        let (host, _) = dns_endpoint(&s.direct_dns);
        if let Ok(ip) = host.parse::<std::net::IpAddr>() { rules.push(rule("direct", json!({ "ip": [ip.to_string()] }))); }
        else if !host.is_empty() { rules.push(rule("direct", json!({ "domain": [format!("full:{host}")] }))); }
    }
    match r.mode.as_str() {
        "DIRECT" => rules.push(rule("direct", json!({ "network": "tcp,udp" }))),
        "GLOBAL" => {
            if r.bypass_lan { rules.push(rule("direct", json!({ "ip": ["geoip:private"] }))); }
            rules.push(rule("proxy", json!({ "network": "tcp,udp" })));
        }
        _ => {
            if r.block_ads { rules.push(rule("block", json!({ "domain": ["geosite:category-ads-all"] }))); }
            for ur in r.rules.iter().filter(|x| x.enabled && !x.value.trim().is_empty()) {
                let vals = split_list(&ur.value);
                let body = match ur.kind.as_str() {
                    "DOMAIN" => json!({ "domain": vals.iter().map(|v| if v.contains(':') { v.clone() } else { format!("domain:{v}") }).collect::<Vec<_>>() }),
                    "GEOSITE" => json!({ "domain": vals.iter().map(|v| format!("geosite:{}", v.trim_start_matches("geosite:"))).collect::<Vec<_>>() }),
                    "IP" => json!({ "ip": vals }),
                    "GEOIP" => json!({ "ip": vals.iter().map(|v| format!("geoip:{}", v.trim_start_matches("geoip:"))).collect::<Vec<_>>() }),
                    "PROCESS" => json!({ "process": vals }),
                    "PORT" => json!({ "port": vals.join(",") }),
                    _ => continue,
                };
                rules.push(rule(tag_for(&ur.action), body));
            }
            for (on, out, cats) in PRESETS(r) {
                if on { rules.push(rule(out, json!({ "domain": cats.iter().map(|c| format!("geosite:{c}")).collect::<Vec<_>>() }))); }
            }
            if r.preset_block_torrents { rules.push(rule("block", json!({ "protocol": ["bittorrent"] }))); }
            if r.bypass_lan { rules.push(rule("direct", json!({ "ip": ["geoip:private"] }))); }
            if r.proxy_blocked_ru && s.geo_source == "RUNETFREEDOM" {
                rules.push(rule("proxy", json!({ "domain": ["geosite:ru-blocked"] })));
                rules.push(rule("proxy", json!({ "ip": ["geoip:ru-blocked"] })));
                rules.push(rule("direct", json!({ "network": "tcp,udp" })));
            } else if r.bypass_ru {
                rules.push(rule("direct", json!({ "domain": ["geosite:category-ru", "regexp:\\.ru$", "regexp:\\.su$", "regexp:\\.xn--p1ai$"] })));
                rules.push(rule("direct", json!({ "ip": ["geoip:ru"] })));
            }
            rules.push(rule("proxy", json!({ "network": "tcp,udp" })));
        }
    }
    let mut rules = rules;
    drop_missing_geo(&mut rules);
    json!({ "domainStrategy": r.domain_strategy, "rules": rules })
}

/// Ready-made rule sets: (enabled, outbound, geosite categories).
#[allow(non_snake_case)]
pub fn PRESETS(r: &RoutingSettings) -> [(bool, &'static str, &'static [&'static str]); 4] {
    [
        (r.preset_streaming, "proxy", &["netflix", "youtube", "spotify", "twitch", "disney", "hbo", "primevideo"]),
        (r.preset_ai, "proxy", &["openai", "anthropic", "google-gemini", "category-ai-chat-!cn"]),
        (r.preset_social, "proxy", &["telegram", "discord", "instagram", "facebook", "twitter", "tiktok", "whatsapp"]),
        (r.preset_games_direct, "direct", &["category-games", "steam", "epicgames"]),
    ]
}

/// A geosite/geoip category missing from the installed databases would stop Xray from starting:
/// such entries are dropped (and logged by the caller's core log), rules left empty go away.
fn drop_missing_geo(rules: &mut Vec<Value>) {
    for r in rules.iter_mut() {
        for (key, kind) in [("domain", "geosite"), ("ip", "geoip")] {
            if let Some(list) = r[key].as_array_mut() {
                list.retain(|v| match v.as_str().and_then(|x| x.strip_prefix(&format!("{kind}:"))) {
                    Some(cat) => super::route::has_category(kind, cat.split('@').next().unwrap_or(cat)),
                    None => true,
                });
                if list.is_empty() { r["__empty"] = json!(true); }
            }
        }
    }
    rules.retain(|r| r.get("__empty").is_none());
}
