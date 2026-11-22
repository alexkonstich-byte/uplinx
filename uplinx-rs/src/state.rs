//! Reads the same state.json the Kotlin version writes (%APPDATA%\Uplinx), so the port is a drop-in.

use serde::Deserialize;
use std::path::PathBuf;

#[derive(Debug, Clone, Deserialize, Default)]
#[serde(rename_all = "camelCase", default)]
pub struct Node {
    pub id: String,
    pub name: String,
    pub protocol: String,
    pub address: String,
    pub port: u16,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct Settings {
    pub theme_mode: String,
    pub color_source: String,
    pub seed_color: i64,
    pub selected_node_id: Option<String>,
}

impl Default for Settings {
    fn default() -> Self {
        Self { theme_mode: "SYSTEM".into(), color_source: "WINDOWS_ACCENT".into(), seed_color: 0xFF1F8BFF, selected_node_id: None }
    }
}

#[derive(Debug, Clone, Deserialize, Default)]
#[serde(default)]
pub struct AppState {
    pub settings: Settings,
    pub nodes: Vec<Node>,
}

impl AppState {
    pub fn selected_node(&self) -> Option<&Node> {
        self.settings.selected_node_id.as_ref()
            .and_then(|id| self.nodes.iter().find(|n| &n.id == id))
            .or_else(|| self.nodes.first())
    }
}

pub fn home() -> PathBuf {
    if let Ok(h) = std::env::var("UPLINX_HOME") { return PathBuf::from(h); }
    let base = std::env::var("APPDATA").map(PathBuf::from).unwrap_or_else(|_| PathBuf::from("."));
    base.join("Uplinx")
}

pub fn load() -> AppState {
    std::fs::read_to_string(home().join("state.json"))
        .ok()
        .and_then(|s| serde_json::from_str(&s).ok())
        .unwrap_or_default()
}
