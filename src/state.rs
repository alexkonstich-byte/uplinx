//! Storage: %APPDATA%\Uplinx\state.json (or $UPLINX_HOME). Atomic writes.

use crate::model::AppState;
use std::path::PathBuf;

pub fn home() -> PathBuf {
    static H: std::sync::OnceLock<PathBuf> = std::sync::OnceLock::new();
    H.get_or_init(|| {
        let h = std::env::var("UPLINX_HOME").map(PathBuf::from).unwrap_or_else(|_| {
            std::env::var("APPDATA").map(PathBuf::from).unwrap_or_else(|_| PathBuf::from(".")).join("Uplinx")
        });
        let _ = std::fs::create_dir_all(h.join("core"));
        h
    }).clone()
}

pub fn core_dir() -> PathBuf { home().join("core") }

pub fn load() -> AppState {
    let path = home().join("state.json");
    match std::fs::read_to_string(&path) {
        Ok(text) => serde_json::from_str(&text).unwrap_or_else(|_| {
            // keep a broken file for the user instead of silently losing it
            let _ = std::fs::copy(&path, home().join("state.broken.json"));
            AppState::default()
        }),
        Err(_) => AppState::default(),
    }
}

pub fn save(st: &AppState) {
    let tmp = home().join("state.json.tmp");
    if let Ok(text) = serde_json::to_string(st) {
        if std::fs::write(&tmp, text).is_ok() { let _ = std::fs::rename(&tmp, home().join("state.json")); }
    }
}
