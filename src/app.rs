//! The controller: owns the state, runs connect/disconnect, pings, subscriptions, updates.
//! Plain threads + mutexes; the UI polls a revision counter and redraws only when it moved.

use crate::core::{self, awg, singbox, Engine, errors::{self, Friendly}, ping, probe, subs, sysproxy, update, Core, Status};
use crate::model::*;
use crate::{parse, state, t, win};
use std::collections::{HashMap, HashSet, VecDeque};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering::Relaxed};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

pub const PING_TESTING: i32 = -2;

#[derive(Clone, Debug)]
pub enum Action {
    Reconnect, ErrorDetails, RestartUpdate, UndoRouting(Box<RoutingSettings>), Disconnect,
    // undo restores only what was removed: everything else changed in the meantime stays
    RestoreNode(Box<ProxyNode>, usize),
    Unhide(String),
    RestoreSubscription(Box<Subscription>, Vec<(usize, ProxyNode)>),
    RestoreWidgets(Vec<HomeWidget>),
}

#[derive(Clone, Debug)]
pub struct Msg { pub text: String, pub action: Option<(String, Action)> }

#[derive(Clone, Debug, Default, PartialEq)]
pub struct IpInfo { pub ip: String, pub country: String, pub code: String, pub city: String, pub isp: String }

#[derive(Default)]
pub struct Flags { pub need_admin: AtomicBool, pub need_amnezia: AtomicBool, pub show_error: AtomicBool, pub quit: AtomicBool }

pub struct App {
    pub state: Mutex<AppState>,
    pub core: Arc<Core>,
    pub pings: Mutex<HashMap<String, i32>>,
    pub testing: Mutex<HashSet<String>>,
    pub updating: Mutex<HashSet<String>>,
    pub messages: Mutex<VecDeque<Msg>>,
    pub toasts: Mutex<Vec<(String, String, bool)>>,
    pub last_error: Mutex<Option<Friendly>>,
    pub ip: Mutex<Option<IpInfo>>,
    pub ip_loading: AtomicBool,
    pub ping_history: Mutex<Vec<i32>>,
    pub sites: Mutex<HashMap<String, (i32, u16)>>,
    pub sites_checking: AtomicBool,
    pub speed: Mutex<Option<probe::SpeedProgress>>,
    pub speed_node: Mutex<Option<String>>,
    pub update: Mutex<Option<update::UpdateInfo>>,
    pub update_progress: Mutex<Option<f32>>,
    pub update_ready: Mutex<Option<update::Prepared>>,
    pub whats_new: Mutex<Option<update::UpdateInfo>>,
    pub pending_import: Mutex<Option<(String, String)>>,
    pub flags: Flags,
    core_ver: Mutex<String>,
    singbox_ver: Mutex<String>,
    /// The user wants to be connected (cleared only by Disconnect): drives automatic reconnects.
    want_connected: AtomicBool,
    reconnects: std::sync::atomic::AtomicU32,
    /// Physical adapter at the time a TUN connection was made; a change means a new network.
    tun_iface: Mutex<Option<String>>,
    /// "Measure speed of all": (done, total, current server) while it runs.
    pub speed_all: Mutex<Option<(usize, usize, String)>>,
    speed_all_cancel: Mutex<Option<Arc<AtomicBool>>>,
    pub rev: AtomicU64,
    dirty: AtomicBool,
    running_sig: Mutex<Option<String>>,
    connect_lock: Mutex<()>,
    ping_cancel: Mutex<Arc<AtomicBool>>,
    awg_stats: Mutex<Option<Arc<AtomicBool>>>,
}

