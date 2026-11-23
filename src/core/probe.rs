//! Real delay ("URL test") and speed test without touching the system: a throwaway Xray with one local
//! HTTP inbound per server, outbounds bound to the physical adapter so another VPN can't fake results.

use super::{xray, xray_exe, CREATE_NO_WINDOW};
use crate::model::{AppSettings, ProxyNode, SpeedResult, now_ms};
use crate::state::{core_dir, home};
use serde_json::{json, Value};
use std::io::Read;
use std::net::TcpListener;
use std::os::windows::process::CommandExt;
use std::process::{Child, Command, Stdio};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

pub struct Target { pub key: String, pub outbound: Value }

/// One target per plain server, one per member for balancers; none for AmneziaWG-only configs.
pub fn targets(n: &ProxyNode, s: &AppSettings) -> Vec<Target> {
    if n.needs_amnezia_engine() { return vec![]; }
    if n.protocol == "XRAY_CONFIG" {
        let cfg: Value = serde_json::from_str(&n.raw_config).unwrap_or(Value::Null);
        let outs: Vec<&Value> = cfg["outbounds"].as_array().map(|a| a.iter().collect()).unwrap_or_default();
        let wanted: Vec<String> = if n.balancer_members.is_empty() {
            outs.first().and_then(|o| o["tag"].as_str()).map(|t| vec![t.to_string()]).unwrap_or_default()
        } else { n.balancer_members.clone() };
        return outs.into_iter()
            .filter(|o| o["tag"].as_str().is_some_and(|t| wanted.iter().any(|w| w == t)) && o.get("proxySettings").is_none())
            .map(|o| Target { key: format!("{}#{}", n.id, o["tag"].as_str().unwrap()), outbound: o.clone() }).collect();
    }
    let mut ps = s.clone();
    ps.connect_mode = "SYSTEM_PROXY".into();
    ps.mux = false;
    vec![Target { key: n.id.clone(), outbound: xray::outbound(n, &ps) }]
}

static PHYSICAL: Mutex<Option<(Instant, Option<String>)>> = Mutex::new(None);

/// Same as [physical_interface] but never from the cache (after a network change).
pub fn physical_interface_fresh() -> Option<String> { *PHYSICAL.lock().unwrap() = None; physical_interface() }

/// Adapter carrying the default route, skipping VPN/TUN adapters. Cached for a minute.
pub fn physical_interface() -> Option<String> {
    if let Some((t, name)) = PHYSICAL.lock().unwrap().as_ref() { if t.elapsed() < Duration::from_secs(60) { return name.clone(); } }
    let ps = "Get-NetRoute -DestinationPrefix 0.0.0.0/0 -ErrorAction SilentlyContinue | Sort-Object RouteMetric | \
        ForEach-Object { Get-NetAdapter -InterfaceIndex $_.ifIndex -ErrorAction SilentlyContinue } | \
        Where-Object { $_.Status -eq 'Up' -and $_.InterfaceDescription -notmatch 'tun|tunnel|wintun|wireguard|tap|vpn|virtual' -and $_.Name -notmatch 'tun|vpn|xray|happ|uplinx' } | \
        Select-Object -First 1 -ExpandProperty Name";
    let name = Command::new("powershell").args(["-NoProfile", "-NonInteractive", "-Command", ps]).creation_flags(CREATE_NO_WINDOW).output().ok()
        .and_then(|o| String::from_utf8_lossy(&o.stdout).trim().lines().last().map(|l| l.trim().to_string()))
        .filter(|l| !l.is_empty() && !l.contains("Exception"));
    *PHYSICAL.lock().unwrap() = Some((Instant::now(), name.clone()));
    name
}

fn bind(mut o: Value, iface: &Option<String>) -> Value {
    if let Some(i) = iface {
        if !o["streamSettings"].is_object() { o["streamSettings"] = json!({}); }
        if !o["streamSettings"]["sockopt"].is_object() { o["streamSettings"]["sockopt"] = json!({}); }
        o["streamSettings"]["sockopt"]["interface"] = json!(i);
    }
    o
}

