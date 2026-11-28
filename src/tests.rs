//! Parser, importer and config-builder tests.

use crate::core::xray;
use crate::model::*;
use crate::parse::{self, links};
use base64::Engine;
use base64::engine::general_purpose::{STANDARD, URL_SAFE_NO_PAD};
use std::io::Write;

const AWG: &str = "[Interface]
PrivateKey = cHJpdmF0ZWtleXByaXZhdGVrZXlwcml2YXRla2V5MDA=
Address = 10.8.1.2/32
DNS = 1.1.1.1, 1.0.0.1
Jc = 4
Jmin = 10
Jmax = 50
S1 = 52
S2 = 87
H1 = 1234567
H2 = 2345678
H3 = 3456789
H4 = 4567890

[Peer]
PublicKey = cHVibGlja2V5cHVibGlja2V5cHVibGlja2V5cHViMDA=
PresharedKey = cHNrcHNrcHNrcHNrcHNrcHNrcHNrcHNrcHNrcHNrMDA=
AllowedIPs = 0.0.0.0/0, ::/0
Endpoint = 203.0.113.5:51820
PersistentKeepalive = 25";

fn awg_plain() -> String {
    AWG.lines().filter(|l| !(l.len() > 1 && (l.starts_with('S') || l.starts_with('H')) && l.as_bytes()[1].is_ascii_digit())).collect::<Vec<_>>().join("\n")
}

#[test]
fn vless_reality() {
    let n = links::parse("vless://11111111-2222-3333-4444-555555555555@example.com:443?type=tcp&security=reality&sni=www.microsoft.com&fp=chrome&pbk=PUBKEY&sid=ab12&flow=xtls-rprx-vision#My%20Server%20%F0%9F%87%B3%F0%9F%87%B1").unwrap();
    assert_eq!(n.protocol, "VLESS");
    assert_eq!((n.address.as_str(), n.port), ("example.com", 443));
    assert_eq!(n.security, "reality"); assert_eq!(n.public_key, "PUBKEY"); assert_eq!(n.flow, "xtls-rprx-vision");
    assert_eq!(n.name, "My Server 🇳🇱");
    let mut again = links::parse(&links::to_link(&n)).unwrap();
    again.id = n.id.clone(); again.raw_link = n.raw_link.clone();
    assert_eq!(n, again);
}

#[test]
fn vmess_base64() {
    let js = r#"{"v":"2","ps":"vm","add":"1.2.3.4","port":"8443","id":"uuid","aid":"0","net":"ws","host":"h.com","path":"/ws","tls":"tls","sni":"h.com"}"#;
    let n = links::parse(&format!("vmess://{}", STANDARD.encode(js))).unwrap();
    assert_eq!(n.network, "ws"); assert_eq!(n.path, "/ws"); assert_eq!(n.security, "tls"); assert_eq!(n.port, 8443);
}

#[test]
fn shadowsocks_forms() {
    let sip = links::parse(&format!("ss://{}@[2001:db8::1]:8388#ss1", URL_SAFE_NO_PAD.encode("aes-256-gcm:pa+ss"))).unwrap();
    assert_eq!(sip.address, "2001:db8::1"); assert_eq!(sip.password, "pa+ss"); assert_eq!(sip.method, "aes-256-gcm");
    let legacy = links::parse(&format!("ss://{}#old", STANDARD.encode("chacha20-ietf-poly1305:pw@5.6.7.8:443"))).unwrap();
    assert_eq!(legacy.address, "5.6.7.8"); assert_eq!(legacy.password, "pw");
}

#[test]
fn hysteria2_and_trojan() {
    let h = links::parse("hy2://secret@hy.example:8443?sni=hy.example&obfs=salamander&obfs-password=ob#hy").unwrap();
    assert_eq!(h.protocol, "HYSTERIA2"); assert_eq!(h.obfs_password, "ob");
    let t = links::parse("trojan://pw@t.example:443?type=grpc&serviceName=svc#tr").unwrap();
    assert_eq!(t.security, "tls"); assert_eq!(t.service_name, "svc");
}

