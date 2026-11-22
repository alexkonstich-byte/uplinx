//! AmneziaWG tunnels through the official AmneziaWG service (admin), and the Wintun driver for TUN mode.

use super::{CREATE_NO_WINDOW, Traffic};
use crate::state::{core_dir, home};
use std::os::windows::process::CommandExt;
use std::path::PathBuf;
use std::process::Command;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

pub const TUNNEL: &str = "UplinxAWG";

fn awg_dir() -> PathBuf { PathBuf::from(std::env::var("ProgramFiles").unwrap_or_else(|_| "C:\\Program Files".into())).join("AmneziaWG") }
pub fn installed() -> bool { awg_dir().join("amneziawg.exe").exists() }
fn marker() -> PathBuf { home().join("awg.active") }
pub fn active() -> bool { marker().exists() }

fn cmd(args: &[&str]) -> (i32, String) {
    match Command::new(awg_dir().join("amneziawg.exe")).args(args).creation_flags(CREATE_NO_WINDOW).output() {
        Ok(o) => (o.status.code().unwrap_or(-1), String::from_utf8_lossy(&o.stdout).to_string() + &String::from_utf8_lossy(&o.stderr)),
        Err(e) => (-1, e.to_string()),
    }
}

pub fn start(conf_text: &str) -> anyhow::Result<()> {
    anyhow::ensure!(installed(), crate::t!("AmneziaWG is not installed"));
    let dir = home().join("awg");
    std::fs::create_dir_all(&dir)?;
    let conf = dir.join(format!("{TUNNEL}.conf"));
    std::fs::write(&conf, conf_text)?;
    cmd(&["/uninstalltunnelservice", TUNNEL]); // a stale service from a crash would block the install
    let (code, out) = cmd(&["/installtunnelservice", &conf.to_string_lossy()]);
    if code != 0 { anyhow::bail!(if out.trim().is_empty() { format!("amneziawg.exe exited with code {code}") } else { out }); }
    std::fs::write(marker(), TUNNEL)?;
    Ok(())
}

/// Takes the tunnel down (also one left over from a crash). Safe to call any time.
pub fn stop() {
    if !active() { return; }
    if installed() { cmd(&["/uninstalltunnelservice", TUNNEL]); }
    let _ = std::fs::remove_file(marker());
}

/// Traffic counters from `awg show <tunnel> transfer`, once a second.
pub fn stats_loop(traffic: Arc<Mutex<Traffic>>, stop: Arc<AtomicBool>) {
    std::thread::spawn(move || {
        let (mut last_rx, mut last_tx) = (None::<u64>, 0u64);
        while !stop.load(Ordering::Relaxed) {
            if let Ok(o) = Command::new(awg_dir().join("awg.exe")).args(["show", TUNNEL, "transfer"]).creation_flags(CREATE_NO_WINDOW).output() {
                let text = String::from_utf8_lossy(&o.stdout).to_string();
                let parts: Vec<&str> = text.lines().find(|l| !l.trim().is_empty()).unwrap_or("").split_whitespace().collect();
                if let (Some(rx), Some(tx)) = (parts.get(1).and_then(|x| x.parse::<u64>().ok()), parts.get(2).and_then(|x| x.parse::<u64>().ok())) {
                    if let Some(lr) = last_rx {
                        let (down, up) = (rx.saturating_sub(lr), tx.saturating_sub(last_tx));
                        let mut t = traffic.lock().unwrap();
                        t.up = up; t.down = down; t.total_up = tx; t.total_down = rx;
                        t.history.push((up, down));
                        let n = t.history.len();
                        if n > 60 { t.history.drain(..n - 60); }
                    }
                    last_rx = Some(rx); last_tx = tx;
                }
            }
            std::thread::sleep(Duration::from_secs(1));
        }
    });
}

pub fn wintun_dll() -> PathBuf { core_dir().join("wintun.dll") }

pub fn ensure_wintun() -> anyhow::Result<()> {
    if wintun_dll().exists() { return Ok(()); }
    let data = super::http_agent().get("https://www.wintun.net/builds/wintun-0.14.1.zip").call()?.into_body().with_config().limit(64 << 20).read_to_vec()?;
    let mut ar = zip::ZipArchive::new(std::io::Cursor::new(data))?;
    let arch = if cfg!(target_arch = "aarch64") { "arm64" } else { "amd64" };
    let mut f = ar.by_name(&format!("wintun/bin/{arch}/wintun.dll"))?;
    let mut out = vec![];
    std::io::Read::read_to_end(&mut f, &mut out)?;
    std::fs::write(wintun_dll(), out)?;
    Ok(())
}
