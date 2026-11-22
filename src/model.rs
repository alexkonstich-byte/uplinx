//! Data model, stored as state.json.
//! Enums are kept as their JSON strings; unknown fields are preserved via `extra`.

use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};
use std::collections::BTreeMap;

fn new_id() -> String { uuid::Uuid::new_v4().to_string() }
fn s(v: &str) -> String { v.to_string() }

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase", default)]
pub struct ProxyNode {
    pub id: String,
    pub name: String,
    pub protocol: String, // VLESS, VMESS, TROJAN, SHADOWSOCKS, SOCKS, HTTP, HYSTERIA2, WIREGUARD, XRAY_CONFIG
    pub address: String,
    pub port: u16,
    pub uuid: String,
    pub password: String,
    pub username: String,
    pub method: String,
    pub flow: String,
    pub encryption: String,
    pub alter_id: i32,
    pub vmess_security: String,
    pub network: String,
    pub header_type: String,
    pub host: String,
    pub path: String,
    pub service_name: String,
    pub grpc_mode: String,
    pub xhttp_mode: String,
    pub xhttp_extra: String,
    pub security: String,
    pub sni: String,
    pub alpn: String,
    pub fingerprint: String,
    pub public_key: String,
    pub short_id: String,
    pub spider_x: String,
    pub allow_insecure: bool,
    pub pinned_peer_cert_sha256: String,
    pub verify_peer_cert_by_name: String,
    pub ech_config_list: String,
    pub obfs: String,
    pub obfs_password: String,
    pub up_mbps: i32,
    pub down_mbps: i32,
    pub wg_private_key: String,
    pub wg_public_key: String,
    pub wg_pre_shared_key: String,
    pub wg_addresses: String,
    pub wg_reserved: String,
    pub wg_mtu: i32,
    pub wg_dns: String,
    pub wg_allowed_ips: String,
    pub wg_keepalive: i32,
    pub awg: BTreeMap<String, String>,
    pub wg_conf_text: String,
    pub raw_config: String,
    pub balancer_members: Vec<String>,
    pub subscription_id: Option<String>,
    pub raw_link: String,
    pub last_used_at: i64,
    #[serde(flatten)]
    pub extra: Map<String, Value>,
}

impl Default for ProxyNode {
    fn default() -> Self {
        Self {
            id: new_id(), name: String::new(), protocol: s("VLESS"), address: String::new(), port: 443,
            uuid: String::new(), password: String::new(), username: String::new(), method: String::new(), flow: String::new(),
            encryption: s("none"), alter_id: 0, vmess_security: s("auto"), network: s("tcp"), header_type: s("none"),
            host: String::new(), path: String::new(), service_name: String::new(), grpc_mode: s("gun"), xhttp_mode: s("auto"),
            xhttp_extra: String::new(), security: s("none"), sni: String::new(), alpn: String::new(), fingerprint: String::new(),
            public_key: String::new(), short_id: String::new(), spider_x: String::new(), allow_insecure: false,
            pinned_peer_cert_sha256: String::new(), verify_peer_cert_by_name: String::new(), ech_config_list: String::new(),
            obfs: String::new(), obfs_password: String::new(), up_mbps: 0, down_mbps: 0,
            wg_private_key: String::new(), wg_public_key: String::new(), wg_pre_shared_key: String::new(), wg_addresses: String::new(),
            wg_reserved: String::new(), wg_mtu: 1420, wg_dns: String::new(), wg_allowed_ips: s("0.0.0.0/0, ::/0"), wg_keepalive: 0,
            awg: BTreeMap::new(), wg_conf_text: String::new(), raw_config: String::new(), balancer_members: vec![],
            subscription_id: None, raw_link: String::new(), last_used_at: 0, extra: Map::new(),
        }
    }
}