#[test]
fn base64_subscription() {
    let body = STANDARD.encode("vless://a@h1:443#a\ntrojan://b@h2:443#b\n");
    assert_eq!(parse::parse_many(&body).len(), 2);
}

#[test]
fn amnezia_wg_conf() {
    let r = parse::import(AWG, Some("home"));
    assert_eq!(r.nodes.len(), 1);
    let n = &r.nodes[0];
    assert_eq!(n.protocol, "WIREGUARD"); assert_eq!(n.address, "203.0.113.5"); assert_eq!(n.port, 51820);
    assert_eq!(n.awg.get("Jc").map(String::as_str), Some("4")); assert!(n.needs_amnezia_engine());
    let plain = parse::wg_parse(&awg_plain(), None).unwrap();
    assert!(!plain.needs_amnezia_engine());
}

fn q_compress(s: &str) -> String {
    let raw = s.as_bytes();
    let mut out = (raw.len() as u32).to_be_bytes().to_vec();
    let mut z = flate2::write::ZlibEncoder::new(Vec::new(), flate2::Compression::default());
    z.write_all(raw).unwrap();
    out.extend(z.finish().unwrap());
    format!("vpn://{}", URL_SAFE_NO_PAD.encode(out))
}

#[test]
fn amnezia_vpn_key() {
    let awg_cfg = serde_json::json!({ "config": AWG.replace("DNS = 1.1.1.1, 1.0.0.1", "DNS = $PRIMARY_DNS") }).to_string();
    let xray_cfg = r#"{"outbounds":[{"protocol":"vless","settings":{"vnext":[{"address":"x.example","port":443,"users":[{"id":"u","flow":"xtls-rprx-vision","encryption":"none"}]}]},"streamSettings":{"network":"tcp","security":"reality","realitySettings":{"serverName":"sni.example","publicKey":"PK","shortId":"01","fingerprint":"chrome"}}},{"protocol":"freedom","tag":"direct"}]}"#;
    let json = serde_json::json!({
        "containers": [
            {"container": "amnezia-awg", "awg": {"last_config": awg_cfg}},
            {"container": "amnezia-xray", "xray": {"last_config": xray_cfg}}
        ],
        "defaultContainer": "amnezia-awg", "description": "My Amnezia", "dns1": "9.9.9.9", "hostName": "203.0.113.5"
    }).to_string();
    let nodes = parse::parse_many(&q_compress(&json));
    assert_eq!(nodes.len(), 2);
    assert_eq!(nodes[0].wg_dns, "9.9.9.9"); assert!(nodes[0].needs_amnezia_engine());
    assert_eq!(nodes[1].protocol, "VLESS"); assert_eq!(nodes[1].public_key, "PK"); assert_eq!(nodes[1].name, "My Amnezia · XRAY");
}

#[test]
fn xray_json_array_subscription() {
    let body = r#"[{"remarks":"🇩🇪 DE","outbounds":[{"tag":"proxy","protocol":"vless","settings":{"vnext":[{"address":"de.example","port":443,"users":[{"id":"id1","encryption":"none"}]}]},"streamSettings":{"network":"xhttp","security":"tls","tlsSettings":{"serverName":"de.example","alpn":["h2"]},"xhttpSettings":{"path":"/x","mode":"auto"}}}]},
        {"remarks":"🇫🇮 FI","outbounds":[{"tag":"proxy","protocol":"trojan","settings":{"servers":[{"address":"fi.example","port":443,"password":"p"}]}}]}]"#;
    let nodes = parse::parse_many(body);
    assert_eq!(nodes.iter().map(|n| n.name.as_str()).collect::<Vec<_>>(), ["🇩🇪 DE", "🇫🇮 FI"]);
    assert_eq!(nodes[0].network, "xhttp"); assert_eq!(nodes[0].path, "/x");
}

#[test]
fn config_never_contains_removed_allow_insecure() {
    let n = links::parse("vless://u@h:443?security=tls&allowInsecure=1&sni=h#x").unwrap();
    let cfg = xray::build(&n, &AppSettings::default(), &RoutingSettings::default());
    assert!(!cfg.contains("allowInsecure"));
}

