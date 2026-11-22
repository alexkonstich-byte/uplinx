//! Updates from GitHub Releases. Installed copies get a quiet in-place MSI upgrade;
//! portable copies (portable.txt next to Uplinx.exe) swap files after exit.

use super::CREATE_NO_WINDOW;
use crate::state::home;
use serde_json::Value;
use std::io::Read;
use std::os::windows::process::CommandExt;
use std::path::PathBuf;
use std::process::Command;
use std::time::Duration;

pub const REPO: &str = "alexkonstich-byte/uplinx";
pub const VERSION: &str = env!("CARGO_PKG_VERSION");

#[derive(Clone, Debug, PartialEq)]
pub struct UpdateInfo { pub version: String, pub notes: String, pub url: String, pub exe: Option<String>, pub zip: Option<String>, pub msi: Option<String> }

#[derive(Clone, Debug, PartialEq)]
pub struct Prepared { pub version: String, pub script: PathBuf }

fn parts(v: &str) -> Vec<u64> { v.trim_start_matches('v').split(['.', '-']).filter_map(|x| x.parse().ok()).collect() }

pub fn newer(a: &str, b: &str) -> bool {
    let (x, y) = (parts(a), parts(b));
    for i in 0..x.len().max(y.len()) {
        let (p, q) = (x.get(i).copied().unwrap_or(0), y.get(i).copied().unwrap_or(0));
        if p != q { return p > q; }
    }
    false
}

pub fn app_dir() -> Option<PathBuf> {
    let exe = std::env::current_exe().ok()?;
    let name = exe.file_name()?.to_string_lossy().to_lowercase();
    if name == "uplinx.exe" { exe.parent().map(|p| p.to_path_buf()) } else { None }
}
pub fn is_portable() -> bool { app_dir().is_some_and(|d| d.join("portable.txt").exists()) }

fn agent() -> ureq::Agent { ureq::Agent::config_builder().timeout_connect(Some(Duration::from_secs(15))).timeout_global(Some(Duration::from_secs(600))).build().into() }

fn api(path: &str) -> anyhow::Result<Value> {
    let r = agent().get(&format!("https://api.github.com/repos/{REPO}/{path}"))
        .header("Accept", "application/vnd.github+json").header("User-Agent", "Uplinx-updater").call()?;
    Ok(serde_json::from_str(&r.into_body().read_to_string()?)?)
}

fn info(o: &Value) -> UpdateInfo {
    let assets: Vec<&Value> = o["assets"].as_array().map(|a| a.iter().collect()).unwrap_or_default();
    let asset = |f: &dyn Fn(&str) -> bool| assets.iter().find(|a| f(a["name"].as_str().unwrap_or(""))).and_then(|a| a["browser_download_url"].as_str()).map(str::to_string);
    UpdateInfo {
        version: o["tag_name"].as_str().unwrap_or("").trim_start_matches('v').into(),
        notes: o["body"].as_str().unwrap_or("").into(),
        url: o["html_url"].as_str().unwrap_or("").into(),
        exe: asset(&|n| n.ends_with(".exe")), zip: asset(&|n| n.ends_with(".zip") && n.contains("portable")), msi: asset(&|n| n.ends_with(".msi")),
    }
}

pub fn check() -> anyhow::Result<Option<UpdateInfo>> {
    let u = info(&api("releases/latest")?);
    Ok(Some(u).filter(|u| newer(&u.version, VERSION)))
}

pub fn notes_for(version: &str) -> Option<UpdateInfo> { api(&format!("releases/tags/v{version}")).ok().map(|o| info(&o)) }

fn download(url: &str, target: &std::path::Path, progress: &dyn Fn(f32)) -> anyhow::Result<()> {
    let r = agent().get(url).header("User-Agent", "Uplinx-updater").call()?;
    let total: u64 = r.headers().get("content-length").and_then(|v| v.to_str().ok()).and_then(|v| v.parse().ok()).unwrap_or(0);
    let mut body = r.into_body();
    let mut rd = body.with_config().limit(u64::MAX).reader();
    let mut f = std::fs::File::create(target)?;
    let mut buf = vec![0u8; 64 * 1024];
    let mut done = 0u64;
    loop {
        let n = rd.read(&mut buf)?;
        if n == 0 { break; }
        std::io::Write::write_all(&mut f, &buf[..n])?;
        done += n as u64;
        if total > 0 { progress(done as f32 / total as f32); }
    }
    Ok(())
}

/// Downloads (and for portable copies unpacks) the update. Nothing is applied yet.
pub fn prepare(u: &UpdateInfo, progress: &dyn Fn(f32)) -> anyhow::Result<Prepared> {
    let work = home().join("update");
    let _ = std::fs::remove_dir_all(&work);
    std::fs::create_dir_all(&work)?;
    let pid = std::process::id();
    let script = work.join("apply.ps1");
    if let (true, Some(dir), Some(zip_url)) = (is_portable(), app_dir(), u.zip.as_ref()) {
        let zip = work.join("update.zip");
        download(zip_url, &zip, progress)?;
        let new = work.join("new");
        zip::ZipArchive::new(std::fs::File::open(&zip)?)?.extract(&new)?; // extract() rejects paths escaping the folder
        let _ = std::fs::remove_file(&zip);
        let entries: Vec<_> = std::fs::read_dir(&new)?.flatten().collect();
        let src = if entries.len() == 1 && entries[0].path().is_dir() { entries[0].path() } else { new };
        std::fs::write(&script, [
            "$ErrorActionPreference = 'SilentlyContinue'".to_string(),
            format!("Wait-Process -Id {pid} -Timeout 120"),
            format!("robocopy \"{}\" \"{}\" /MIR /R:3 /W:1 /NFL /NDL /NJH /NJS | Out-Null", src.display(), dir.display()),
            format!("New-Item -ItemType File -Force \"{}\" | Out-Null", dir.join("portable.txt").display()),
            format!("Start-Process \"{}\"", dir.join("Uplinx.exe").display()),
        ].join("\r\n"))?;
        return Ok(Prepared { version: u.version.clone(), script });
    }
    let msi_url = u.msi.as_ref().ok_or_else(|| anyhow::anyhow!(crate::t!("The release has no installer")))?;
    let msi = work.join(format!("Uplinx-{}.msi", u.version));
    download(msi_url, &msi, progress)?;
    let exe = PathBuf::from(std::env::var("LOCALAPPDATA").unwrap_or_default()).join("Uplinx").join("Uplinx.exe");
    std::fs::write(&script, [
        "$ErrorActionPreference = 'SilentlyContinue'".to_string(),
        format!("Wait-Process -Id {pid} -Timeout 120"),
        format!("Start-Process msiexec.exe -ArgumentList '/i', '\"{}\"', '/passive', '/norestart', '/l*v', '\"{}\"' -Wait", msi.display(), work.join("install.log").display()),
        format!("Start-Process \"{}\"", exe.display()),
    ].join("\r\n"))?;
    Ok(Prepared { version: u.version.clone(), script })
}

/// Starts applying; the caller exits right after.
pub fn apply(p: &Prepared) -> anyhow::Result<()> {
    Command::new("powershell").args(["-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File", &p.script.to_string_lossy()])
        .creation_flags(CREATE_NO_WINDOW).spawn()?;
    Ok(())
}
