//! "Where will this site go?" — evaluates a domain or IP against the exact routing rules Xray gets,
//! reading geosite.dat / geoip.dat directly (a tiny protobuf reader, no extra crates).

use crate::model::{AppSettings, RoutingSettings};
use crate::state::core_dir;
use serde_json::Value;
use std::net::{IpAddr, ToSocketAddrs};

#[derive(Clone, Debug, PartialEq)]
pub struct Verdict { pub action: String, pub reason: String }

// ------------------------------------------------------------------ protobuf

struct Pb<'a> { b: &'a [u8], i: usize }
impl<'a> Pb<'a> {
    fn new(b: &'a [u8]) -> Self { Self { b, i: 0 } }
    fn varint(&mut self) -> Option<u64> {
        let mut v = 0u64;
        for shift in (0..64).step_by(7) {
            let x = *self.b.get(self.i)?; self.i += 1;
            v |= ((x & 0x7f) as u64) << shift;
            if x & 0x80 == 0 { return Some(v); }
        }
        None
    }
    /// (field, wire type, payload for length-delimited / value for varint)
    fn next(&mut self) -> Option<(u64, u8, &'a [u8], u64)> {
        if self.i >= self.b.len() { return None; }
        let key = self.varint()?;
        let (field, wt) = (key >> 3, (key & 7) as u8);
        match wt {
            0 => { let v = self.varint()?; Some((field, 0, &[], v)) }
            2 => { let n = self.varint()? as usize; let s = self.b.get(self.i..self.i + n)?; self.i += n; Some((field, 2, s, 0)) }
            1 => { self.i += 8; Some((field, 1, &[], 0)) }
            5 => { self.i += 4; Some((field, 5, &[], 0)) }
            _ => None,
        }
    }
}

fn str_of(b: &[u8]) -> String { String::from_utf8_lossy(b).to_string() }

/// Domain matchers of one geosite category: (type, value). Types: 0 keyword, 1 regex, 2 domain, 3 full.
fn geosite(category: &str) -> Option<Vec<(u64, String)>> {
    let data = std::fs::read(core_dir().join("geosite.dat")).ok()?;
    let want = category.to_uppercase();
    let mut top = Pb::new(&data);
    while let Some((f, _, entry, _)) = top.next() {
        if f != 1 { continue; }
        let mut e = Pb::new(entry);
        let mut code = String::new();
        let mut domains = vec![];
        while let Some((f2, _, v, _)) = e.next() {
            match f2 {
                1 => { code = str_of(v).to_uppercase(); if code != want { break; } }
                2 => {
                    let mut d = Pb::new(v);
                    let (mut t, mut val) = (0, String::new());
                    while let Some((f3, _, dv, n)) = d.next() { match f3 { 1 => t = n, 2 => val = str_of(dv), _ => {} } }
                    domains.push((t, val));
                }
                _ => {}
            }
        }
        if code == want { return Some(domains); }
    }
    None
}

/// CIDRs of one geoip category and whether it is inverted.
fn geoip(category: &str) -> Option<(Vec<(Vec<u8>, u32)>, bool)> {
    let data = std::fs::read(core_dir().join("geoip.dat")).ok()?;
    let want = category.to_uppercase();
    let mut top = Pb::new(&data);
    while let Some((f, _, entry, _)) = top.next() {
        if f != 1 { continue; }
        let mut e = Pb::new(entry);
        let (mut code, mut cidrs, mut reverse) = (String::new(), vec![], false);
        while let Some((f2, _, v, n)) = e.next() {
            match f2 {
                1 => { code = str_of(v).to_uppercase(); if code != want { break; } }
                2 => {
                    let mut c = Pb::new(v);
                    let (mut ip, mut prefix) = (vec![], 0u32);
                    while let Some((f3, _, cv, cn)) = c.next() { match f3 { 1 => ip = cv.to_vec(), 2 => prefix = cn as u32, _ => {} } }
                    cidrs.push((ip, prefix));
                }
                3 => reverse = n != 0,
                _ => {}
            }
        }
        if code == want { return Some((cidrs, reverse)); }
    }
    None
}

/// Category names in a .dat file (cached per file and modification time).
fn categories(file: &str) -> Option<std::collections::HashSet<String>> {
    use std::sync::Mutex;
    static CACHE: Mutex<Vec<(String, std::time::SystemTime, std::collections::HashSet<String>)>> = Mutex::new(Vec::new());
    let path = core_dir().join(file);
    let mtime = std::fs::metadata(&path).and_then(|m| m.modified()).ok()?;
    if let Some(c) = CACHE.lock().unwrap().iter().find(|c| c.0 == file && c.1 == mtime) { return Some(c.2.clone()); }
    let data = std::fs::read(&path).ok()?;
    let mut set = std::collections::HashSet::new();
    let mut top = Pb::new(&data);
    while let Some((f, _, entry, _)) = top.next() {
        if f != 1 { continue; }
        let mut e = Pb::new(entry);
        if let Some((1, _, code, _)) = e.next() { set.insert(str_of(code).to_lowercase()); }
    }
    let mut c = CACHE.lock().unwrap();
    c.retain(|x| x.0 != file);
    c.push((file.to_string(), mtime, set.clone()));
    Some(set)
}

/// True when the category exists — or when the databases are not there to check (Xray will say).
pub fn has_category(kind: &str, cat: &str) -> bool {
    if kind == "geoip" && cat == "private" { return true; }
    match categories(if kind == "geosite" { "geosite.dat" } else { "geoip.dat" }) {
        Some(set) => set.contains(&cat.to_lowercase()),
        None => true,
    }
}

// ------------------------------------------------------------------ matching

fn ip_bytes(ip: &IpAddr) -> Vec<u8> { match ip { IpAddr::V4(a) => a.octets().to_vec(), IpAddr::V6(a) => a.octets().to_vec() } }

fn in_cidr(ip: &IpAddr, net: &[u8], prefix: u32) -> bool {
    let b = ip_bytes(ip);
    if b.len() != net.len() { return false; }
    let full = (prefix / 8) as usize;
    if b[..full.min(b.len())] != net[..full.min(net.len())] { return false; }
    let rem = prefix % 8;
    if rem == 0 || full >= b.len() { return true; }
    let mask = 0xffu8 << (8 - rem);
    b[full] & mask == net[full] & mask
}

fn private(ip: &IpAddr) -> bool {
    match ip {
        IpAddr::V4(a) => a.is_private() || a.is_loopback() || a.is_link_local() || a.octets()[0] == 100 && (a.octets()[1] & 0xc0) == 64,
        IpAddr::V6(a) => a.is_loopback() || (a.segments()[0] & 0xfe00) == 0xfc00 || (a.segments()[0] & 0xffc0) == 0xfe80,
    }
}

fn domain_hit(host: &str, m: &str) -> Result<bool, String> {
    let h = host.to_lowercase();
    let suffix = |d: &str| h == d || h.ends_with(&format!(".{d}"));
    if let Some(d) = m.strip_prefix("domain:") { return Ok(suffix(&d.to_lowercase())); }
    if let Some(d) = m.strip_prefix("full:") { return Ok(h == d.to_lowercase()); }
    if let Some(d) = m.strip_prefix("keyword:") { return Ok(h.contains(&d.to_lowercase())); }
    if let Some(r) = m.strip_prefix("regexp:") { return Ok(regex_lite::Regex::new(r).map(|x| x.is_match(&h)).unwrap_or(false)); }
    if let Some(cat) = m.strip_prefix("geosite:") {
        let cat = cat.split('@').next().unwrap_or(cat);
        let list = geosite(cat).ok_or_else(|| format!("geosite:{cat}"))?;
        return Ok(list.iter().any(|(t, v)| match t {
            0 => h.contains(&v.to_lowercase()),
            1 => regex_lite::Regex::new(v).map(|x| x.is_match(&h)).unwrap_or(false),
            3 => h == v.to_lowercase(),
            _ => suffix(&v.to_lowercase()),
        }));
    }
    Ok(h.contains(&m.to_lowercase()))
}

fn ip_hit(ip: &IpAddr, m: &str) -> Result<bool, String> {
    if let Some(cat) = m.strip_prefix("geoip:") {
        if cat == "private" { return Ok(private(ip)); }
        let (list, reverse) = geoip(cat).ok_or_else(|| format!("geoip:{cat}"))?;
        return Ok(list.iter().any(|(n, p)| in_cidr(ip, n, *p)) != reverse);
    }
    let (net, prefix) = m.split_once('/').unwrap_or((m, ""));
    let Ok(n) = net.parse::<IpAddr>() else { return Ok(false) };
    let p = prefix.parse().unwrap_or(if n.is_ipv4() { 32 } else { 128 });
    Ok(in_cidr(ip, &ip_bytes(&n), p))
}

fn port_hit(port: u16, spec: &str) -> bool {
    spec.split(',').any(|p| match p.trim().split_once('-') {
        Some((a, b)) => a.trim().parse::<u16>().is_ok_and(|a| a <= port) && b.trim().parse::<u16>().is_ok_and(|b| port <= b),
        None => p.trim().parse::<u16>().is_ok_and(|x| x == port),
    })
}

fn describe(rule: &Value) -> String {
    for k in ["domain", "ip", "process"] {
        if let Some(a) = rule[k].as_array() {
            let v: Vec<&str> = a.iter().filter_map(|x| x.as_str()).take(3).collect();
            return format!("{k}: {}{}", v.join(", "), if a.len() > 3 { ", …" } else { "" });
        }
    }
    if let Some(p) = rule["port"].as_str() { return format!("port: {p}"); }
    crate::t!("everything else")
}

/// One pass over the rules. `ip` is the resolved address when the strategy allows it.
fn pass(rules: &[Value], host: Option<&str>, ip: Option<&IpAddr>, port: u16, missing: &mut Vec<String>) -> Option<(String, String)> {
    for r in rules {
        if r.get("inboundTag").is_some() { continue; }
        if r.get("process").is_some() || r.get("protocol").is_some() { continue; } // depends on the program / protocol, not the site
        let mut ok = true;
        if let Some(list) = r["domain"].as_array() {
            ok = match host {
                Some(h) => list.iter().filter_map(|x| x.as_str()).any(|m| domain_hit(h, m).unwrap_or_else(|e| { missing.push(e); false })),
                None => false,
            };
        }
        if ok { if let Some(list) = r["ip"].as_array() {
            ok = match ip {
                Some(a) => list.iter().filter_map(|x| x.as_str()).any(|m| ip_hit(a, m).unwrap_or_else(|e| { missing.push(e); false })),
                None => false,
            };
        } }
        if ok { if let Some(p) = r["port"].as_str() { ok = port_hit(port, p); } }
        if ok { return Some((r["outboundTag"].as_str().unwrap_or("proxy").to_string(), describe(r))); }
    }
    None
}

/// `input`: a domain, URL or IP (optionally with :port).
pub fn check(input: &str, s: &AppSettings, r: &RoutingSettings) -> Verdict {
    let mut t = input.trim().to_string();
    if let Some((_, rest)) = t.split_once("://") { t = rest.to_string(); }
    let t = t.split(['/', '?', '#']).next().unwrap_or("").to_string();
    let (host, port) = match t.rsplit_once(':') {
        Some((h, p)) if !h.contains(':') && p.parse::<u16>().is_ok() => (h.to_string(), p.parse().unwrap()),
        _ => (t.trim_matches(['[', ']']).to_string(), 443u16),
    };
    if host.is_empty() { return Verdict { action: String::new(), reason: String::new() }; }
    let routing = super::xray::routing(s, r);
    let rules: Vec<Value> = routing["rules"].as_array().cloned().unwrap_or_default();
    let mut missing = vec![];
    let literal: Option<IpAddr> = host.parse().ok();
    let resolve = || (host.as_str(), port).to_socket_addrs().ok().and_then(|mut a| a.next()).map(|a| a.ip());
    let res = if let Some(ip) = literal {
        pass(&rules, None, Some(&ip), port, &mut missing)
    } else if r.domain_strategy == "IPOnDemand" {
        let ip = resolve();
        pass(&rules, Some(&host), ip.as_ref(), port, &mut missing)
    } else {
        pass(&rules, Some(&host), None, port, &mut missing).or_else(|| {
            if r.domain_strategy == "IPIfNonMatch" { let ip = resolve(); pass(&rules, Some(&host), ip.as_ref(), port, &mut missing) } else { None }
        })
    };
    let (tag, why) = res.unwrap_or(("proxy".into(), crate::t!("everything else")));
    let action = match tag.as_str() { "direct" => "DIRECT", "block" => "BLOCK", _ => "PROXY" }.to_string();
    missing.dedup();
    let mut reason = why;
    if !missing.is_empty() { reason += &format!(" · {}: {}", crate::t!("not in the geo databases"), missing.join(", ")); }
    Verdict { action, reason }
}