impl ProxyNode {
    pub fn identity(&self) -> String {
        if !self.raw_link.is_empty() { return self.raw_link.clone(); }
        format!("{}|{}|{}|{}{}|{}", self.protocol, self.address, self.port, self.uuid, self.password, self.name)
    }
    pub fn endpoint(&self) -> String {
        if self.address.contains(':') { format!("[{}]:{}", self.address, self.port) } else { format!("{}:{}", self.address, self.port) }
    }
    /// AWG features Xray cannot reproduce (padding, custom headers, I-packets).
    pub fn needs_amnezia_engine(&self) -> bool {
        self.protocol == "WIREGUARD" && self.awg.iter().any(|(k, v)| {
            let key = k.to_uppercase();
            let v = v.trim();
            if key.starts_with('S') { !v.is_empty() && v != "0" }
            else if key.starts_with('H') { v != &key[1..] }
            else if key.starts_with('I') { !v.is_empty() }
            else { false }
        })
    }
    pub fn protocol_title(&self) -> &'static str {
        match self.protocol.as_str() {
            "VLESS" => "VLESS", "VMESS" => "VMess", "TROJAN" => "Trojan", "SHADOWSOCKS" => "Shadowsocks", "SOCKS" => "SOCKS",
            "HTTP" => "HTTP", "HYSTERIA2" => "Hysteria2", "WIREGUARD" => "WireGuard", "XRAY_CONFIG" => "Xray JSON", _ => "?",
        }
    }
    pub fn transport_label(&self) -> String {
        if self.protocol == "WIREGUARD" { return if self.awg.is_empty() { s("WireGuard") } else { s("AmneziaWG") }; }
        let mut l = self.network.to_uppercase();
        if !self.security.is_empty() && self.security != "none" { l.push_str(" · "); l.push_str(&self.security.to_uppercase()); }
        l
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase", default)]
pub struct UsagePoint { pub t: i64, pub used: i64 }
impl Default for UsagePoint { fn default() -> Self { Self { t: 0, used: 0 } } }

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase", default)]
pub struct Subscription {
    pub id: String,
    pub name: String,
    pub url: String,
    pub upload: i64,
    pub download: i64,
    pub total: i64,
    pub expire: i64,
    pub updated_at: i64,
    pub auto_update_hours: i32,
    pub support_url: String,
    pub web_page_url: String,
    pub announce: String,
    pub last_error: String,
    pub pinned: bool,
    pub logo_file: String,
    pub links: BTreeMap<String, String>,
    pub hidden: Vec<String>,
    pub routing_profile: String,
    pub user_agent: String,
    pub usage_history: Vec<UsagePoint>,
    #[serde(flatten)]
    pub extra: Map<String, Value>,
}

impl Default for Subscription {
    fn default() -> Self {
        Self {
            id: new_id(), name: String::new(), url: String::new(), upload: 0, download: 0, total: 0, expire: 0, updated_at: 0,
            auto_update_hours: 12, support_url: String::new(), web_page_url: String::new(), announce: String::new(),
            last_error: String::new(), pinned: false, logo_file: String::new(), links: BTreeMap::new(), hidden: vec![],
            routing_profile: String::new(), user_agent: String::new(), usage_history: vec![], extra: Map::new(),
        }
    }
}

impl Subscription {
    pub fn expired(&self) -> bool { self.expire > 0 && self.expire * 1000 < now_ms() }
    pub fn used_up(&self) -> bool { self.total > 0 && self.upload + self.download >= self.total }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase", default)]
pub struct RouteRule {
    pub id: String,
    pub kind: String,   // DOMAIN, IP, GEOSITE, GEOIP, PROCESS, PORT
    pub value: String,
    pub action: String, // PROXY, DIRECT, BLOCK
    pub enabled: bool,
}
impl Default for RouteRule {
    fn default() -> Self { Self { id: new_id(), kind: s("DOMAIN"), value: String::new(), action: s("PROXY"), enabled: true } }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase", default)]
pub struct RoutingSettings {
    pub mode: String, // RULE, GLOBAL, DIRECT
    pub bypass_lan: bool,
    pub bypass_ru: bool,
    pub block_ads: bool,
    pub proxy_blocked_ru: bool,
    pub domain_strategy: String,
    pub rules: Vec<RouteRule>,
    #[serde(flatten)]
    pub extra: Map<String, Value>,
}
impl Default for RoutingSettings {
    fn default() -> Self {
        Self { mode: s("RULE"), bypass_lan: true, bypass_ru: true, block_ads: true, proxy_blocked_ru: false,
            domain_strategy: s("IPIfNonMatch"), rules: vec![], extra: Map::new() }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase", default)]
pub struct SplitTunnel { pub enabled: bool, pub mode: String, pub apps: Vec<String> }
impl Default for SplitTunnel { fn default() -> Self { Self { enabled: false, mode: s("EXCLUDE"), apps: vec![] } } }

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Default)]
#[serde(rename_all = "camelCase", default)]
pub struct SpeedResult { pub node_id: String, pub node_name: String, pub down_mbps: f64, pub up_mbps: f64, pub ping_ms: i32, pub at: i64 }

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase", default)]
pub struct HomeWidget {
    pub id: String,
    #[serde(rename = "type")]
    pub kind: String,
    pub x: i32, pub y: i32, pub w: i32, pub h: i32,
    pub opts: BTreeMap<String, String>,
}
impl Default for HomeWidget {
    fn default() -> Self { Self { id: new_id(), kind: s("CONNECT"), x: 0, y: 0, w: 6, h: 6, opts: BTreeMap::new() } }
}

pub fn default_home_widgets() -> Vec<HomeWidget> {
    let w = |kind: &str, x, y, w, h| HomeWidget { kind: s(kind), x, y, w, h, ..Default::default() };
    vec![w("CONNECT", 0, 0, 7, 6), w("SERVER", 7, 0, 5, 2), w("SPEED", 7, 2, 5, 3), w("PING", 7, 5, 2, 2),
         w("IP", 9, 5, 3, 2), w("MODES", 0, 6, 7, 2), w("QUOTA", 7, 7, 5, 2)]
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase", default)]
pub struct AppSettings {
    pub theme_mode: String,
    pub color_source: String,
    pub seed_color: i64,
    pub palette_style: String,
    pub amoled: bool,
    pub connect_mode: String, // SYSTEM_PROXY, TUN
    pub socks_port: u16,
    pub http_port: u16,
    pub api_port: u16,
    pub allow_lan: bool,
    pub sniffing: bool,
    pub remote_dns: String,
    pub direct_dns: String,
    pub mux: bool,
    pub log_level: String,
    pub tun_strict_dns: bool,
    pub minimize_to_tray: bool,
    pub auto_connect: bool,
    pub geo_source: String, // XRAY, RUNETFREEDOM
    pub user_agent: String,
    pub ping_url: String,
    pub selected_node_id: Option<String>,
    pub client_spoof: String, // NONE, HAPP, INCY
    pub language: String,
    pub sites: Vec<String>,
    pub usage_down: BTreeMap<String, i64>,
    pub usage_up: BTreeMap<String, i64>,
    pub split: SplitTunnel,
    pub notify_connection: bool,
    pub notify_errors: bool,
    pub notify_subscription: bool,
    pub notify_updates: bool,
    pub sub_warned_day: String,
    pub speed_results: Vec<SpeedResult>,
    pub home_widgets: Vec<HomeWidget>,
    pub last_seen_version: String,
    pub hwid: String,
    pub server_sort: String,
    pub mask_addresses: bool,
    pub ipv6: bool,
    pub show_hidden: bool,
    pub apply_panel_routing: bool,
    pub check_updates: bool,
    pub all_time_up: i64,
    pub all_time_down: i64,
    /// Identities of favourite servers (shown first everywhere).
    pub favorites: Vec<String>,
    /// Server groups folded in the list ("manual" for servers without a subscription).
    pub collapsed_groups: Vec<String>,
    /// Main window: x, y, width, height in physical pixels; empty = centred default.
    pub window_rect: Vec<i32>,
    pub window_maximized: bool,
    pub last_page: i32,
    /// Mini window position (physical pixels).
    pub mini_pos: Vec<i32>,
    pub clipboard_offer: bool,
    pub auto_ping: bool,
    pub auto_reconnect: bool,
    /// System proxy mode: if the core dies, keep the proxy pointing at it so nothing leaks past the VPN.
    pub kill_switch: bool,
    #[serde(flatten)]
    pub extra: Map<String, Value>,
}

impl Default for AppSettings {
    fn default() -> Self {
        Self {
            theme_mode: s("SYSTEM"), color_source: s("WINDOWS_ACCENT"), seed_color: 0xFF1F8BFF, palette_style: s("TonalSpot"), amoled: false,
            connect_mode: s("SYSTEM_PROXY"), socks_port: 10808, http_port: 10809, api_port: 10813, allow_lan: false, sniffing: true,
            remote_dns: s("https://1.1.1.1/dns-query"), direct_dns: s("77.88.8.8"), mux: false, log_level: s("warning"),
            tun_strict_dns: true, minimize_to_tray: true, auto_connect: false, geo_source: s("XRAY"), user_agent: s("v2rayN/7.13"),
            ping_url: s("https://www.gstatic.com/generate_204"), selected_node_id: None, client_spoof: s("NONE"), language: s("en"),
            sites: ["youtube.com", "instagram.com", "chatgpt.com", "x.com", "discord.com"].map(s).to_vec(),
            usage_down: BTreeMap::new(), usage_up: BTreeMap::new(), split: SplitTunnel::default(),
            notify_connection: true, notify_errors: true, notify_subscription: true, notify_updates: true, sub_warned_day: String::new(),
            speed_results: vec![], home_widgets: default_home_widgets(), last_seen_version: String::new(),
            hwid: uuid::Uuid::new_v4().simple().to_string()[..16].to_string(), server_sort: s("MANUAL"), mask_addresses: false, ipv6: true,
            show_hidden: false, apply_panel_routing: true, check_updates: true, all_time_up: 0, all_time_down: 0,
            favorites: vec![], collapsed_groups: vec![], window_rect: vec![], window_maximized: false, last_page: 0, mini_pos: vec![],
            clipboard_offer: true, auto_ping: true, auto_reconnect: true, kill_switch: false, extra: Map::new(),
        }
    }
}

impl AppSettings {
    pub fn effective_user_agent(&self) -> String {
        match self.client_spoof.as_str() { "HAPP" => s("Happ/3.1.0"), "INCY" => s("INCY/2.4.0"), _ => self.user_agent.clone() }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Default)]
#[serde(rename_all = "camelCase", default)]
pub struct AppState {
    pub settings: AppSettings,
    pub routing: RoutingSettings,
    pub subscriptions: Vec<Subscription>,
    pub nodes: Vec<ProxyNode>,
    pub hidden: Vec<String>,
    #[serde(flatten)]
    pub extra: Map<String, Value>,
}

impl AppState {
    pub fn selected_node(&self) -> Option<&ProxyNode> {
        self.settings.selected_node_id.as_ref()
            .and_then(|id| self.nodes.iter().find(|n| &n.id == id))
            .or_else(|| self.nodes.first())
    }
}

pub fn now_ms() -> i64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0)
}
