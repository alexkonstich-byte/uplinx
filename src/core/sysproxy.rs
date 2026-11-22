//! Windows system proxy (WinINet). Previous values are backed up to disk before changing anything,
//! so a crash never leaves the user offline: the next start restores them.

use crate::state::home;
use serde::{Deserialize, Serialize};
use winreg::{enums::*, RegKey};

const KEY: &str = "Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings";
const BYPASS: &str = "localhost;127.*;10.*;172.16.*;172.17.*;172.18.*;172.19.*;172.20.*;172.21.*;172.22.*;172.23.*;172.24.*;172.25.*;172.26.*;172.27.*;172.28.*;172.29.*;172.30.*;172.31.*;192.168.*;<local>";

#[derive(Serialize, Deserialize)]
struct Backup { enable: u32, server: Option<String>, #[serde(rename = "override")] over: Option<String>, #[serde(rename = "autoConfigUrl")] pac: Option<String> }

fn backup_file() -> std::path::PathBuf { home().join("proxy-backup.json") }
fn key() -> std::io::Result<RegKey> { RegKey::predef(HKEY_CURRENT_USER).open_subkey_with_flags(KEY, KEY_READ | KEY_WRITE) }

pub fn enabled_by_us() -> bool { backup_file().exists() }

fn refresh() {
    #[link(name = "wininet")]
    unsafe extern "system" { fn InternetSetOptionW(h: *mut core::ffi::c_void, opt: u32, buf: *mut core::ffi::c_void, len: u32) -> i32; }
    unsafe {
        InternetSetOptionW(std::ptr::null_mut(), 39, std::ptr::null_mut(), 0); // SETTINGS_CHANGED
        InternetSetOptionW(std::ptr::null_mut(), 37, std::ptr::null_mut(), 0); // REFRESH
    }
}

pub fn enable(http_port: u16, socks_port: u16) -> anyhow::Result<()> {
    let k = key()?;
    if !backup_file().exists() {
        let b = Backup { enable: k.get_value("ProxyEnable").unwrap_or(0), server: k.get_value("ProxyServer").ok(),
            over: k.get_value("ProxyOverride").ok(), pac: k.get_value("AutoConfigURL").ok() };
        std::fs::write(backup_file(), serde_json::to_string(&b)?)?;
    }
    k.set_value("ProxyServer", &format!("http=127.0.0.1:{http_port};https=127.0.0.1:{http_port};socks=127.0.0.1:{socks_port}"))?;
    k.set_value("ProxyOverride", &BYPASS)?;
    let _ = k.delete_value("AutoConfigURL");
    k.set_value("ProxyEnable", &1u32)?;
    refresh();
    Ok(())
}

/// Puts back whatever the user had before. Safe to call any number of times.
pub fn restore() {
    if !backup_file().exists() { return; }
    if let Ok(k) = key() {
        let b: Option<Backup> = std::fs::read_to_string(backup_file()).ok().and_then(|t| serde_json::from_str(&t).ok());
        let set = |name: &str, v: &Option<String>| match v { Some(x) => { let _ = k.set_value(name, x); } None => { let _ = k.delete_value(name); } };
        match b {
            Some(b) => { let _ = k.set_value("ProxyEnable", &b.enable); set("ProxyServer", &b.server); set("ProxyOverride", &b.over); set("AutoConfigURL", &b.pac); }
            None => { let _ = k.set_value("ProxyEnable", &0u32); }
        }
        refresh();
    }
    let _ = std::fs::remove_file(backup_file());
}
