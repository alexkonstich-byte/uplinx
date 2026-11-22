//! Xray core lifecycle: install/update, run hidden, logs, traffic stats. Plain threads — no async runtime.

pub mod awg;
pub mod errors;
pub mod ping;
pub mod probe;
pub mod route;
pub mod subs;
pub mod sysproxy;
pub mod update;
pub mod xray;

use crate::state::{core_dir, home};
use std::io::{BufRead, BufReader, Read};
use std::os::windows::process::CommandExt;
use std::path::PathBuf;
use std::process::{Child, Command, Stdio};
use std::sync::{Arc, Mutex};
use std::time::Duration;

pub(crate) const CREATE_NO_WINDOW: u32 = 0x0800_0000;

#[derive(Clone, Debug, PartialEq)]
pub enum Status { Stopped, Preparing(String, Option<f32>), Starting, Running(i64), Failed(String) }

#[derive(Clone, Debug, Default)]
pub struct Traffic { pub up: u64, pub down: u64, pub total_up: u64, pub total_down: u64, pub history: Vec<(u64, u64)> }

#[derive(Clone, Debug)]
pub struct LogLine { pub time: i64, pub level: char, pub text: String }

pub struct Core {
    child: Mutex<Option<Child>>,
    pub logs: Mutex<Vec<LogLine>>,
    pub traffic: Arc<Mutex<Traffic>>,
    pub status: Mutex<Status>,
    stats_stop: Mutex<Option<Arc<std::sync::atomic::AtomicBool>>>,
}

pub fn xray_exe() -> PathBuf { core_dir().join("xray.exe") }
fn pid_file() -> PathBuf { home().join("xray.pid") }

pub fn http_agent() -> ureq::Agent {
    ureq::Agent::config_builder().timeout_global(Some(Duration::from_secs(60))).user_agent("Uplinx").build().into()
}

impl Core {
    pub fn new() -> Arc<Self> {
        let c = Arc::new(Self { child: Mutex::new(None), logs: Mutex::new(vec![]), traffic: Arc::new(Mutex::new(Traffic::default())),
            status: Mutex::new(Status::Stopped), stats_stop: Mutex::new(None) });
        c.kill_orphan();
        c
    }

    pub fn log(&self, level: char, text: impl Into<String>) {
        let mut l = self.logs.lock().unwrap();
        l.push(LogLine { time: crate::model::now_ms(), level, text: text.into() });
        let n = l.len();
        if n > 2000 { l.drain(..n - 2000); }
    }

    /// For engines other than Xray (AmneziaWG): show as connected.
    pub fn set_running(&self) { *self.traffic.lock().unwrap() = Traffic::default(); self.set_status(Status::Running(crate::model::now_ms())); }
    pub fn clear_logs(&self) { self.logs.lock().unwrap().clear(); }

    pub fn status(&self) -> Status { self.status.lock().unwrap().clone() }
    fn set_status(&self, s: Status) { *self.status.lock().unwrap() = s; }
    pub fn has_core(&self) -> bool { xray_exe().exists() }
    pub fn is_running(&self) -> bool { matches!(self.status(), Status::Running(_)) }

    fn download(&self, url: &str, label: &str) -> anyhow::Result<Vec<u8>> {
        let resp = http_agent().get(url).call()?;
        let total: Option<u64> = resp.headers().get("content-length").and_then(|v| v.to_str().ok()).and_then(|v| v.parse().ok());
        let mut reader = resp.into_body().into_reader();
        let mut buf = vec![0u8; 64 * 1024];
        let mut out = Vec::with_capacity(total.unwrap_or(1 << 20) as usize);
        loop {
            let n = reader.read(&mut buf)?;
            if n == 0 { break; }
            out.extend_from_slice(&buf[..n]);
            self.set_status(Status::Preparing(label.into(), total.map(|t| out.len() as f32 / t as f32)));
        }
        Ok(out)
    }