#[test]
fn clash_yaml_with_rules() {
    let yaml = r#"proxies:
  - name: "🇩🇪 DE"
    type: vless
    server: de.example
    port: 443
    uuid: 11111111-2222-3333-4444-555555555555
    network: tcp
    tls: true
    servername: www.microsoft.com
    client-fingerprint: chrome
    flow: xtls-rprx-vision
    reality-opts:
      public-key: PUB
      short-id: ab
  - {name: ss1, type: ss, server: 1.2.3.4, port: 8388, cipher: aes-256-gcm, password: pw}
  - {name: hy, type: hysteria2, server: hy.example, port: 443, password: secret, sni: hy.example}
  - {name: tr, type: trojan, server: tr.example, port: 443, password: p, network: ws, ws-opts: {path: /ws, headers: {Host: cdn.example}}}
rules:
  - DOMAIN-SUFFIX,gosuslugi.ru,DIRECT
  - PROCESS-NAME,Telegram.exe,Proxy
  - GEOIP,RU,DIRECT
  - MATCH,Proxy"#;
    let r = parse::import(yaml, None);
    assert_eq!(r.nodes.len(), 4);
    assert_eq!(r.nodes[0].security, "reality"); assert_eq!(r.nodes[0].public_key, "PUB"); assert_eq!(r.nodes[0].name, "🇩🇪 DE");
    assert_eq!(r.nodes[3].path, "/ws"); assert_eq!(r.nodes[3].host, "cdn.example");
    assert_eq!(r.rules.iter().map(|x| x.kind.as_str()).collect::<Vec<_>>(), ["DOMAIN", "PROCESS", "GEOIP"]);
    assert_eq!(r.rules[1].action, "PROXY");
}

#[test]
fn sing_box_outbounds() {
    let sb = r#"{"outbounds":[
      {"type":"vless","tag":"vl","server":"a.example","server_port":443,"uuid":"u","flow":"xtls-rprx-vision",
       "tls":{"enabled":true,"server_name":"sni.example","utls":{"enabled":true,"fingerprint":"chrome"},"reality":{"enabled":true,"public_key":"PK","short_id":"01"}}},
      {"type":"hysteria2","tag":"hy","server":"h.example","server_port":8443,"password":"pw","obfs":{"type":"salamander","password":"ob"},"tls":{"enabled":true,"server_name":"h.example"}},
      {"type":"direct","tag":"direct"}]}"#;
    let nodes = parse::parse_many(sb);
    assert_eq!(nodes.len(), 2);
    assert_eq!(nodes[0].security, "reality"); assert_eq!(nodes[0].public_key, "PK");
    assert_eq!(nodes[1].protocol, "HYSTERIA2"); assert_eq!(nodes[1].obfs_password, "ob");
}

