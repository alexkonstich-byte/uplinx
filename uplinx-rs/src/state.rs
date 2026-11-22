//! Storage: %APPDATA%\Uplinx\state.json, shared format with the Kotlin version. Atomic writes.

use crate::model::AppState;
use std::path::PathBuf;

pub fn home() -> PathBuf {
    let h = std::env::var("UPLINX_HOME").map(PathBuf::from).unwrap_or_else(|_| {
        let base = std::env::var("APPDATA").map(PathBuf::from).unwrap_or_else(|_| PathBuf::from("."));
        let home = base.join("Uplinx");
        let legacy = base.join("MyProxy");
        if !home.exists() && legacy.is_dir() { let _ = std::fs::rename(&legacy, &home); }
        home
    });
    let _ = std::fs::create_dir_all(&h);
    h
}

pub fn core_dir() -> PathBuf { let d = home().join("core"); let _ = std::fs::create_dir_all(&d); d }

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
    if let Ok(text) = serde_json::to_string_pretty(st) {
        if std::fs::write(&tmp, text).is_ok() { let _ = std::fs::rename(&tmp, home().join("state.json")); }
    }
}