    /// Downloads Xray (+ geo files) on first connect or on request.
    pub fn install(&self, force: bool, geo_runetfreedom: bool) -> anyhow::Result<()> {
        let prev = self.status();
        let r = (|| -> anyhow::Result<()> {
            if force || !self.has_core() {
                anyhow::ensure!(!self.is_running(), "Disconnect before updating the core");
                let zip = self.download("https://github.com/XTLS/Xray-core/releases/latest/download/Xray-windows-64.zip", "Xray core")?;
                let mut ar = zip::ZipArchive::new(std::io::Cursor::new(zip))?;
                for i in 0..ar.len() {
                    let mut f = ar.by_index(i)?;
                    let name = f.name().rsplit('/').next().unwrap_or("").to_string();
                    let wanted = name == "xray.exe" || (name.ends_with(".dat") && (!geo_runetfreedom || !core_dir().join(&name).exists()));
                    if !wanted { continue; }
                    let mut data = vec![];
                    f.read_to_end(&mut data)?;
                    std::fs::write(core_dir().join(&name), data)?;
                }
                self.log('I', format!("Xray installed to {}", core_dir().display()));
            }
            let marker = core_dir().join(".geo-runetfreedom");
            if force || (geo_runetfreedom && !marker.exists()) { self.update_geo(geo_runetfreedom)?; }
            Ok(())
        })();
        if matches!(self.status(), Status::Preparing(..)) { self.set_status(if matches!(prev, Status::Preparing(..)) { Status::Stopped } else { prev }); }
        r
    }

    pub fn update_geo(&self, runetfreedom: bool) -> anyhow::Result<()> {
        let base = if runetfreedom { "https://github.com/runetfreedom/russia-v2ray-rules-dat/releases/latest/download" }
                   else { "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download" };
        for f in ["geoip.dat", "geosite.dat"] {
            let data = self.download(&format!("{base}/{f}"), f)?;
            std::fs::write(core_dir().join(f), data)?;
        }
        let marker = core_dir().join(".geo-runetfreedom");
        if runetfreedom { let _ = std::fs::write(marker, "1"); } else { let _ = std::fs::remove_file(marker); }
        self.log('I', "Geo databases updated");
        Ok(())
    }

    pub fn version(&self) -> Option<String> {
        if !self.has_core() { return None; }
        let out = Command::new(xray_exe()).arg("version").creation_flags(CREATE_NO_WINDOW).output().ok()?;
        String::from_utf8_lossy(&out.stdout).split_whitespace().nth(1).map(str::to_string)
    }

    fn xray(&self, args: &[&str]) -> Command {
        let mut c = Command::new(xray_exe());
        c.args(args).current_dir(core_dir()).env("XRAY_LOCATION_ASSET", core_dir()).creation_flags(CREATE_NO_WINDOW);
        c
    }

    /// `xray run -test`: validates without starting anything. Returns the error text.
    pub fn test_config(&self, path: &std::path::Path) -> Option<String> {
        let out = self.xray(&["run", "-test", "-c", &path.to_string_lossy()]).output().ok()?;
        if out.status.success() { None } else {
            let t = String::from_utf8_lossy(&out.stdout).to_string() + &String::from_utf8_lossy(&out.stderr);
            Some(t.lines().filter(|l| !l.trim().is_empty()).rev().take(4).collect::<Vec<_>>().into_iter().rev().collect::<Vec<_>>().join("\n"))
        }
    }

    pub fn start(self: &Arc<Self>, config: &str, api_port: u16) -> anyhow::Result<()> {
        anyhow::ensure!(self.child.lock().unwrap().is_none(), "The core is already running");
        self.set_status(Status::Starting);
        let cfg_path = home().join("run-config.json");
        std::fs::write(&cfg_path, config)?;
        if let Some(err) = self.test_config(&cfg_path) {
            self.log('E', &err);
            self.set_status(Status::Failed(err.clone()));
            anyhow::bail!(err);
        }
        let mut child = self.xray(&["run", "-c", &cfg_path.to_string_lossy()]).stdout(Stdio::piped()).stderr(Stdio::piped()).spawn()?;
        let _ = std::fs::write(pid_file(), child.id().to_string());
        for pipe in [child.stdout.take().map(|p| Box::new(p) as Box<dyn Read + Send>), child.stderr.take().map(|p| Box::new(p) as Box<dyn Read + Send>)].into_iter().flatten() {
            let me = self.clone();
            std::thread::spawn(move || {
                for line in BufReader::new(pipe).lines().map_while(Result::ok) {
                    let lvl = if line.contains("[Error]") { 'E' } else if line.contains("[Warning]") { 'W' } else if line.contains("[Debug]") { 'D' } else { 'I' };
                    let text = line.splitn(3, ' ').nth(2).filter(|_| line.chars().take(4).all(|c| c.is_ascii_digit())).unwrap_or(&line).to_string();
                    me.log(lvl, text);
                }
            });
        }
        *self.child.lock().unwrap() = Some(child);
        std::thread::sleep(Duration::from_millis(1200));
        let exited = self.child.lock().unwrap().as_mut().and_then(|c| c.try_wait().ok().flatten());
        if let Some(code) = exited {
            *self.child.lock().unwrap() = None;
            let _ = std::fs::remove_file(pid_file());
            let tail = self.logs.lock().unwrap().iter().rev().take(3).map(|l| l.text.clone()).collect::<Vec<_>>().join("\n");
            let err = if tail.is_empty() { format!("Xray exited with code {code}") } else { tail };
            self.set_status(Status::Failed(err.clone()));
            anyhow::bail!(err);
        }
        self.set_status(Status::Running(crate::model::now_ms()));
        *self.traffic.lock().unwrap() = Traffic::default();
        self.start_stats(api_port);
        Ok(())
    }