fn free_ports(n: usize) -> Vec<u16> {
    let l: Vec<TcpListener> = (0..n).filter_map(|_| TcpListener::bind("127.0.0.1:0").ok()).collect();
    l.iter().filter_map(|x| x.local_addr().ok().map(|a| a.port())).collect()
}

struct Throwaway { child: Child, file: std::path::PathBuf }
impl Drop for Throwaway { fn drop(&mut self) { let _ = self.child.kill(); let _ = self.child.wait(); let _ = std::fs::remove_file(&self.file); } }

fn spawn(cfg: &Value) -> anyhow::Result<Throwaway> {
    let file = home().join(format!("probe-{}.json", uuid::Uuid::new_v4().simple()));
    std::fs::write(&file, cfg.to_string())?;
    let child = Command::new(xray_exe()).args(["run", "-c", &file.to_string_lossy()]).current_dir(core_dir())
        .env("XRAY_LOCATION_ASSET", core_dir()).stdout(Stdio::null()).stderr(Stdio::null()).creation_flags(CREATE_NO_WINDOW).spawn()?;
    let mut t = Throwaway { child, file };
    std::thread::sleep(Duration::from_millis(700));
    anyhow::ensure!(t.child.try_wait()?.is_none(), "Xray didn't start");
    Ok(t)
}

pub fn proxied_agent(port: u16, timeout: Duration) -> ureq::Agent {
    ureq::Agent::config_builder().proxy(ureq::Proxy::new(&format!("http://127.0.0.1:{port}")).ok())
        .timeout_global(Some(timeout)).timeout_connect(Some(Duration::from_secs(6))).http_status_as_error(false).max_redirects(0).build().into()
}

pub fn direct_agent(timeout: Duration) -> ureq::Agent {
    ureq::Agent::config_builder().timeout_global(Some(timeout)).timeout_connect(Some(Duration::from_secs(6))).http_status_as_error(false).max_redirects(0).build().into()
}

/// GET; (ms, status). Body is drained. -1 on failure.
pub fn timed_get(agent: &ureq::Agent, url: &str) -> (i32, u16) {
    let t0 = Instant::now();
    match agent.get(url).header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Uplinx").call() {
        Ok(r) => {
            let code = r.status().as_u16();
            let mut body = r.into_body();
            let _ = std::io::copy(&mut body.as_reader().take(256 * 1024), &mut std::io::sink());
            ((t0.elapsed().as_millis() as i32).max(1), code)
        }
        Err(_) => (-1, 0),
    }
}

/// Warm-up request (pays for the TLS handshake), then the measured one — as other clients report it.
pub fn measure(agent: &ureq::Agent, url: &str) -> i32 {
    timed_get(agent, url);
    let (ms, code) = timed_get(agent, url);
    if code >= 500 || code == 0 { -1 } else { ms }
}

pub fn concurrency_for(n: usize) -> usize { if n > 45 { 25 } else { 10 } }

/// Probes all targets; `on_result(key, ms)` is called from worker threads. Blocks until done or `cancel`.
pub fn probe(targets: Vec<Target>, url: &str, cancel: &AtomicBool, on_result: &(dyn Fn(&str, i32) + Sync)) {
    if !xray_exe().exists() || targets.is_empty() { return; }
    let iface = physical_interface();
    let conc = concurrency_for(targets.len());
    for chunk in targets.chunks(120) {
        if cancel.load(Ordering::Relaxed) { return; }
        let ports = free_ports(chunk.len());
        if ports.len() < chunk.len() { chunk.iter().for_each(|t| on_result(&t.key, -1)); continue; }
        let inbounds: Vec<Value> = ports.iter().enumerate().map(|(i, p)| json!({"tag": format!("in{i}"), "protocol": "http", "listen": "127.0.0.1", "port": p})).collect();
        let outbounds: Vec<Value> = chunk.iter().enumerate().map(|(i, t)| { let mut o = t.outbound.clone(); o["tag"] = json!(format!("out{i}")); bind(o, &iface) }).collect();
        let rules: Vec<Value> = (0..chunk.len()).map(|i| json!({"type": "field", "inboundTag": [format!("in{i}")], "outboundTag": format!("out{i}")})).collect();
        let cfg = json!({"log": {"loglevel": "none"}, "inbounds": inbounds, "outbounds": outbounds, "routing": {"rules": rules}});
        let Ok(_proc) = spawn(&cfg) else { chunk.iter().for_each(|t| on_result(&t.key, -1)); continue };
        let next = AtomicUsize::new(0);
        std::thread::scope(|sc| {
            for _ in 0..conc.min(chunk.len()) {
                sc.spawn(|| loop {
                    let i = next.fetch_add(1, Ordering::Relaxed);
                    if i >= chunk.len() || cancel.load(Ordering::Relaxed) { break; }
                    let ms = measure(&proxied_agent(ports[i], Duration::from_secs(8)), url);
                    on_result(&chunk[i].key, ms);
                });
            }
        });
    }
}