fn spawn(f: impl FnOnce() + Send + 'static) { std::thread::spawn(f); }

impl App {
    pub fn new() -> Arc<Self> {
        let st = state::load();
        crate::i18n::apply(&st.settings.language);
        sysproxy::restore(); // a crash while connected: give the user their internet back
        awg::stop();
        let app = Arc::new(Self {
            state: Mutex::new(st), core: Core::new(), pings: Default::default(), testing: Default::default(), updating: Default::default(),
            messages: Default::default(), toasts: Default::default(), last_error: Default::default(), ip: Default::default(),
            ip_loading: AtomicBool::new(false), ping_history: Default::default(), sites: Default::default(), sites_checking: AtomicBool::new(false),
            speed: Default::default(), speed_node: Default::default(), update: Default::default(), update_progress: Default::default(),
            update_ready: Default::default(), whats_new: Default::default(), pending_import: Default::default(), flags: Flags::default(), core_ver: Default::default(), singbox_ver: Default::default(),
            rev: AtomicU64::new(1), dirty: AtomicBool::new(false), running_sig: Default::default(), connect_lock: Mutex::new(()),
            ping_cancel: Mutex::new(Arc::new(AtomicBool::new(false))), awg_stats: Default::default(),
            want_connected: AtomicBool::new(false), reconnects: Default::default(), tun_iface: Default::default(),
            speed_all: Default::default(), speed_all_cancel: Default::default(),
        });
        app.clone().start_background();
        app
    }

    pub fn bump(&self) { self.rev.fetch_add(1, Relaxed); }
    pub fn snapshot(&self) -> AppState { self.state.lock().unwrap().clone() }
    pub fn settings(&self) -> AppSettings { self.state.lock().unwrap().settings.clone() }

    /// Every state change goes through here: saved (debounced) and redrawn.
    pub fn edit(&self, f: impl FnOnce(&mut AppState)) {
        f(&mut self.state.lock().unwrap());
        self.dirty.store(true, Relaxed);
        self.bump();
    }

    pub fn notify(&self, text: impl Into<String>, action: Option<(String, Action)>) {
        let mut q = self.messages.lock().unwrap();
        q.push_back(Msg { text: text.into(), action });
        while q.len() > 4 { q.pop_front(); }
        drop(q);
        self.bump();
    }

    pub fn toast(&self, title: impl Into<String>, text: impl Into<String>, error: bool) {
        self.toasts.lock().unwrap().push((title.into(), text.into(), error));
        self.bump();
    }

    pub fn is_connected(&self) -> bool { self.core.is_running() || (awg::active() && self.running_sig.lock().unwrap().is_some()) }
    pub fn status(&self) -> Status { self.core.status() }
    pub fn selected(&self) -> Option<ProxyNode> { self.state.lock().unwrap().selected_node().cloned() }

    pub fn save_now(&self) {
        if self.dirty.swap(false, Relaxed) { let s = self.snapshot(); let _ = state::save(&s); }
    }

    // ============================================================ background loop

    pub fn core_version(&self) -> String { self.core_ver.lock().unwrap().clone() }
    fn read_core_version(&self) {
        let v = self.core.version().map(|v| format!("v{v}")).unwrap_or_else(|| t!("Not installed"));
        *self.core_ver.lock().unwrap() = v;
        let sb = self.core.singbox_version().map(|v| format!("v{v}")).unwrap_or_else(|| t!("Not installed"));
        *self.singbox_ver.lock().unwrap() = sb;
        self.bump();
    }
    pub fn singbox_version(&self) -> String { self.singbox_ver.lock().unwrap().clone() }

    /// Which engine runs this server and its config. sing-box only when chosen and able to run the server.
    fn plan(node: &ProxyNode, st: &AppState) -> (Engine, String) {
        if st.settings.core == "SINGBOX" && singbox::supports(node) {
            (Engine::SingBox, singbox::build(node, &st.settings, &st.routing))
        } else {
            (Engine::Xray, core::xray::build(node, &st.settings, &st.routing))
        }
    }

    fn start_background(self: Arc<Self>) {
        self.clone().watch_network();
        let me = self.clone();
        spawn(move || me.read_core_version());
        // "What's new" once after an update
        let me = self.clone();
        spawn(move || {
            let seen = me.settings().last_seen_version;
            if seen != update::VERSION {
                if !seen.is_empty() { if let Some(u) = update::notes_for(update::VERSION) { *me.whats_new.lock().unwrap() = Some(u); me.bump(); } }
                me.edit(|s| s.settings.last_seen_version = update::VERSION.into());
            }
        });
        let me = self;
        spawn(move || {
            let start = Instant::now();
            let (mut last_save, mut last_flush, mut last_sub_check, mut last_tunnel_ping, mut last_sites, mut last_update) =
                (Instant::now(), Instant::now(), None::<Instant>, None::<Instant>, None::<Instant>, None::<Instant>);
            let (mut pend_up, mut pend_down) = (0u64, 0u64);
            let mut was_connected = false;
            let mut last_traffic_rev = (0u64, 0u64, 0usize);
            let mut last_tick = Instant::now();
            loop {
                std::thread::sleep(Duration::from_millis(500));
                if me.flags.quit.load(Relaxed) { break; }
                if me.core.check_alive() { me.on_core_died(); }
                if let Status::Running(t0) = me.status() { if now_ms() - t0 > 60_000 { me.reconnects.store(0, Relaxed); } }
                let slept = last_tick.elapsed() > Duration::from_secs(30);
                last_tick = Instant::now();
                if slept && me.is_connected() && me.settings().connect_mode == "TUN" && me.settings().auto_reconnect {
                    me.core.log('I', "Resumed from sleep: reconnecting the TUN tunnel");
                    me.reconnect();
                }
                let connected = me.is_connected();
                if connected != was_connected {
                    was_connected = connected;
                    me.ping_history.lock().unwrap().clear();
                    last_tunnel_ping = connected.then(|| Instant::now() - Duration::from_secs(17));
                    last_sites = connected.then(|| Instant::now() - Duration::from_secs(58));
                    if me.has_widget("IP") { let m = me.clone(); spawn(move || { std::thread::sleep(Duration::from_millis(1500)); m.refresh_ip(); }); }
                    me.bump();
                }
                // live traffic → redraw + all-time usage
                {
                    let t = me.core.traffic.lock().unwrap();
                    let key = (t.total_up, t.total_down, t.history.len());
                    if key != last_traffic_rev {
                        last_traffic_rev = key;
                        pend_up += t.up; pend_down += t.down;
                        drop(t);
                        me.bump();
                    }
                }
                if (last_flush.elapsed() > Duration::from_secs(30) || !connected) && pend_up + pend_down > 0 {
                    let (u, d) = (pend_up as i64, pend_down as i64);
                    pend_up = 0; pend_down = 0; last_flush = Instant::now();
                    let day = today();
                    me.edit(|s| {
                        let st = &mut s.settings;
                        st.all_time_up += u; st.all_time_down += d;
                        *st.usage_up.entry(day.clone()).or_default() += u;
                        *st.usage_down.entry(day.clone()).or_default() += d;
                        for m in [&mut st.usage_up, &mut st.usage_down] { while m.len() > 120 { let k = m.keys().next().cloned().unwrap(); m.remove(&k); } }
                    });
                }
                if last_save.elapsed() > Duration::from_millis(1500) { me.save_now(); last_save = Instant::now(); }
                if connected {
                    if last_tunnel_ping.is_some_and(|t| t.elapsed() > Duration::from_secs(20)) {
                        last_tunnel_ping = Some(Instant::now());
                        let m = me.clone();
                        spawn(move || m.tunnel_ping());
                    }
                    if last_sites.is_some_and(|t| t.elapsed() > Duration::from_secs(60)) {
                        last_sites = Some(Instant::now());
                        if me.has_widget("SITES") { me.check_sites(); }
                    }
                }
                if start.elapsed() > Duration::from_secs(5) && last_sub_check.is_none_or(|t| t.elapsed() > Duration::from_secs(600)) {
                    last_sub_check = Some(Instant::now());
                    me.check_subscription_warnings();
                    let now = now_ms();
                    let due: Vec<String> = me.snapshot().subscriptions.iter()
                        .filter(|s| s.auto_update_hours > 0 && now - s.updated_at > s.auto_update_hours as i64 * 3_600_000).map(|s| s.id.clone()).collect();
                    for id in due { me.update_subscription(&id); }
                }
                if start.elapsed() > Duration::from_secs(8) && last_update.is_none_or(|t| t.elapsed() > Duration::from_secs(6 * 3600)) {
                    last_update = Some(Instant::now());
                    if me.settings().check_updates { let m = me.clone(); spawn(move || m.check_updates(false)); }
                }
            }
        });
    }

    /// TUN routes belong to one network: when Windows switches to another (Wi-Fi ↔ cable, new hotspot), reconnect.
    fn watch_network(self: Arc<Self>) {
        spawn(move || loop {
            #[link(name = "iphlpapi")]
            unsafe extern "system" { fn NotifyAddrChange(handle: *mut std::ffi::c_void, overlapped: *const std::ffi::c_void) -> u32; }
            unsafe { NotifyAddrChange(std::ptr::null_mut(), std::ptr::null()); } // blocks until addresses change
            if self.flags.quit.load(Relaxed) { break; }
            let s = self.settings();
            let Status::Running(t0) = self.status() else { continue };
            if s.connect_mode != "TUN" || !s.auto_reconnect || now_ms() - t0 < 15_000 { continue; } // our own adapter appearing
            std::thread::sleep(Duration::from_secs(3));
            let now = probe::physical_interface_fresh();
            let before = self.tun_iface.lock().unwrap().clone();
            if now.is_some() && now != before && self.is_connected() {
                self.notify(t!("Network changed — reconnecting"), None);
                self.reconnect();
            }
        });
    }

    fn has_widget(&self, kind: &str) -> bool { self.state.lock().unwrap().settings.home_widgets.iter().any(|w| w.kind == kind) }

    fn check_subscription_warnings(&self) {
        let st = self.snapshot();
        let day = today();
        if !st.settings.notify_subscription || st.settings.sub_warned_day == day { return; }
        let mut any = false;
        for s in &st.subscriptions {
            let days = if s.expire > 0 { (s.expire * 1000 - now_ms()) / 86_400_000 } else { i64::MAX };
            if (0..=3).contains(&days) || s.used_up() || s.expired() {
                any = true;
                let text = if s.expired() { t!("Subscription expired") } else if s.used_up() { t!("Out of traffic") } else { t!("The subscription ends in %1 d", days.max(0)) };
                self.toast(&s.name, text, s.expired() || s.used_up());
            }
        }
        if any { self.edit(|s| s.settings.sub_warned_day = day); }
    }

    // ============================================================ connection

    pub fn toggle(self: &Arc<Self>) {
        match self.status() {
            Status::Running(_) => self.disconnect(),
            Status::Stopped | Status::Failed(_) => if self.running_sig.lock().unwrap().is_some() { self.disconnect() } else { self.connect() },
            // the core is still downloading: a second press cancels instead of doing nothing
            Status::Preparing(..) => {
                self.want_connected.store(false, Relaxed);
                self.core.cancel_download();
                self.notify(t!("Connection cancelled"), None);
            }
            Status::Starting => {}
        }
    }

    pub fn connect(self: &Arc<Self>) {
        self.want_connected.store(true, Relaxed);
        self.reconnects.store(0, Relaxed);
        let me = self.clone();
        spawn(move || { let _g = me.connect_lock.lock().unwrap(); me.connect_inner(false); });
    }

    fn connect_inner(&self, retry: bool) {
        let st = self.snapshot();
        let Some(node) = st.selected_node().cloned() else { self.notify(t!("Add a server or a subscription first"), None); return };
        if let Some(sub) = st.subscriptions.iter().find(|s| Some(&s.id) == node.subscription_id.as_ref()) {
            if sub.expired() { self.notify(t!("Subscription “%1” has expired — the server may not work", sub.name), None); }
        }
        *self.last_error.lock().unwrap() = None;
        let tun = st.settings.connect_mode == "TUN";
        if node.needs_amnezia_engine() {
            if !awg::installed() { self.flags.need_amnezia.store(true, Relaxed); self.bump(); return; }
            if !win::is_admin() { self.flags.need_admin.store(true, Relaxed); self.bump(); return; }
            match awg::start(&parse::wg_to_conf(&node)) {
                Ok(()) => {
                    *self.running_sig.lock().unwrap() = Some(format!("awg:{}", node.id));
                    self.core.set_running();
                    let stop = Arc::new(AtomicBool::new(false));
                    awg::stats_loop(self.core.traffic.clone(), stop.clone());
                    *self.awg_stats.lock().unwrap() = Some(stop);
                    self.core.log('I', format!("AmneziaWG: tunnel {} started", awg::TUNNEL));
                    self.after_connect(&node, &st.settings);
                }
                Err(e) => { *self.running_sig.lock().unwrap() = None; self.fail(&e.to_string()); }
            }
            return;
        }
        if tun && !win::is_admin() { self.flags.need_admin.store(true, Relaxed); self.bump(); return; }
        let r = (|| -> anyhow::Result<String> {
            self.bump();
            if !self.core.has_core() { self.core.install(false, st.settings.geo_source == "RUNETFREEDOM")?; self.read_core_version(); }
            else { self.core.install(false, st.settings.geo_source == "RUNETFREEDOM")?; }
            let (mut engine, mut cfg) = Self::plan(&node, &st);
            if st.settings.core == "SINGBOX" && engine == Engine::Xray {
                self.core.log('W', t!("sing-box can't run this server (%1) — using Xray", node.transport_label()));
            }
            if engine == Engine::SingBox {
                if let Err(e) = self.core.install_singbox(false) {
                    self.core.log('W', t!("sing-box couldn't be installed (%1) — using Xray", e));
                    (engine, cfg) = (Engine::Xray, core::xray::build(&node, &st.settings, &st.routing));
                }
            }
            if tun && engine == Engine::Xray { awg::ensure_wintun()?; }
            if let Err(e) = self.core.start_with(engine, &cfg, st.settings.api_port) {
                if engine != Engine::SingBox { return Err(e); }
                // a config sing-box rejects must not leave the user offline: Xray takes over
                self.core.log('W', t!("sing-box didn't start (%1) — using Xray", e.to_string().lines().next().unwrap_or("")));
                if tun { awg::ensure_wintun()?; }
                cfg = core::xray::build(&node, &st.settings, &st.routing);
                self.core.start_with(Engine::Xray, &cfg, st.settings.api_port)?;
            }
            if !tun { sysproxy::enable(st.settings.http_port, st.settings.socks_port)?; }
            Ok(cfg)
        })();
        match r {
            Ok(cfg) => { *self.running_sig.lock().unwrap() = Some(cfg); self.after_connect(&node, &st.settings); }
            Err(e) => {
                *self.running_sig.lock().unwrap() = None;
                // kill switch: while retrying, the proxy keeps pointing at the dead core so nothing goes around the VPN
                if !(retry && st.settings.kill_switch && !tun) { sysproxy::restore(); }
                self.core.stop();
                if e.to_string() == core::CANCELLED { self.core.log('I', t!("Connection cancelled")); }
                else if retry { self.core.log('E', &e.to_string()); } else { self.fail(&e.to_string()); }
            }
        }
        if tun && self.is_connected() { *self.tun_iface.lock().unwrap() = probe::physical_interface_fresh(); }
        self.bump();
    }

    fn after_connect(&self, node: &ProxyNode, s: &AppSettings) {
        let id = node.id.clone();
        self.edit(|st| if let Some(n) = st.nodes.iter_mut().find(|n| n.id == id) { n.last_used_at = now_ms(); });
        let mode = if s.connect_mode == "TUN" { "TUN" } else { "Proxy" };
        self.core.log('I', t!("Connected: %1 (%2)", node.name, mode));
        if s.notify_connection { self.toast(t!("Connected"), format!("{} · {}", node.name, mode), false); }
    }

    fn fail(&self, raw: &str) {
        let fe = errors::explain(raw);
        self.core.log('E', t!("Error: %1\n%2", fe.title, fe.details));
        self.notify(format!("{}. {}", fe.title, fe.hint), Some((t!("Details"), Action::ErrorDetails)));
        if self.settings().notify_errors { self.toast(&fe.title, &fe.hint, true); }
        *self.last_error.lock().unwrap() = Some(fe);
    }

    pub fn disconnect(self: &Arc<Self>) {
        let me = self.clone();
        spawn(move || {
            let _g = me.connect_lock.lock().unwrap();
            me.want_connected.store(false, Relaxed);
            let was = me.is_connected();
            me.stop_all();
            if was && me.settings().notify_connection { me.toast(t!("Disconnected"), t!("Traffic goes directly"), false); }
            me.bump();
        });
    }

    fn stop_all(&self) {
        *self.running_sig.lock().unwrap() = None;
        sysproxy::restore();
        if let Some(f) = self.awg_stats.lock().unwrap().take() { f.store(true, Relaxed); }
        awg::stop();
        self.core.stop();
    }

    pub fn reconnect(self: &Arc<Self>) {
        let me = self.clone();
        spawn(move || { let _g = me.connect_lock.lock().unwrap(); me.stop_all(); me.connect_inner(false); });
    }

    fn on_core_died(self: &Arc<Self>) {
        *self.running_sig.lock().unwrap() = None;
        let s = self.settings();
        let blocked = s.kill_switch && s.connect_mode != "TUN" && sysproxy::enabled_by_us();
        if !blocked { sysproxy::restore(); }
        let msg = match self.status() { Status::Failed(m) => m, _ => String::new() };
        let fe = errors::explain(&msg);
        *self.last_error.lock().unwrap() = Some(fe.clone());
        let attempt = self.reconnects.fetch_add(1, Relaxed);
        if s.auto_reconnect && self.want_connected.load(Relaxed) && attempt < 3 {
            let wait = [2u64, 5, 15][attempt as usize];
            self.notify(t!("Connection lost — reconnecting in %1 s", wait), Some((t!("Disconnect"), Action::Disconnect)));
            let me = self.clone();
            spawn(move || {
                std::thread::sleep(Duration::from_secs(wait));
                if !me.want_connected.load(Relaxed) || me.is_connected() { return; }
                let _g = me.connect_lock.lock().unwrap();
                me.connect_inner(true);
                if !me.is_connected() && me.status() != Status::Stopped { me.on_core_died(); }
            });
            return;
        }
        let text = if blocked { t!("The core stopped: %1. Traffic is blocked until you reconnect or disconnect", fe.title) }
                   else { t!("The core stopped: %1. System proxy turned off", fe.title) };
        self.notify(&text, Some(if blocked { (t!("Disconnect"), Action::Disconnect) } else { (t!("Details"), Action::ErrorDetails) }));
        if s.notify_errors { self.toast(t!("Connection lost"), &text, true); }
    }

    /// Clean exit: proxy restored, core stopped, state saved, a downloaded update applied.
    pub fn shutdown(&self) {
        self.flags.quit.store(true, Relaxed);
        self.stop_all();
        self.dirty.store(true, Relaxed);
        self.save_now();
        if let Some(p) = self.update_ready.lock().unwrap().take() { let _ = update::apply(&p); }
    }

    fn suggest_reconnect(self: &Arc<Self>) {
        if !self.is_connected() { return; }
        let st = self.snapshot();
        let Some(node) = st.selected_node() else { return };
        if node.needs_amnezia_engine() { return; }
        let (engine, cfg) = Self::plan(node, &st);
        let running = *self.core.engine.lock().unwrap();
        if engine != running || Some(cfg) != *self.running_sig.lock().unwrap() {
            self.notify(t!("Settings changed"), Some((t!("Reconnect"), Action::Reconnect)));
        }
    }

    pub fn run_action(self: &Arc<Self>, a: Action) {
        match a {
            Action::RestoreNode(n, at) => self.edit(|st| if !st.nodes.iter().any(|x| x.id == n.id) { let at = at.min(st.nodes.len()); st.nodes.insert(at, *n); }),
            Action::Unhide(key) => self.edit(|st| st.hidden.retain(|x| *x != key)),
            Action::RestoreSubscription(sub, nodes) => self.edit(|st| {
                if st.subscriptions.iter().any(|x| x.id == sub.id) { return; }
                st.subscriptions.push(*sub);
                for (at, n) in nodes { let at = at.min(st.nodes.len()); st.nodes.insert(at, n); }
            }),
            Action::RestoreWidgets(w) => self.edit(|st| st.settings.home_widgets = w),
            Action::UndoRouting(r) => self.update_routing(|x| *x = *r),
            Action::Reconnect => self.reconnect(),
            Action::ErrorDetails => { self.flags.show_error.store(true, Relaxed); self.bump(); }
            Action::RestartUpdate => self.install_update(),
            Action::Disconnect => self.disconnect(),
        }
    }

    // ============================================================ settings / routing / nodes

    pub fn update_settings(self: &Arc<Self>, f: impl FnOnce(&mut AppSettings)) {
        let before = self.settings();
        self.edit(|s| f(&mut s.settings));
        let after = self.settings();
        let key = |a: &AppSettings| ((a.connect_mode.clone(), a.socks_port, a.http_port, a.api_port, a.allow_lan, a.sniffing, a.remote_dns.clone()),
            (a.direct_dns.clone(), a.mux, a.log_level.clone(), a.tun_strict_dns, a.geo_source.clone(), a.ipv6, a.split.clone()));
        if key(&before) != key(&after) { self.suggest_reconnect(); }
        if before.language != after.language { crate::i18n::apply(&after.language); }
    }

    pub fn update_routing(self: &Arc<Self>, f: impl FnOnce(&mut RoutingSettings)) {
        self.edit(|s| f(&mut s.routing));
        self.suggest_reconnect();
    }

    pub fn set_connect_mode(self: &Arc<Self>, mode: &str) {
        if self.settings().connect_mode == mode { return; }
        self.edit(|s| s.settings.connect_mode = mode.into());
        if self.is_connected() { self.reconnect(); }
    }

    pub fn set_routing_mode(self: &Arc<Self>, mode: &str) {
        if self.state.lock().unwrap().routing.mode == mode { return; }
        self.edit(|s| s.routing.mode = mode.into());
        if self.is_connected() { self.reconnect(); }
    }

    pub fn select(self: &Arc<Self>, id: &str) {
        let changed = self.settings().selected_node_id.as_deref() != Some(id);
        self.edit(|s| s.settings.selected_node_id = Some(id.into()));
        if changed && self.is_connected() { self.reconnect(); }
    }

    pub fn upsert_node(self: &Arc<Self>, n: ProxyNode) {
        let id = n.id.clone();
        self.edit(|s| match s.nodes.iter_mut().find(|x| x.id == n.id) { Some(x) => *x = n, None => s.nodes.push(n) });
        if self.is_connected() && self.selected().is_some_and(|x| x.id == id) { self.suggest_reconnect(); }
    }

    pub fn delete_node(&self, id: &str) {
        let before = self.snapshot();
        let Some(at) = before.nodes.iter().position(|n| n.id == id) else { return };
        let n = before.nodes[at].clone();
        self.edit(|s| s.nodes.retain(|x| x.id != id));
        self.notify(t!("“%1” deleted", n.name), Some((t!("Undo"), Action::RestoreNode(Box::new(n), at))));
    }

    pub fn set_hidden(&self, id: &str, hidden: bool) {
        let Some(n) = self.snapshot().nodes.into_iter().find(|n| n.id == id) else { return };
        let key = n.identity();
        self.edit(|s| { s.hidden.retain(|x| *x != key); if hidden { s.hidden.push(key.clone()); } });
        if hidden { self.notify(t!("“%1” hidden", n.name), Some((t!("Undo"), Action::Unhide(key)))); }
    }

    pub fn copy_link(&self, id: &str) {
        if let Some(n) = self.snapshot().nodes.iter().find(|n| n.id == id) {
            let text = if n.protocol == "WIREGUARD" { parse::wg_to_conf(n) } else if n.protocol == "XRAY_CONFIG" { n.raw_config.clone() } else { parse::links::to_link(n) };
            win::set_clipboard(&text);
            self.notify(t!("Copied"), None);
        }
    }

    // ============================================================ import

    fn deep_link_inner(t: &str) -> Option<String> {
        let (scheme, rest) = t.split_once("://")?;
        if !scheme.chars().all(|c| c.is_ascii_alphanumeric()) || scheme.starts_with("vless") || scheme.starts_with("vmess") { return None; }
        let (verb, payload) = rest.split_once('/')?;
        if !["add", "import", "sub", "install-config"].contains(&verb.to_lowercase().as_str()) || payload.is_empty() { return None; }
        Some(parse::links::url_decode(payload))
    }

    /// Imports anything; returns how many things were added.
    pub fn import_text(self: &Arc<Self>, text: &str, name_hint: Option<&str>) -> usize {
        let t = text.trim();
        if t.is_empty() { return 0; }
        if let Some(inner) = Self::deep_link_inner(t) { return self.import_text(&inner, name_hint); }
        if (t.starts_with("http://") || t.starts_with("https://")) && !t.contains('\n') && !t.contains('@') {
            self.add_subscription("", t);
            return 1;
        }
        self.apply_import(parse::import(t, name_hint))
    }

    fn apply_import(self: &Arc<Self>, mut r: parse::ImportResult) -> usize {
        let mut count = 0;
        // the same link pasted again must not make a second copy of the server
        let known: HashSet<String> = self.snapshot().nodes.iter().filter(|n| n.subscription_id.is_none()).map(|n| n.identity()).collect();
        let before = r.nodes.len();
        let mut seen = HashSet::new();
        r.nodes.retain(|n| { let k = n.identity(); !known.contains(&k) && seen.insert(k) });
        let dupes = before - r.nodes.len();
        if dupes > 0 && r.nodes.is_empty() && r.nested.is_empty() && r.rules.is_empty() && r.routing_profile.is_none() {
            self.notify(if dupes == 1 { t!("This server is already added") } else { t!("These servers are already added") }, None);
            return dupes;
        }
        if !r.nodes.is_empty() {
            let first = r.nodes[0].id.clone();
            let n = r.nodes.len();
            self.edit(|s| { s.nodes.extend(r.nodes.iter().cloned()); if s.settings.selected_node_id.is_none() { s.settings.selected_node_id = Some(first); } });
            count += n;
            if self.settings().auto_ping { self.ping_all(Some(r.nodes.iter().map(|n| n.id.clone()).collect())); }
        }
        for u in &r.nested { self.add_subscription("", u); count += 1; }
        if !r.rules.is_empty() { let rules = r.rules.clone(); self.update_routing(|x| x.rules.extend(rules)); count += r.rules.len(); }
        if let Some(p) = &r.routing_profile {
            let before = self.snapshot().routing;
            if let Some(nr) = parse::happ_routing_apply(p, &before) {
                self.update_routing(|x| *x = nr);
                self.notify(t!("Routing profile applied"), Some((t!("Undo"), Action::UndoRouting(Box::new(before)))));
                count += 1;
            }
        }
        let mut parts = vec![];
        if !r.nodes.is_empty() { parts.push(t!("servers: %1", r.nodes.len())); }
        if !r.nested.is_empty() { parts.push(t!("subscriptions: %1", r.nested.len())); }
        if !r.rules.is_empty() { parts.push(t!("rules: %1", r.rules.len())); }
        if !parts.is_empty() { self.notify(t!("Added — ") + &parts.join(", "), None); }
        count
    }

    /// Imports from outside the window (deep links, second instance) wait for the user's OK.
    pub fn request_import(&self, text: &str) {
        let t = text.trim();
        if t.is_empty() { return; }
        let inner = Self::deep_link_inner(t).unwrap_or_else(|| t.to_string());
        let summary = if inner.starts_with("http") && !inner.contains('@') { t!("Subscription: %1", inner) } else {
            let r = parse::import(&inner, None);
            t!("Servers: %1", r.nodes.len()) + &if r.routing_profile.is_some() { t!(", routing profile") } else { String::new() }
        };
        *self.pending_import.lock().unwrap() = Some((inner, summary));
        self.bump();
    }

    pub fn import_clipboard(self: &Arc<Self>) {
        if let Some(text) = win::clipboard_text() { if self.import_text(&text, None) > 0 { return; } }
        let codes = crate::qr::from_clipboard();
        if !codes.is_empty() { for c in codes { self.import_text(&c, None); } return; }
        self.notify(t!("No links, configs or QR code in the clipboard"), None);
    }

    pub fn import_files(self: &Arc<Self>, files: Vec<std::path::PathBuf>) {
        for f in files {
            let name = f.file_name().map(|x| x.to_string_lossy().to_string()).unwrap_or_default();
            if crate::qr::is_image(&f) {
                let codes = crate::qr::from_file(&f);
                if codes.is_empty() { self.notify(t!("%1: no QR code found", name), None); }
                for c in codes { self.import_text(&c, None); }
                continue;
            }
            let Ok(text) = std::fs::read_to_string(&f) else { continue };
            let stem = f.file_stem().map(|x| x.to_string_lossy().to_string());
            if self.import_text(&text, stem.as_deref()) == 0 { self.notify(t!("%1: format not recognised", name), None); }
        }
    }

    pub fn scan_screen_qr(self: &Arc<Self>) {
        let me = self.clone();
        spawn(move || {
            let codes = crate::qr::from_screen();
            if codes.is_empty() { me.notify(t!("No QR codes found on the screen. Open the QR and try again"), None); }
            for c in codes { me.import_text(&c, None); }
        });
    }

    // ============================================================ ping

    pub fn ping_all(self: &Arc<Self>, ids: Option<Vec<String>>) { self.ping_run(ids); }

    fn ping_run(self: &Arc<Self>, ids: Option<Vec<String>>) -> Option<std::thread::JoinHandle<()>> {
        let st = self.snapshot();
        let nodes: Vec<ProxyNode> = st.nodes.iter().filter(|n| match &ids {
            Some(ids) => ids.contains(&n.id),
            None => st.settings.show_hidden || !st.hidden.contains(&n.identity()),
        }).cloned().collect();
        if nodes.is_empty() { return None; }
        let cancel = Arc::new(AtomicBool::new(false));
        if ids.is_none() { let mut c = self.ping_cancel.lock().unwrap(); c.store(true, Relaxed); *c = cancel.clone(); }
        let prev = self.pings.lock().unwrap().clone();
        let mut ordered = nodes;
        ordered.sort_by_key(|n| prev.get(&n.id).map(|&p| if p >= 0 { p } else { i32::MAX - 1 }).unwrap_or(i32::MAX - 2));
        self.testing.lock().unwrap().extend(ordered.iter().map(|n| n.id.clone()));
        self.bump();
        let me = self.clone();
        Some(std::thread::spawn(move || {
            if !me.core.has_core() {
                if let Err(e) = me.core.install(false, st.settings.geo_source == "RUNETFREEDOM") { me.notify(t!("Couldn't download the core for accurate ping: %1", e), None); }
            }
            let (via_core, via_tcp): (Vec<ProxyNode>, Vec<ProxyNode>) = if me.core.has_core() { ordered.into_iter().partition(|n| !n.needs_amnezia_engine()) } else { (vec![], ordered) };
            let tcp_me = me.clone();
            let tcp = std::thread::spawn(move || {
                if via_tcp.is_empty() { return; }
                let faked = ping::handshakes_faked();
                if faked { tcp_me.notify(t!("Another VPN in TUN mode fakes TCP connections — a simple ping is unreliable. Turn it off or use ping through the core"), None); }
                let next = std::sync::atomic::AtomicUsize::new(0);
                std::thread::scope(|sc| for _ in 0..25.min(via_tcp.len()) {
                    sc.spawn(|| loop {
                        let i = next.fetch_add(1, Relaxed);
                        let Some(n) = via_tcp.get(i) else { break };
                        tcp_me.finish(&n.id, if faked { ping::PING_UNRELIABLE } else { ping::tcp(n, 3000) });
                    });
                });
            });
            let mut targets = vec![];
            let pending: Mutex<HashMap<String, (usize, i32)>> = Mutex::new(HashMap::new());
            for n in &via_core {
                let t = probe::targets(n, &st.settings);
                if t.is_empty() { me.finish(&n.id, -1); continue; }
                pending.lock().unwrap().insert(n.id.clone(), (t.len(), i32::MIN));
                targets.extend(t);
            }
            probe::probe(targets, &st.settings.ping_url, &cancel, &|key, ms| {
                let id = key.split('#').next().unwrap_or(key);
                let mut p = pending.lock().unwrap();
                let Some(e) = p.get_mut(id) else { return };
                e.0 -= 1;
                e.1 = if e.1 == i32::MIN || e.1 < 0 || (ms >= 0 && ms < e.1) { ms } else { e.1 }; // balancer: best member
                let (left, best) = *e;
                drop(p);
                if left == 0 { me.finish(id, best); } else if key.contains('#') { me.pings.lock().unwrap().insert(id.into(), best); me.bump(); }
            });
            for (id, _) in pending.into_inner().unwrap() { me.testing.lock().unwrap().remove(&id); }
            let _ = tcp.join();
            me.bump();
        }))
    }

    /// Picks the server with the lowest ping (testing them first if nothing is known) and connects to it.
    pub fn connect_fastest(self: &Arc<Self>) {
        let me = self.clone();
        spawn(move || {
            let st = me.snapshot();
            let visible: Vec<String> = st.nodes.iter().filter(|n| st.settings.show_hidden || !st.hidden.contains(&n.identity())).map(|n| n.id.clone()).collect();
            if visible.is_empty() { me.notify(t!("Add a server or a subscription first"), None); return; }
            let known = { let p = me.pings.lock().unwrap(); visible.iter().any(|id| p.get(id).is_some_and(|&x| x >= 0)) };
            if !known { if let Some(h) = me.ping_run(Some(visible.clone())) { let _ = h.join(); } }
            let best = { let p = me.pings.lock().unwrap(); visible.iter().filter_map(|id| p.get(id).filter(|&&x| x >= 0).map(|&x| (x, id.clone()))).min() };
            let Some((ms, id)) = best else { me.notify(t!("No server answered"), None); return };
            let name = st.nodes.iter().find(|n| n.id == id).map(|n| n.name.clone()).unwrap_or_default();
            me.notify(t!("Fastest: %1 (%2 ms)", name, ms), None);
            let changed = st.settings.selected_node_id.as_deref() != Some(&id);
            me.edit(|s| s.settings.selected_node_id = Some(id.clone()));
            if me.is_connected() { if changed { me.reconnect(); } } else { me.connect(); }
        });
    }

    /// Measures the real download speed of every visible server, one after another (a few seconds each).
    pub fn speed_all(self: &Arc<Self>) {
        if self.speed_all.lock().unwrap().is_some() { return; }
        let st = self.snapshot();
        let pings = self.pings.lock().unwrap().clone();
        let mut nodes: Vec<ProxyNode> = st.nodes.iter()
            .filter(|n| !st.hidden.contains(&n.identity()) && !n.needs_amnezia_engine() && pings.get(&n.id).is_none_or(|&p| p >= 0))
            .cloned().collect();
        nodes.sort_by_key(|n| pings.get(&n.id).copied().unwrap_or(i32::MAX));
        if nodes.is_empty() { self.notify(t!("Add a server or a subscription first"), None); return; }
        let cancel = Arc::new(AtomicBool::new(false));
        *self.speed_all_cancel.lock().unwrap() = Some(cancel.clone());
        *self.speed_all.lock().unwrap() = Some((0, nodes.len(), String::new()));
        self.bump();
        let me = self.clone();
        spawn(move || {
            if !me.core.has_core() { let _ = me.core.install(false, st.settings.geo_source == "RUNETFREEDOM"); }
            let mut measured = 0;
            for (i, n) in nodes.iter().enumerate() {
                if cancel.load(Relaxed) { break; }
                *me.speed_all.lock().unwrap() = Some((i, nodes.len(), crate::view::split_flag(&n.name).1));
                me.bump();
                if let Some(v) = probe::quick_speed(n, &st.settings, 4.0, &cancel) {
                    measured += 1;
                    let key = n.identity();
                    me.edit(|s| { s.settings.speeds.insert(key, v); });
                }
            }
            *me.speed_all.lock().unwrap() = None;
            *me.speed_all_cancel.lock().unwrap() = None;
            me.notify(t!("Speed measured: %1 of %2 servers", measured, nodes.len()), None);
            me.bump();
        });
    }

    pub fn speed_all_stop(&self) {
        if let Some(c) = self.speed_all_cancel.lock().unwrap().as_ref() { c.store(true, Relaxed); }
    }

    pub fn toggle_favorite(&self, id: &str) {
        let Some(n) = self.snapshot().nodes.into_iter().find(|n| n.id == id) else { return };
        let key = n.identity();
        self.edit(|s| { let f = &mut s.settings.favorites; if let Some(i) = f.iter().position(|x| *x == key) { f.remove(i); } else { f.push(key); } });
    }

    pub fn toggle_group(&self, gid: &str) {
        let g = gid.to_string();
        self.edit(|s| { let c = &mut s.settings.collapsed_groups; if let Some(i) = c.iter().position(|x| *x == g) { c.remove(i); } else { c.push(g); } });
    }

    /// A short description when the clipboard holds something new to import, None otherwise.
    pub fn clipboard_offer(&self, text: &str) -> Option<String> {
        let t = text.trim();
        if t.is_empty() || t.len() > 256 * 1024 { return None; }
        let inner = Self::deep_link_inner(t).unwrap_or_else(|| t.to_string());
        let st = self.snapshot();
        if (inner.starts_with("http://") || inner.starts_with("https://")) && !inner.contains('\n') && !inner.contains('@') {
            if st.subscriptions.iter().any(|s| s.url == inner) { return None; }
            let host = inner.split("://").nth(1).unwrap_or("").split('/').next().unwrap_or("");
            return Some(t!("Subscription: %1", host));
        }
        let r = parse::import(&inner, None);
        let known: HashSet<String> = st.nodes.iter().map(|n| n.identity()).collect();
        let fresh: Vec<&ProxyNode> = r.nodes.iter().filter(|n| !known.contains(&n.identity())).collect();
        if fresh.len() == 1 { return Some(crate::view::split_flag(&fresh[0].name).1); }
        if fresh.len() > 1 { return Some(t!("Servers: %1", fresh.len())); }
        if !r.nested.iter().all(|u| st.subscriptions.iter().any(|s| &s.url == u)) { return Some(t!("Subscription: %1", r.nested[0])); }
        None
    }

    pub fn route_check(&self, input: &str) -> core::route::Verdict {
        let st = self.snapshot();
        core::route::check(input, &st.settings, &st.routing)
    }

    fn finish(&self, id: &str, ms: i32) {
        self.pings.lock().unwrap().insert(id.into(), ms);
        self.testing.lock().unwrap().remove(id);
        self.bump();
    }

    pub fn ping_current(self: &Arc<Self>) {
        let Some(n) = self.selected() else { return };
        if !self.is_connected() { self.ping_all(Some(vec![n.id])); return; }
        self.testing.lock().unwrap().insert(n.id.clone());
        let me = self.clone();
        spawn(move || { let s = me.settings(); me.finish(&n.id, probe::through_proxy(s.http_port, &s.ping_url)); });
    }

    fn proxied(&self) -> Option<u16> { let s = self.settings(); (self.core.is_running() && s.connect_mode != "TUN").then_some(s.http_port) }

    fn tunnel_ping(&self) {
        let url = self.settings().ping_url;
        let ms = match self.proxied() { Some(p) => probe::through_proxy(p, &url), None => probe::measure(&probe::direct_agent(Duration::from_secs(8)), &url) };
        let mut h = self.ping_history.lock().unwrap();
        h.push(ms);
        let n = h.len();
        if n > 60 { h.drain(..n - 60); }
        drop(h);
        if ms >= 0 { if let Some(sel) = self.selected() { self.pings.lock().unwrap().insert(sel.id, ms); } }
        self.bump();
    }

    // ============================================================ IP, sites, speed

    pub fn refresh_ip(&self) {
        if self.ip_loading.swap(true, Relaxed) { return; }
        self.bump();
        let agent = match self.proxied() { Some(p) => probe::proxied_agent(p, Duration::from_secs(10)), None => probe::direct_agent(Duration::from_secs(10)) };
        let r = agent.get("https://ipwho.is/").header("User-Agent", "Uplinx").call().ok().and_then(|r| r.into_body().read_to_string().ok())
            .and_then(|b| serde_json::from_str::<serde_json::Value>(&b).ok())
            .map(|o| IpInfo { ip: s(&o["ip"]), country: s(&o["country"]), code: s(&o["country_code"]), city: s(&o["city"]), isp: s(&o["connection"]["isp"]) });
        *self.ip.lock().unwrap() = r;
        self.ip_loading.store(false, Relaxed);
        self.bump();
    }

    pub fn check_sites(self: &Arc<Self>) {
        if self.sites_checking.swap(true, Relaxed) { return; }
        self.bump();
        let me = self.clone();
        spawn(move || {
            let agent = match me.proxied() { Some(p) => probe::proxied_agent(p, Duration::from_secs(8)), None => probe::direct_agent(Duration::from_secs(8)) };
            let sites = me.settings().sites;
            std::thread::scope(|sc| for site in &sites {
                let (agent, me) = (&agent, &me);
                sc.spawn(move || {
                    let url = if site.contains("://") { site.clone() } else { format!("https://{site}") };
                    let r = probe::timed_get(agent, &url);
                    me.sites.lock().unwrap().insert(site.clone(), r);
                    me.bump();
                });
            });
            me.sites_checking.store(false, Relaxed);
            me.bump();
        });
    }

    pub fn set_sites(&self, list: Vec<String>) {
        let mut v: Vec<String> = list.into_iter().map(|x| x.trim().to_string()).filter(|x| !x.is_empty()).collect();
        v.dedup();
        self.edit(|s| s.settings.sites = v);
    }

    pub fn run_speed_test(self: &Arc<Self>, node: Option<ProxyNode>) {
        let Some(n) = node.or_else(|| self.selected()) else { self.notify(t!("Add a server or a subscription first"), None); return };
        { let mut g = self.speed_node.lock().unwrap(); if g.is_some() { return; } *g = Some(n.id.clone()); }
        let me = self.clone();
        spawn(move || {
            let st = me.settings();
            let r = (|| { if !me.core.has_core() { me.core.install(false, st.geo_source == "RUNETFREEDOM")?; }
                probe::speed_test(&n, &st, &AtomicBool::new(false), &|p| { *me.speed.lock().unwrap() = Some(p); me.bump(); }) })();
            match r {
                Ok(res) => {
                    if res.ping_ms >= 0 { me.pings.lock().unwrap().insert(n.id.clone(), res.ping_ms); }
                    let (key, down) = (n.identity(), res.down_mbps);
                    me.edit(|s| { s.settings.speeds.insert(key, down); });
                    me.edit(|s| { s.settings.speed_results.push(res); let k = s.settings.speed_results.len(); if k > 30 { s.settings.speed_results.drain(..k - 30); } });
                }
                Err(e) => { *me.speed.lock().unwrap() = None; me.notify(t!("Speed test failed: %1", errors::explain(&e.to_string()).title), None); }
            }
            *me.speed_node.lock().unwrap() = None;
            me.bump();
        });
    }

    // ============================================================ subscriptions

    pub fn add_subscription(self: &Arc<Self>, name: &str, url: &str) {
        let url = url.trim().to_string();
        if self.snapshot().subscriptions.iter().any(|s| s.url == url) { self.notify(t!("This subscription is already added"), None); return; }
        let sub = Subscription { name: if name.trim().is_empty() { t!("New subscription") } else { name.trim().into() }, url, ..Default::default() };
        let id = sub.id.clone();
        self.edit(|s| s.subscriptions.push(sub));
        self.update_subscription(&id);
    }

    pub fn edit_subscription(&self, sub: Subscription) { self.edit(|s| if let Some(x) = s.subscriptions.iter_mut().find(|x| x.id == sub.id) { *x = sub; }); }

    pub fn delete_subscription(&self, id: &str) {
        let before = self.snapshot();
        let Some(sub) = before.subscriptions.iter().find(|s| s.id == id).cloned() else { return };
        let nodes: Vec<(usize, ProxyNode)> = before.nodes.iter().enumerate().filter(|(_, n)| n.subscription_id.as_deref() == Some(id)).map(|(i, n)| (i, n.clone())).collect();
        self.edit(|s| { s.subscriptions.retain(|x| x.id != id); s.nodes.retain(|n| n.subscription_id.as_deref() != Some(id)); });
        let name = sub.name.clone();
        self.notify(t!("Subscription “%1” deleted", name), Some((t!("Undo"), Action::RestoreSubscription(Box::new(sub), nodes))));
    }

    pub fn update_all_subscriptions(self: &Arc<Self>) { for s in self.snapshot().subscriptions { self.update_subscription(&s.id); } }

    pub fn update_subscription(self: &Arc<Self>, id: &str) {
        let Some(sub) = self.snapshot().subscriptions.into_iter().find(|s| s.id == id) else { return };
        if !self.updating.lock().unwrap().insert(id.into()) { return; }
        self.bump();
        let me = self.clone();
        let id = id.to_string();
        spawn(move || {
            let st = me.settings();
            match subs::fetch(&sub, &st.effective_user_agent(), &st.hwid) {
                Ok(res) => {
                    if !res.subscription.user_agent.is_empty() && res.subscription.user_agent != sub.user_agent {
                        me.notify(t!("“%1” didn't accept the current client — switched to: %2", res.subscription.name, res.subscription.user_agent.split('/').next().unwrap_or("")), None);
                    }
                    let selected = me.selected();
                    let mut new_sub = res.subscription.clone();
                    new_sub.usage_history = sub.usage_history.clone();
                    new_sub.usage_history.push(UsagePoint { t: now_ms(), used: new_sub.upload + new_sub.download });
                    let k = new_sub.usage_history.len();
                    if k > 90 { new_sub.usage_history.drain(..k - 90); }
                    me.edit(|s| {
                        let old: Vec<ProxyNode> = s.nodes.iter().filter(|n| n.subscription_id.as_deref() == Some(&id)).cloned().collect();
                        let merged: Vec<ProxyNode> = res.nodes.iter().cloned().map(|mut n| {
                            if let Some(o) = old.iter().find(|o| o.identity() == n.identity()) { n.id = o.id.clone(); n.last_used_at = o.last_used_at; }
                            n
                        }).collect();
                        let first = s.nodes.iter().position(|n| n.subscription_id.as_deref() == Some(&id)).unwrap_or(s.nodes.len());
                        s.nodes.retain(|n| n.subscription_id.as_deref() != Some(&id));
                        let at = first.min(s.nodes.len());
                        s.nodes.splice(at..at, merged.iter().cloned());
                        if let Some(sel) = &selected {
                            if sel.subscription_id.as_deref() == Some(&id) && !merged.iter().any(|m| m.id == sel.id) {
                                s.settings.selected_node_id = merged.iter().find(|m| m.name == sel.name).or(merged.first()).map(|m| m.id.clone());
                            }
                        } else if s.settings.selected_node_id.is_none() { s.settings.selected_node_id = merged.first().map(|m| m.id.clone()); }
                        if let Some(x) = s.subscriptions.iter_mut().find(|x| x.id == id) { *x = new_sub; }
                    });
                    if st.apply_panel_routing {
                        let p = &res.subscription.routing_profile;
                        if !p.is_empty() && *p != sub.routing_profile {
                            if let Some(nr) = parse::happ_routing_apply(p, &me.snapshot().routing) {
                                me.update_routing(|x| *x = nr);
                                me.notify(t!("“%1”: routing profile from the service applied", res.subscription.name), None);
                            }
                        }
                        if !res.rules.is_empty() {
                            let prefix = format!("panel-{id}-");
                            let rules: Vec<RouteRule> = res.rules.iter().enumerate().map(|(i, r)| RouteRule { id: format!("{prefix}{i}"), ..r.clone() }).collect();
                            me.update_routing(|x| { x.rules.retain(|r| !r.id.starts_with(&prefix)); let mut v = rules; v.append(&mut x.rules); x.rules = v; });
                        }
                    }
                    if st.auto_ping {
                        let ids: Vec<String> = me.snapshot().nodes.iter().filter(|n| n.subscription_id.as_deref() == Some(&id)).map(|n| n.id.clone()).collect();
                        me.ping_all(Some(ids));
                    }
                    if selected.is_some_and(|s| s.subscription_id.as_deref() == Some(&id)) { me.suggest_reconnect(); }
                }
                Err(e) => {
                    let msg = e.to_string();
                    me.edit(|s| if let Some(x) = s.subscriptions.iter_mut().find(|x| x.id == id) { x.last_error = msg.clone(); });
                    me.notify(format!("“{}”: {msg}", sub.name), None);
                }
            }
            me.updating.lock().unwrap().remove(&id);
            me.bump();
        });
    }

    // ============================================================ rules

    pub fn upsert_rule(self: &Arc<Self>, rule: RouteRule) {
        self.update_routing(|r| match r.rules.iter_mut().find(|x| x.id == rule.id) { Some(x) => *x = rule, None => r.rules.push(rule) });
    }
    pub fn delete_rule(self: &Arc<Self>, id: &str) { self.update_routing(|r| r.rules.retain(|x| x.id != id)); }
    pub fn move_rule(self: &Arc<Self>, id: &str, delta: i32) {
        self.update_routing(|r| {
            let Some(i) = r.rules.iter().position(|x| x.id == id) else { return };
            let j = i as i32 + delta;
            if j >= 0 && (j as usize) < r.rules.len() { let x = r.rules.remove(i); r.rules.insert(j as usize, x); }
        });
    }

    // ============================================================ core & app updates

    pub fn update_singbox(self: &Arc<Self>) {
        let me = self.clone();
        spawn(move || match me.core.install_singbox(true) {
            Ok(()) => { me.read_core_version(); me.notify(t!("sing-box updated: %1", me.singbox_version()), None) }
            Err(e) => me.notify(t!("Update failed: %1", errors::explain(&e.to_string()).title), None),
        });
    }

    pub fn update_core(self: &Arc<Self>) {
        if self.is_connected() { self.notify(t!("Disconnect first"), None); return; }
        let me = self.clone();
        spawn(move || match me.core.install(true, me.settings().geo_source == "RUNETFREEDOM") {
            Ok(()) => { me.read_core_version(); me.notify(t!("Core updated: %1", me.core_version()), None) }
            Err(e) => me.notify(t!("Update failed: %1", errors::explain(&e.to_string()).title), None),
        });
    }

    pub fn update_geo(self: &Arc<Self>) {
        let me = self.clone();
        spawn(move || match me.core.update_geo(me.settings().geo_source == "RUNETFREEDOM") {
            Ok(()) => { me.notify(t!("Geo databases updated"), None); me.suggest_reconnect(); }
            Err(e) => me.notify(t!("Error: %1", e), None),
        });
    }

    pub fn check_updates(&self, manual: bool) {
        match update::check() {
            Ok(u) => {
                if let Some(u) = &u {
                    let known = self.update.lock().unwrap().as_ref().map(|x| x.version.clone());
                    if known.as_deref() != Some(&u.version) && self.settings().notify_updates { self.toast(t!("Version %1 is available", u.version), t!("Open Uplinx to update"), false); }
                } else if manual { self.notify(t!("You have the latest version %1", update::VERSION), None); }
                *self.update.lock().unwrap() = u;
            }
            Err(e) => if manual { self.notify(t!("Couldn't check for updates: %1", e), None); },
        }
        self.bump();
    }

    fn download_update(&self) -> bool {
        let Some(u) = self.update.lock().unwrap().clone() else { return false };
        if self.update_ready.lock().unwrap().as_ref().is_some_and(|p| p.version == u.version) { return true; }
        if u.msi.is_none() && u.zip.is_none() { win::open_url(&u.url); return false; }
        *self.update_progress.lock().unwrap() = Some(0.0);
        self.bump();
        let r = update::prepare(&u, &|f| { *self.update_progress.lock().unwrap() = Some(f); self.bump(); });
        *self.update_progress.lock().unwrap() = None;
        self.bump();
        match r {
            Ok(p) => { *self.update_ready.lock().unwrap() = Some(p); true }
            Err(e) => { self.notify(t!("Update download failed: %1", e), None); false }
        }
    }

    /// Download (if needed), then quit into the installer; the app starts again afterwards.
    pub fn install_update(self: &Arc<Self>) {
        let me = self.clone();
        spawn(move || if me.download_update() { me.shutdown(); std::process::exit(0); });
    }

    pub fn update_in_background(self: &Arc<Self>) {
        let me = self.clone();
        spawn(move || if me.download_update() {
            let v = me.update_ready.lock().unwrap().as_ref().map(|p| p.version.clone()).unwrap_or_default();
            me.toast(t!("Update %1 is ready", v), t!("It installs when Uplinx restarts"), false);
            me.notify(t!("Update %1 is ready", v), Some((t!("Restart"), Action::RestartUpdate)));
        });
    }
}

fn s(v: &serde_json::Value) -> String { v.as_str().unwrap_or("").to_string() }

/// Local date as YYYY-MM-DD.
pub fn today() -> String {
    #[repr(C)] struct St { y: u16, m: u16, dow: u16, d: u16, h: u16, mi: u16, s: u16, ms: u16 }
    #[link(name = "kernel32")] unsafe extern "system" { fn GetLocalTime(st: *mut St); }
    let mut st = St { y: 0, m: 0, dow: 0, d: 0, h: 0, mi: 0, s: 0, ms: 0 };
    unsafe { GetLocalTime(&mut st) };
    format!("{:04}-{:02}-{:02}", st.y, st.m, st.d)
}