    pub fn stop(&self) {
        if let Some(flag) = self.stats_stop.lock().unwrap().take() { flag.store(true, std::sync::atomic::Ordering::Relaxed); }
        if let Some(mut c) = self.child.lock().unwrap().take() { let _ = c.kill(); let _ = c.wait(); }
        let _ = std::fs::remove_file(pid_file());
        *self.traffic.lock().unwrap() = Traffic::default();
        self.set_status(Status::Stopped);
    }

    /// Detects a core that died on its own; returns true once when that happens.
    pub fn check_alive(&self) -> bool {
        let mut g = self.child.lock().unwrap();
        if let Some(c) = g.as_mut() {
            if let Ok(Some(code)) = c.try_wait() {
                *g = None;
                drop(g);
                let _ = std::fs::remove_file(pid_file());
                self.set_status(Status::Failed(format!("Xray exited unexpectedly (code {code})")));
                return true;
            }
        }
        false
    }

    fn kill_orphan(&self) {
        let Ok(pid) = std::fs::read_to_string(pid_file()) else { return };
        let pid = pid.trim();
        // only an xray.exe with that pid
        let _ = Command::new("taskkill").args(["/F", "/FI", &format!("PID eq {pid}"), "/FI", "IMAGENAME eq xray.exe"]).creation_flags(CREATE_NO_WINDOW).output();
        let _ = std::fs::remove_file(pid_file());
    }

    fn start_stats(self: &Arc<Self>, api_port: u16) {
        let stop = Arc::new(std::sync::atomic::AtomicBool::new(false));
        *self.stats_stop.lock().unwrap() = Some(stop.clone());
        let me = self.clone();
        std::thread::spawn(move || {
            while !stop.load(std::sync::atomic::Ordering::Relaxed) {
                let t0 = std::time::Instant::now();
                if let Ok(out) = me.xray(&["api", "statsquery", &format!("--server=127.0.0.1:{api_port}"), "-pattern", "outbound>>>", "-reset"]).output() {
                    if let Ok(v) = serde_json::from_slice::<serde_json::Value>(&out.stdout) {
                        let (mut up, mut down) = (0u64, 0u64);
                        for e in v["stat"].as_array().into_iter().flatten() {
                            let name = e["name"].as_str().unwrap_or("");
                            let tag = name.split(">>>").nth(1).unwrap_or("");
                            if ["direct", "block", "dns-out", "api"].contains(&tag) || tag.starts_with("direct") || tag.starts_with("block") { continue; }
                            let val = e["value"].as_u64().or_else(|| e["value"].as_str().and_then(|s| s.parse().ok())).unwrap_or(0);
                            if name.ends_with("uplink") { up += val } else if name.ends_with("downlink") { down += val }
                        }
                        let mut t = me.traffic.lock().unwrap();
                        t.up = up; t.down = down; t.total_up += up; t.total_down += down;
                        t.history.push((up, down));
                        let n = t.history.len();
                        if n > 60 { t.history.drain(..n - 60); }
                    }
                }
                std::thread::sleep(Duration::from_millis(1000).saturating_sub(t0.elapsed()).max(Duration::from_millis(100)));
            }
        });
    }
}