/// Delay through the running local proxy (while connected).
pub fn through_proxy(http_port: u16, url: &str) -> i32 { measure(&proxied_agent(http_port, Duration::from_secs(8)), url) }

// ------------------------------------------------------------------ speed test

#[derive(Clone, Copy, Debug, PartialEq, Default)]
pub enum SpeedPhase { #[default] Preparing, Ping, Download, Upload, Done }

#[derive(Clone, Debug, Default)]
pub struct SpeedProgress { pub phase: SpeedPhase, pub mbps: f64, pub fraction: f32, pub down: Option<f64>, pub up: Option<f64>, pub ping: Option<i32> }

const DOWN_URL: &str = "https://speed.cloudflare.com/__down?bytes=200000000";
const UP_URL: &str = "https://speed.cloudflare.com/__up";
const DOWN_SECS: f64 = 10.0;
const UP_SECS: f64 = 8.0;
const UP_BYTES: u64 = 25_000_000;

fn mbps(bytes: u64, secs: f64) -> f64 { if secs <= 0.0 { 0.0 } else { bytes as f64 * 8.0 / 1e6 / secs } }

struct UpBody<'a> { sent: u64, t0: Instant, last: Instant, last_bytes: u64, down: f64, ping: i32, cb: &'a dyn Fn(SpeedProgress), sent_out: Arc<Mutex<u64>> }
impl Read for UpBody<'_> {
    fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
        if self.sent >= UP_BYTES || self.t0.elapsed().as_secs_f64() > UP_SECS { return Ok(0); }
        let n = buf.len().min(64 * 1024).min((UP_BYTES - self.sent) as usize);
        buf[..n].fill(0);
        self.sent += n as u64;
        *self.sent_out.lock().unwrap() = self.sent;
        if self.last.elapsed() > Duration::from_millis(200) {
            (self.cb)(SpeedProgress { phase: SpeedPhase::Upload, mbps: mbps(self.sent - self.last_bytes, self.last.elapsed().as_secs_f64()),
                fraction: (self.t0.elapsed().as_secs_f64() / UP_SECS).min(1.0) as f32, down: Some(self.down), up: None, ping: Some(self.ping) });
            self.last = Instant::now(); self.last_bytes = self.sent;
        }
        Ok(n)
    }
}

/// Download speed only, for a few seconds: used to rank many servers by real throughput.
pub fn quick_speed(n: &ProxyNode, s: &AppSettings, secs: f64, cancel: &AtomicBool) -> Option<f64> {
    let target = targets(n, s).into_iter().next()?;
    let port = free_ports(1).first().copied()?;
    let mut o = target.outbound; o["tag"] = json!("out");
    let cfg = json!({"log": {"loglevel": "none"}, "inbounds": [{"tag": "in", "protocol": "http", "listen": "127.0.0.1", "port": port}], "outbounds": [bind(o, &physical_interface())]});
    let _proc = spawn(&cfg).ok()?;
    let agent: ureq::Agent = ureq::Agent::config_builder().proxy(ureq::Proxy::new(&format!("http://127.0.0.1:{port}")).ok())
        .timeout_connect(Some(Duration::from_secs(6))).timeout_global(Some(Duration::from_secs(secs as u64 + 10))).http_status_as_error(false).build().into();
    let resp = agent.get(DOWN_URL).call().ok()?;
    if resp.status().as_u16() >= 400 { return None; }
    let mut body = resp.into_body();
    let mut r = body.as_reader();
    let mut buf = vec![0u8; 256 * 1024];
    let (t0, mut total) = (Instant::now(), 0u64);
    loop {
        if cancel.load(Ordering::Relaxed) { return None; }
        let k = r.read(&mut buf).unwrap_or(0);
        if k == 0 { break; }
        total += k as u64;
        if t0.elapsed().as_secs_f64() >= secs { break; }
    }
    let el = t0.elapsed().as_secs_f64();
    (total > 64 * 1024).then(|| mbps(total, el))
}