#[test]
fn jsonc_and_link_array() {
    let jsonc = "// comment\n{\n  \"outbounds\": [ /* inline */ {\"protocol\":\"trojan\",\"tag\":\"t\",\"settings\":{\"servers\":[{\"address\":\"t.example\",\"port\":443,\"password\":\"p\"}]}}, ],\n}";
    assert_eq!(parse::parse_many(jsonc).len(), 1);
    assert_eq!(parse::parse_many(r#"["vless://a@h1:443#a","trojan://b@h2:443#b"]"#).len(), 2);
}

#[test]
fn proxy_pools_and_nested() {
    let r = parse::import("1.2.3.4:1080\n5.6.7.8:3128:user:pass\nu:p@9.9.9.9:1080\nhttps://panel.example/sub/xyz", None);
    assert_eq!(r.nodes.len(), 3);
    assert_eq!(r.nodes[1].username, "user");
    assert_eq!(r.nested, ["https://panel.example/sub/xyz"]);
}

#[test]
fn happ_routing_profile() {
    let profile = r#"{"Name":"RU","GlobalProxy":"true","DirectSites":["geosite:category-ru","domain:yandex.ru"],"DirectIp":["geoip:ru"],"BlockSites":["geosite:category-ads-all"],"DomainStrategy":"IPIfNonMatch"}"#;
    let r = parse::import(&format!("happ://routing/add/{}", STANDARD.encode(profile)), None);
    let p = r.routing_profile.expect("profile");
    let mut cur = RoutingSettings::default();
    cur.rules.push(RouteRule { value: "mine.example".into(), ..Default::default() });
    let applied = parse::happ_routing_apply(&p, &cur).unwrap();
    assert_eq!(applied.mode, "RULE");
    assert_eq!(applied.rules.iter().map(|x| x.action.as_str()).collect::<Vec<_>>(), ["BLOCK", "DIRECT", "DIRECT", "PROXY"]);
    assert_eq!(applied.rules.last().unwrap().value, "mine.example");
}

const BALANCER: &str = r#"{
  "remarks": "Auto 🇪🇺",
  "outbounds": [
    {"tag":"proxy-nl","protocol":"vless","settings":{"vnext":[{"address":"nl.example","port":443,"users":[{"id":"11111111-2222-3333-4444-555555555555","encryption":"none"}]}]},
     "streamSettings":{"network":"tcp","security":"tls","tlsSettings":{"serverName":"nl.example"}}},
    {"tag":"proxy-de","protocol":"trojan","settings":{"servers":[{"address":"de.example","port":443,"password":"p"}]},
     "streamSettings":{"network":"tcp","security":"tls","tlsSettings":{"serverName":"de.example"}}},
    {"tag":"direct","protocol":"freedom"}
  ],
  "observatory": {"subjectSelector":["proxy-"],"probeURL":"https://www.gstatic.com/generate_204","probeInterval":"1m"},
  "routing": {"balancers":[{"tag":"auto","selector":["proxy-"],"strategy":{"type":"leastPing"}}],
              "rules":[{"type":"field","network":"tcp,udp","balancerTag":"auto"}]}
}"#;

#[test]
fn xray_config_with_balancer_kept_whole() {
    let nodes = parse::parse_many(BALANCER);
    assert_eq!(nodes.len(), 1);
    let n = &nodes[0];
    assert_eq!(n.protocol, "XRAY_CONFIG");
    assert_eq!(n.balancer_members, ["proxy-nl", "proxy-de"]);
    assert_eq!(n.name, "Auto 🇪🇺");
    let built = xray::build(n, &AppSettings::default(), &RoutingSettings::default());
    assert!(built.contains("balancers") && built.contains("socks-in") && built.contains("StatsService"));
}

#[test]
fn direct_dns_in_any_form() {
    for (input, host, port) in [
        ("77.88.8.8", "77.88.8.8", None), ("77.88.8.8:53", "77.88.8.8", Some(53)),
        ("2a02:6b8::feed:ff", "2a02:6b8::feed:ff", None), ("[2a02:6b8::feed:ff]:53", "2a02:6b8::feed:ff", Some(53)),
        ("https://dns.yandex.ru/dns-query", "dns.yandex.ru", None), ("tcp://[2a02:6b8::feed:ff]:53", "2a02:6b8::feed:ff", Some(53)),
        ("https://1.1.1.1:8443/dns-query?x=1", "1.1.1.1", Some(8443)), ("localhost", "localhost", None),
    ] {
        assert_eq!(xray::dns_endpoint(input), (host.to_string(), port), "{input}");
        assert!(xray::valid_dns(input), "{input}");
    }
    for bad in ["", "   ", "https://", "1.1.1.1 8.8.8.8"] { assert!(!xray::valid_dns(bad), "{bad:?}"); }

    let rule_for = |dns: &str| {
        let s = AppSettings { direct_dns: dns.into(), ..AppSettings::default() };
        let rules = xray::routing(&s, &RoutingSettings::default())["rules"].as_array().unwrap().clone();
        rules.into_iter().find(|r| r["outboundTag"] == "direct" && (r.get("ip").is_some() || r.get("domain").is_some())).unwrap()
    };
    assert_eq!(rule_for("[2a02:6b8::feed:ff]:53")["ip"], serde_json::json!(["2a02:6b8::feed:ff"]));
    assert_eq!(rule_for("77.88.8.8:53")["ip"], serde_json::json!(["77.88.8.8"]));
    assert_eq!(rule_for("https://dns.yandex.ru/dns-query")["domain"], serde_json::json!(["full:dns.yandex.ru"]));
    // nothing broken when the field is empty: no rule with an empty address
    let s = AppSettings { direct_dns: String::new(), ..AppSettings::default() };
    let cfg = xray::build_value(&ProxyNode::default(), &s, &RoutingSettings::default());
    assert!(!cfg.to_string().contains("\"ip\":[\"\"]"));
    assert!(cfg["dns"]["servers"].as_array().unwrap().iter().all(|x| x["address"] != ""));
}

/// Writes sample configs (system-proxy mode only) for `xray run -test`: target/xray-configs.
#[test]
fn dump_sample_configs() {
    let dir = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("target/xray-configs");
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    let vm = format!("vmess://{}", STANDARD.encode(r#"{"v":"2","ps":"vm","add":"1.2.3.4","port":"443","id":"11111111-2222-3333-4444-555555555555","aid":"0","net":"ws","host":"h.com","path":"/ws","tls":"tls"}"#));
    let ss = format!("ss://{}@5.6.7.8:8388#ss", URL_SAFE_NO_PAD.encode("aes-256-gcm:pass"));
    let mut nodes: Vec<(&str, ProxyNode)> = [
        ("vless-reality", "vless://11111111-2222-3333-4444-555555555555@example.com:443?type=tcp&security=reality&sni=www.microsoft.com&fp=chrome&pbk=SbVKOEMjK0sIlbwg4akyBg5mL5KZwwB-ed4eEE7YnRc&sid=ab12&flow=xtls-rprx-vision#a"),
        ("vless-xhttp", "vless://11111111-2222-3333-4444-555555555555@example.com:443?type=xhttp&security=tls&sni=example.com&path=%2Fx&mode=auto&alpn=h2#b"),
        ("vmess-ws", vm.as_str()),
        ("trojan-grpc", "trojan://pw@t.example:443?type=grpc&serviceName=svc&sni=t.example#tr"),
        ("ss", ss.as_str()),
        ("hy2", "hy2://secret@hy.example:8443?sni=hy.example&obfs=salamander&obfs-password=ob#hy"),
    ].into_iter().map(|(k, l)| (k, links::parse(l).unwrap())).collect();
    nodes.push(("awg-junk", parse::wg_parse(&awg_plain(), None).unwrap()));
    nodes.push(("raw-balancer", parse::parse_many(BALANCER).remove(0)));
    let rule = |kind: &str, value: &str, action: &str| RouteRule { kind: kind.into(), value: value.into(), action: action.into(), ..Default::default() };
    let mut rr = RoutingSettings::default();
    rr.rules = vec![rule("DOMAIN", "example.org", "DIRECT"), rule("PROCESS", "telegram.exe", "PROXY"), rule("PORT", "6881-6889", "BLOCK")];
    let presets = RoutingSettings { preset_streaming: true, preset_ai: true, preset_social: true, preset_games_direct: true, preset_block_torrents: true, ..Default::default() };
    let routings = [("rule", rr), ("presets", presets), ("global", RoutingSettings { mode: "GLOBAL".into(), ..Default::default() }), ("direct", RoutingSettings { mode: "DIRECT".into(), ..Default::default() })];
    let mut s = AppSettings::default();
    s.mux = true;
    for (name, n) in &nodes {
        for (rn, r) in &routings { std::fs::write(dir.join(format!("{name}-{rn}.json")), xray::build(n, &s, r)).unwrap(); }
    }
    for (name, dns) in [("doh-host", "https://dns.yandex.ru/dns-query"), ("ipv6-port", "[2a02:6b8::feed:ff]:53"), ("ipv6", "2a02:6b8::feed:ff"),
                        ("ipv4-port", "77.88.8.8:53"), ("tcp-url", "tcp://77.88.8.8:53")] {
        let st = AppSettings { direct_dns: dns.into(), remote_dns: if name == "ipv4-port" { "1.1.1.1:53".into() } else { AppSettings::default().remote_dns }, ..AppSettings::default() };
        std::fs::write(dir.join(format!("direct-dns-{name}.json")), xray::build(&nodes[0].1, &st, &RoutingSettings::default())).unwrap();
    }
    for m in ["INCLUDE", "EXCLUDE"] {
        let mut st = AppSettings::default();
        st.split = SplitTunnel { enabled: true, mode: m.into(), apps: vec!["chrome.exe".into(), "Telegram.exe".into()] };
        std::fs::write(dir.join(format!("split-{}.json", m.to_lowercase())), xray::build(&nodes[0].1, &st, &RoutingSettings::default())).unwrap();
    }
}

#[test]
fn user_info_header() {
    assert_eq!(crate::core::subs::parse_user_info("upload=1; download=2; total=3; expire=4"), Some([1, 2, 3, 4]));
    assert_eq!(crate::core::subs::decode_header("base64:SGVsbG8="), "Hello");
}

#[test]
fn version_compare() {
    use crate::core::update::newer;
    assert!(newer("v1.2.0", "1.1.9")); assert!(!newer("1.1.0", "1.1.0")); assert!(newer("1.10.0", "1.9.3")); assert!(newer("2.0.1", "2.0.0-alpha.1"));
}

#[test]
fn friendly_errors() {
    assert_eq!(crate::core::errors::explain("listen tcp 127.0.0.1:10808: bind: Only one usage of each socket address").title, "Port already in use");
    assert_eq!(crate::core::errors::explain("dial tcp: i/o timeout").title, "Server not responding");
}

/// Every string the app shows through t!() / @tr() has a Russian translation.
#[test]
fn russian_coverage() {
    let table = std::fs::read_to_string(concat!(env!("CARGO_MANIFEST_DIR"), "/src/i18n.rs")).unwrap();
    let have: std::collections::HashSet<String> = table.lines().filter_map(|l| l.trim().strip_prefix("(\"").and_then(|r| r.split_once("\", \"")).map(|x| x.0.to_string())).collect();
    let mut missing = vec![];
    let mut scan = |dir: &str, pat: &str| for e in std::fs::read_dir(concat!(env!("CARGO_MANIFEST_DIR"), "/").to_string() + dir).unwrap().flatten() {
        let p = e.path();
        if p.is_dir() { continue; }
        let text = std::fs::read_to_string(&p).unwrap_or_default();
        for (at, _) in text.match_indices(pat) {
            if text[..at].chars().last().is_some_and(|c| c.is_alphanumeric() || c == '_') { continue; } // format!( etc.
            let part = &text[at + pat.len()..];
            let mut s = String::new(); let mut esc = false;
            for c in part.chars() { if esc { s.push(c); esc = false; } else if c == '\\' { s.push(c); esc = true; } else if c == '"' { break; } else { s.push(c); } }
            if !have.contains(&s) && s.chars().any(|c| c.is_alphabetic()) { missing.push(format!("{}: {s}", p.file_name().unwrap().to_string_lossy())); }
        }
    };
    for d in ["src", "src/core"] { scan(d, "t!(\""); }
    scan("ui", "@tr(\"");
    missing.sort(); missing.dedup();
    assert!(missing.is_empty(), "no Russian for:\n{}", missing.join("\n"));
}

#[test]
fn widgets_never_overlap() {
    let mut ws = default_home_widgets();
    let id = ws[3].id.clone();
    crate::layout_for_tests::place(&mut ws, &id, Some((0, 0)), Some((6, 4)));
    for k in ["SITES", "USAGE", "SERVERS"] { crate::layout_for_tests::add(&mut ws, k); }
    for (i, a) in ws.iter().enumerate() { for b in &ws[i + 1..] {
        assert!(!(a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h), "{a:?} overlaps {b:?}");
    } }
}

#[test]
fn widgets_keep_their_minimum_and_the_grid() {
    use crate::layout_for_tests as l;
    let no_overlap = |ws: &Vec<HomeWidget>| for (i, a) in ws.iter().enumerate() { for b in &ws[i + 1..] {
        assert!(!(a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h), "{a:?} overlaps {b:?}");
    } };
    let mut ws = default_home_widgets();
    let connect = ws.iter().find(|w| w.kind == "CONNECT").unwrap().id.clone();
    // shrunk below what fits, dragged off the grid on both sides
    l::place(&mut ws, &connect, Some((-5, -3)), Some((1, 1)));
    let c = ws.iter().find(|w| w.id == connect).unwrap();
    assert_eq!((c.x, c.y), (0, 0));
    assert_eq!((c.w, c.h), l::min_size("CONNECT"));
    l::place(&mut ws, &connect, Some((20, 0)), None);
    let c = ws.iter().find(|w| w.id == connect).unwrap();
    assert_eq!(c.x + c.w, 12, "pushed back inside the 12 columns");
    no_overlap(&ws);
    // removing a widget leaves no hole at the top
    let top = ws.iter().filter(|w| w.y == 0).map(|w| w.id.clone()).collect::<Vec<_>>();
    ws.retain(|w| !top.contains(&w.id));
    l::compact(&mut ws);
    assert!(ws.iter().any(|w| w.y == 0), "the rest moved up: {ws:?}");
    no_overlap(&ws);
    // a newly added widget is never smaller than its minimum
    l::add(&mut ws, "MODES");
    let m = ws.iter().find(|w| w.kind == "MODES").unwrap();
    assert!(m.w >= l::min_size("MODES").0 && m.h >= l::min_size("MODES").1);
    no_overlap(&ws);
}

#[test]
fn route_check_follows_the_rules() {
    use crate::core::route::check;
    let mut r = RoutingSettings { bypass_ru: false, block_ads: false, domain_strategy: "AsIs".into(), ..Default::default() };
    let rule = |kind: &str, value: &str, action: &str| RouteRule { kind: kind.into(), value: value.into(), action: action.into(), ..Default::default() };
    r.rules = vec![rule("DOMAIN", "example.org", "DIRECT"), rule("IP", "1.2.3.0/24", "BLOCK"), rule("PORT", "6881-6889", "BLOCK")];
    let s = AppSettings::default();
    assert_eq!(check("https://sub.example.org/page", &s, &r).action, "DIRECT");
    assert_eq!(check("1.2.3.4", &s, &r).action, "BLOCK");
    assert_eq!(check("8.8.8.8:6885", &s, &r).action, "BLOCK");
    assert_eq!(check("192.168.1.10", &s, &r).action, "DIRECT"); // LAN preset
    assert_eq!(check("other.net", &s, &r).action, "PROXY");
    r.mode = "DIRECT".into();
    assert_eq!(check("other.net", &s, &r).action, "DIRECT");
}

/// Real geo databases (set UPLINX_TEST_GEO to a folder with geosite.dat / geoip.dat): `cargo test -- --ignored`.
#[test]
#[ignore]
fn route_check_with_real_geo() {
    let dir = std::env::var("UPLINX_TEST_GEO").expect("UPLINX_TEST_GEO");
    let home = std::env::temp_dir().join("uplinx-geo-test");
    std::fs::create_dir_all(home.join("core")).unwrap();
    for f in ["geosite.dat", "geoip.dat"] { std::fs::copy(std::path::Path::new(&dir).join(f), home.join("core").join(f)).unwrap(); }
    unsafe { std::env::set_var("UPLINX_HOME", &home); }
    let s = AppSettings::default();
    let r = RoutingSettings { domain_strategy: "AsIs".into(), ..Default::default() }; // RU direct + ads blocked by default
    let v = crate::core::route::check("doubleclick.net", &s, &r);
    assert_eq!(v.action, "BLOCK", "{v:?}");
    let v = crate::core::route::check("yandex.ru", &s, &r);
    assert_eq!(v.action, "DIRECT", "{v:?}");
    let v = crate::core::route::check("5.255.255.5", &s, &r); // a Yandex address (geoip:ru)
    assert_eq!(v.action, "DIRECT", "{v:?}");
    let v = crate::core::route::check("youtube.com", &s, &r);
    assert_eq!(v.action, "PROXY", "{v:?}");
}

#[test]
fn release_notes_as_plain_text() {
    let t = crate::view::plain_notes("## Uplinx 2.0.2\n\n- **Bold** and `code`\n\n**Downloads**\n- `x.msi`");
    assert_eq!(t, "Uplinx 2.0.2\n\n•  Bold and code");
}

#[test]
fn singbox_configs() {
    use crate::core::singbox::{build_value, supports};
    let dir = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("target/singbox-configs");
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    let links = [
        ("vless-reality", "vless://11111111-2222-3333-4444-555555555555@example.com:443?type=tcp&security=reality&sni=www.microsoft.com&fp=chrome&pbk=SbVKOEMjK0sIlbwg4akyBg5mL5KZwwB-ed4eEE7YnRc&sid=ab12&flow=xtls-rprx-vision#a"),
        ("trojan-grpc", "trojan://pw@t.example:443?type=grpc&serviceName=svc&sni=t.example#tr"),
        ("hy2", "hy2://secret@hy.example:8443?sni=hy.example&obfs=salamander&obfs-password=ob#hy"),
    ];
    let mut nodes: Vec<(&str, ProxyNode)> = links.iter().map(|(k, l)| (*k, links::parse(l).unwrap())).collect();
    nodes.push(("vmess-ws", links::parse(&format!("vmess://{}", STANDARD.encode(r#"{"v":"2","ps":"vm","add":"1.2.3.4","port":"443","id":"11111111-2222-3333-4444-555555555555","aid":"0","net":"ws","host":"h.com","path":"/ws","tls":"tls"}"#))).unwrap()));
    nodes.push(("ss", links::parse(&format!("ss://{}@5.6.7.8:8388#ss", URL_SAFE_NO_PAD.encode("aes-256-gcm:pass"))).unwrap()));
    nodes.push(("wg", parse::wg_parse(&awg_plain().lines().filter(|l| !l.starts_with('J')).collect::<Vec<_>>().join("\n"), None).unwrap()));
    let xhttp = links::parse("vless://u@h:443?type=xhttp&security=tls&sni=h#x").unwrap();
    assert!(!supports(&xhttp), "xhttp stays on Xray");
    let mut r = RoutingSettings { preset_streaming: true, preset_block_torrents: true, ..Default::default() };
    r.rules = vec![RouteRule { kind: "PROCESS".into(), value: "telegram.exe".into(), action: "PROXY".into(), ..Default::default() },
                   RouteRule { kind: "PORT".into(), value: "6881-6889".into(), action: "BLOCK".into(), ..Default::default() }];
    let s = AppSettings::default();
    for (name, n) in &nodes {
        assert!(supports(n), "{name}");
        let v = build_value(n, &s, &r);
        std::fs::write(dir.join(format!("{name}.json")), serde_json::to_string_pretty(&v).unwrap()).unwrap();
        if *name == "wg" {
            assert_eq!(v["endpoints"][0]["type"], "wireguard");
        } else {
            assert_eq!(v["outbounds"][0]["tag"], "proxy");
        }
        let rules = v["route"]["rules"].as_array().unwrap();
        assert!(rules.iter().any(|x| x["process_name"][0] == "telegram.exe"));
        assert!(rules.iter().any(|x| x["port_range"][0] == "6881:6889" && x["action"] == "reject"));
        assert!(rules.iter().any(|x| x["protocol"][0] == "bittorrent" && x["action"] == "reject"));
        assert_eq!(v["route"]["find_process"], true);
        let sets: Vec<&str> = v["route"]["rule_set"].as_array().unwrap().iter().map(|x| x["tag"].as_str().unwrap()).collect();
        assert!(sets.contains(&"geosite-netflix") && sets.contains(&"geosite-category-ads-all") && sets.contains(&"geoip-ru"));
    }
    let vr = build_value(&nodes[0].1, &s, &r);
    assert_eq!(vr["outbounds"][0]["tls"]["reality"]["public_key"], "SbVKOEMjK0sIlbwg4akyBg5mL5KZwwB-ed4eEE7YnRc");
    assert_eq!(vr["outbounds"][0]["flow"], "xtls-rprx-vision");
    let hy = build_value(&nodes[2].1, &s, &r);
    assert_eq!(hy["outbounds"][0]["obfs"]["type"], "salamander");
    let mut tun = AppSettings::default();
    tun.connect_mode = "TUN".into();
    let t = build_value(&nodes[0].1, &tun, &RoutingSettings::default());
    assert_eq!(t["inbounds"][0]["type"], "tun");
    std::fs::write(dir.join("tun.json"), serde_json::to_string_pretty(&t).unwrap()).unwrap();
}