pub fn speed_test(n: &ProxyNode, s: &AppSettings, cancel: &AtomicBool, cb: &dyn Fn(SpeedProgress)) -> anyhow::Result<SpeedResult> {
    cb(SpeedProgress::default());
    anyhow::ensure!(xray_exe().exists(), "core missing");
    let target = targets(n, s).into_iter().next().ok_or_else(|| anyhow::anyhow!("This server can't be tested through Xray"))?;
    let port = free_ports(1).first().copied().ok_or_else(|| anyhow::anyhow!("no free port"))?;
    let mut o = target.outbound; o["tag"] = json!("out");
    let cfg = json!({"log": {"loglevel": "none"}, "inbounds": [{"tag": "in", "protocol": "http", "listen": "127.0.0.1", "port": port}], "outbounds": [bind(o, &physical_interface())]});
    let _proc = spawn(&cfg)?;
    let agent: ureq::Agent = ureq::Agent::config_builder().proxy(ureq::Proxy::new(&format!("http://127.0.0.1:{port}")).ok())
        .timeout_connect(Some(Duration::from_secs(8))).timeout_global(Some(Duration::from_secs(30))).http_status_as_error(false).build().into();

    cb(SpeedProgress { phase: SpeedPhase::Ping, ..Default::default() });
    timed_get(&agent, &s.ping_url);
    let ping = (0..3).map(|_| timed_get(&agent, &s.ping_url).0).filter(|&m| m >= 0).min().unwrap_or(-1);
    cb(SpeedProgress { phase: SpeedPhase::Ping, ping: Some(ping), ..Default::default() });

    let down = {
        let resp = agent.get(DOWN_URL).call()?;
        let mut body = resp.into_body();
        let mut r = body.as_reader();
        let mut buf = vec![0u8; 256 * 1024];
        let (t0, mut last, mut total, mut last_bytes) = (Instant::now(), Instant::now(), 0u64, 0u64);
        loop {
            if cancel.load(Ordering::Relaxed) { anyhow::bail!("cancelled"); }
            let k = r.read(&mut buf).unwrap_or(0);
            if k == 0 { break; }
            total += k as u64;
            if last.elapsed() > Duration::from_millis(200) {
                cb(SpeedProgress { phase: SpeedPhase::Download, mbps: mbps(total - last_bytes, last.elapsed().as_secs_f64()),
                    fraction: (t0.elapsed().as_secs_f64() / DOWN_SECS).min(1.0) as f32, ping: Some(ping), ..Default::default() });
                last = Instant::now(); last_bytes = total;
            }
            if t0.elapsed().as_secs_f64() >= DOWN_SECS { break; }
        }
        mbps(total, t0.elapsed().as_secs_f64())
    };
    cb(SpeedProgress { phase: SpeedPhase::Download, mbps: down, fraction: 1.0, down: Some(down), ping: Some(ping), ..Default::default() });

    let sent = Arc::new(Mutex::new(0u64));
    let t0 = Instant::now();
    let body = UpBody { sent: 0, t0, last: t0, last_bytes: 0, down, ping, cb, sent_out: sent.clone() };
    let _ = agent.post(UP_URL).header("Content-Type", "application/octet-stream").send(ureq::SendBody::from_reader(&mut { body }));
    let up = mbps(*sent.lock().unwrap(), t0.elapsed().as_secs_f64());
    cb(SpeedProgress { phase: SpeedPhase::Done, down: Some(down), up: Some(up), ping: Some(ping), ..Default::default() });
    Ok(SpeedResult { node_id: n.id.clone(), node_name: n.name.clone(), down_mbps: down, up_mbps: up, ping_ms: ping, at: now_ms() })
}
