//! App state → Slint models. Runs on the UI thread when the controller's revision moved.

use crate::app::{App, PING_TESTING};
use crate::core::{probe::SpeedPhase, Status};
use crate::model::*;
use crate::{t, AppItem, AppWindow, Bar, FieldItem, LogItem, NodeItem, Routing, Settings, SiteItem, SpeedRow, SubItem, RuleItem, Ui, WidgetItem};
use slint::{ComponentHandle, Image, ModelRc, SharedString, VecModel};
use std::collections::HashMap;
use std::rc::Rc;
use std::sync::Arc;

include!(concat!(env!("OUT_DIR"), "/flags.rs"));

pub const PING_UNKNOWN: i32 = -100;

#[derive(Default)]
pub struct View {
    pub search: String,
    pub log_filter: i32,
    pub log_search: String,
    pub logs_paused: bool,
    flags: HashMap<String, Option<Image>>,
    logos: HashMap<String, Option<Image>>,
    icons: HashMap<String, Option<Image>>,
    sig_nodes: u64,
    sig_subs: u64,
    sig_rules: u64,
    sig_widgets: u64,
    sig_logs: (usize, i32, i64),
    sig_settings: u64,
    pub editing: Option<String>,
}

fn hash<T: std::hash::Hash>(v: &T) -> u64 { use std::hash::Hasher; let mut d = std::hash::DefaultHasher::new(); v.hash(&mut d); d.finish() }
fn ss(s: impl AsRef<str>) -> SharedString { s.as_ref().into() }
fn model<T: Clone + 'static>(v: Vec<T>) -> ModelRc<T> { ModelRc::from(Rc::new(VecModel::from(v))) }

/// Twemoji id ("1f1f3-1f1f1") of the first flag in a name, and the name without it.
pub fn split_flag(name: &str) -> (Option<String>, String) {
    let cps: Vec<(usize, char)> = name.char_indices().collect();
    for w in cps.windows(2) {
        let (a, b) = (w[0].1 as u32, w[1].1 as u32);
        if (0x1F1E6..=0x1F1FF).contains(&a) && (0x1F1E6..=0x1F1FF).contains(&b) {
            let end = w[1].0 + w[1].1.len_utf8();
            let rest = format!("{}{}", &name[..w[0].0], &name[end..]);
            let rest = rest.trim().trim_start_matches(['·', '-', '|', ' ']).trim().to_string();
            return (Some(format!("{a:x}-{b:x}")), if rest.is_empty() { name.to_string() } else { rest });
        }
    }
    (None, name.to_string())
}

pub fn flag_for_code(code: &str) -> Option<String> {
    let c: Vec<char> = code.to_uppercase().chars().collect();
    if c.len() != 2 || !c.iter().all(|x| x.is_ascii_uppercase()) { return None; }
    Some(format!("{:x}-{:x}", 0x1F1E6 + (c[0] as u32 - 'A' as u32), 0x1F1E6 + (c[1] as u32 - 'A' as u32)))
}

impl View {
    pub fn flag(&mut self, id: &str) -> Option<Image> {
        self.flags.entry(id.to_string()).or_insert_with(|| {
            let data = FLAGS.iter().find(|(k, _)| *k == id)?.1;
            let img = image::load_from_memory_with_format(data, image::ImageFormat::Png).ok()?.to_rgba8();
            let buf = slint::SharedPixelBuffer::<slint::Rgba8Pixel>::clone_from_slice(img.as_raw(), img.width(), img.height());
            Some(Image::from_rgba8(buf))
        }).clone()
    }

    /// A program's icon by exe name, from the path seen when it was running.
    pub fn app_icon(&mut self, name: &str, paths: &std::collections::BTreeMap<String, String>) -> Option<Image> {
        let path = paths.get(&name.to_lowercase())?.clone();
        self.icons.entry(path.clone()).or_insert_with(|| {
            let (px, size) = crate::win::file_icon(&path)?;
            Some(Image::from_rgba8(slint::SharedPixelBuffer::clone_from_slice(&px, size, size)))
        }).clone()
    }

    pub fn app_item(&mut self, name: &str, paths: &std::collections::BTreeMap<String, String>) -> AppItem {
        let icon = self.app_icon(name, paths);
        AppItem { name: ss(name), has_icon: icon.is_some(), icon: icon.unwrap_or_default() }
    }

    fn logo(&mut self, path: &str) -> Option<Image> {
        if path.is_empty() { return None; }
        self.logos.entry(path.to_string()).or_insert_with(|| {
            if path.ends_with(".svg") { return None; }
            let img = image::open(path).ok()?.thumbnail(96, 96).to_rgba8();
            let buf = slint::SharedPixelBuffer::<slint::Rgba8Pixel>::clone_from_slice(img.as_raw(), img.width(), img.height());
            Some(Image::from_rgba8(buf))
        }).clone()
    }

    fn node_item(&mut self, n: &ProxyNode, st: &AppState, pings: &HashMap<String, i32>, testing: bool, sel: &str) -> NodeItem {
        let (fid, name) = split_flag(&n.name);
        let flag = fid.and_then(|f| self.flag(&f));
        let detail = if st.settings.mask_addresses { format!("{} · {}", n.protocol_title(), n.transport_label()) }
            else { format!("{} · {} · {}", n.endpoint(), n.protocol_title(), n.transport_label()) };
        NodeItem {
            id: ss(&n.id), header: false, name: ss(name), detail: ss(detail.trim_end_matches(" · ")),
            has_flag: flag.is_some(), flag: flag.unwrap_or_default(),
            glyph: ss(if n.protocol == "XRAY_CONFIG" { "\u{e9ee}" } else if n.protocol == "WIREGUARD" { "\u{e897}" } else { "\u{e80b}" }),
            ping: if testing { PING_TESTING } else { pings.get(&n.id).copied().unwrap_or(PING_UNKNOWN) }, testing,
            selected: n.id == sel, hidden: st.hidden.contains(&n.identity()), count: 0,
            favorite: st.settings.favorites.contains(&n.identity()), collapsed: false,
            speed: ss(st.settings.speeds.get(&n.identity()).map(|v| format!("{v:.0} Mbps")).unwrap_or_default()),
        }
    }

    pub fn refresh(&mut self, w: &AppWindow, app: &Arc<App>) {
        let st = app.snapshot();
        let ui = w.global::<Ui>();
        let status = app.status();
        let pings = app.pings.lock().unwrap().clone();
        let testing = app.testing.lock().unwrap().clone();
        let sel = st.selected_node().cloned();
        let sel_id = sel.as_ref().map(|n| n.id.clone()).unwrap_or_default();

        // ---- connection
        let (code, text) = match &status {
            Status::Running(_) => (2, t!("Connected")),
            Status::Preparing(..) | Status::Starting => (1, t!("Connecting…")),
            Status::Failed(_) => (3, t!("Connection failed")),
            Status::Stopped => (0, t!("Not connected")),
        };
        ui.set_status(code);
        ui.set_status_text(ss(text));
        match &status {
            Status::Preparing(label, p) => { ui.set_preparing(true); ui.set_prep_text(ss(t!("Downloading %1…", label))); ui.set_prep_progress(p.unwrap_or(-1.0)); }
            Status::Starting => { ui.set_preparing(true); ui.set_prep_text(ss(t!("Starting…"))); ui.set_prep_progress(-1.0); }
            _ => ui.set_preparing(false),
        }
        match &sel {
            Some(n) => {
                let (fid, name) = split_flag(&n.name);
                let flag = fid.and_then(|f| self.flag(&f));
                ui.set_server_name(ss(name));
                ui.set_server_detail(ss(if st.settings.mask_addresses { n.protocol_title().to_string() } else { format!("{} · {}", n.endpoint(), n.protocol_title()) }));
                ui.set_server_has_flag(flag.is_some());
                ui.set_server_flag(flag.unwrap_or_default());
            }
            None => { ui.set_server_name(ss(t!("No server"))); ui.set_server_detail(ss("")); ui.set_server_has_flag(false); }
        }
        let traffic = app.core.traffic.lock().unwrap().clone();
        ui.set_session_time(ss(match status { Status::Running(t0) => fmt_duration((now_ms() - t0) / 1000), _ => "00:00".into() }));
        ui.set_down_speed(ss(fmt_speed(traffic.down)));
        ui.set_up_speed(ss(fmt_speed(traffic.up)));
        ui.set_down_total(ss(fmt_bytes(traffic.total_down as i64)));
        ui.set_up_total(ss(fmt_bytes(traffic.total_up as i64)));
        let downs: Vec<f64> = traffic.history.iter().map(|x| x.1 as f64).collect();
        let ups: Vec<f64> = traffic.history.iter().map(|x| x.0 as f64).collect();
        let max = downs.iter().chain(ups.iter()).cloned().fold(1.0, f64::max);
        ui.set_speed_down_values(norm(&downs, max));
        ui.set_speed_up_values(norm(&ups, max));
        let ph = app.ping_history.lock().unwrap().clone();
        let ok: Vec<f64> = ph.iter().filter(|&&x| x >= 0).map(|&x| x as f64).collect();
        let cur = sel.as_ref().and_then(|n| pings.get(&n.id).copied());
        ui.set_ping_now(ss(match cur { Some(p) if p >= 0 => format!("{p} ms"), Some(-1) => t!("timeout"), _ => "— ms".into() }));
        ui.set_ping_values(norm(&ok, ok.iter().cloned().fold(1.0, f64::max) * 1.2));
        ui.set_ping_stats(ss(if ok.len() > 1 {
            let mn = ok.iter().cloned().fold(f64::MAX, f64::min); let mx = ok.iter().cloned().fold(0.0, f64::max);
            t!("min %1 · avg %2 · max %3", mn as i64, (ok.iter().sum::<f64>() / ok.len() as f64) as i64, mx as i64)
        } else { String::new() }));

        // ---- ip
        let ip = app.ip.lock().unwrap().clone().unwrap_or_default();
        ui.set_ip_loading(app.ip_loading.load(std::sync::atomic::Ordering::Relaxed));
        ui.set_ip(ss(&ip.ip));
        ui.set_ip_place(ss([ip.city.as_str(), ip.country.as_str()].iter().filter(|s| !s.is_empty()).cloned().collect::<Vec<_>>().join(", ")));
        ui.set_ip_isp(ss(&ip.isp));
        let ipf = flag_for_code(&ip.code).and_then(|f| self.flag(&f));
        ui.set_ip_has_flag(ipf.is_some());
        ui.set_ip_flag(ipf.unwrap_or_default());

        // ---- quota of the current subscription (or the first one)
        let sub = sel.as_ref().and_then(|n| st.subscriptions.iter().find(|s| Some(&s.id) == n.subscription_id.as_ref())).or(st.subscriptions.first());
        ui.set_has_quota(sub.is_some());
        if let Some(s) = sub {
            ui.set_quota_title(ss(&s.name));
            let used = s.upload + s.download;
            ui.set_quota_text(ss(if s.total > 0 { t!("%1 of %2", fmt_bytes(used), fmt_bytes(s.total)) } else { t!("Used %1 · unlimited", fmt_bytes(used)) }));
            ui.set_quota_used(if s.total > 0 { (used as f64 / s.total as f64) as f32 } else { -1.0 });
            ui.set_quota_expire(ss(expire_text(s)));
        }

        // ---- usage (last 14 days)
        let days: Vec<(String, i64)> = st.settings.usage_down.iter().rev().take(14).map(|(k, v)| (k.clone(), v + st.settings.usage_up.get(k).copied().unwrap_or(0))).collect::<Vec<_>>().into_iter().rev().collect();
        let mx = days.iter().map(|d| d.1).max().unwrap_or(1).max(1) as f32;
        ui.set_usage(model(days.iter().map(|(k, v)| Bar { value: *v as f32 / mx, label: ss(k.get(8..10).unwrap_or("")) }).collect()));
        let today = days.iter().find(|d| d.0 == crate::app::today()).map(|d| d.1).unwrap_or(0);
        ui.set_usage_total(ss(t!("Today %1", fmt_bytes(today))));
        ui.set_all_time(ss(t!("All time: %1", fmt_bytes(st.settings.all_time_down + st.settings.all_time_up))));

        // ---- sites
        let res = app.sites.lock().unwrap().clone();
        ui.set_sites(model(st.settings.sites.iter().map(|s| { let r = res.get(s); SiteItem { site: ss(s), ms: r.map(|x| x.0).unwrap_or(0), code: r.map(|x| x.1 as i32).unwrap_or(0), checked: r.is_some() } }).collect()));
        ui.set_sites_text(ss(st.settings.sites.join("
")));
        ui.set_sites_checking(app.sites_checking.load(std::sync::atomic::Ordering::Relaxed));

        // ---- speed test
        let running = app.speed_node.lock().unwrap().is_some();
        ui.set_speed_running(running);
        let sp = app.speed.lock().unwrap().clone();
        let last = st.settings.speed_results.last();
        let mbps = |v: Option<f64>| v.map(|x| format!("{x:.1} Mbps")).unwrap_or("—".into());
        if let Some(p) = &sp {
            ui.set_speed_phase(match p.phase { SpeedPhase::Preparing => 0, SpeedPhase::Ping => 1, SpeedPhase::Download => 2, SpeedPhase::Upload => 3, SpeedPhase::Done => 4 });
            ui.set_speed_fraction(p.fraction);
            ui.set_speed_now(ss(match p.phase { SpeedPhase::Preparing => t!("Preparing…"), SpeedPhase::Ping => t!("Measuring ping…"),
                SpeedPhase::Download => t!("Download: %1", mbps(Some(p.mbps))), SpeedPhase::Upload => t!("Upload: %1", mbps(Some(p.mbps))), SpeedPhase::Done => String::new() }));
            ui.set_speed_down(ss(mbps(p.down.or(if p.phase == SpeedPhase::Download { Some(p.mbps) } else { None }))));
            ui.set_speed_up(ss(mbps(p.up.or(if p.phase == SpeedPhase::Upload { Some(p.mbps) } else { None }))));
            ui.set_speed_ping(ss(p.ping.filter(|&x| x >= 0).map(|x| format!("{x} ms")).unwrap_or("—".into())));
        } else if let Some(r) = last {
            ui.set_speed_down(ss(mbps(Some(r.down_mbps)))); ui.set_speed_up(ss(mbps(Some(r.up_mbps))));
            ui.set_speed_ping(ss(if r.ping_ms >= 0 { format!("{} ms", r.ping_ms) } else { "—".into() }));
        } else { ui.set_speed_down(ss("—")); ui.set_speed_up(ss("—")); ui.set_speed_ping(ss("—")); }
        ui.set_speed_node(ss(last.map(|r| t!("Last: %1", split_flag(&r.node_name).1)).unwrap_or_default()));
        ui.set_speed_history(model(st.settings.speed_results.iter().rev().take(10).map(|r| SpeedRow {
            name: ss(&r.node_name), down: ss(mbps(Some(r.down_mbps))), up: ss(mbps(Some(r.up_mbps))), ping: ss(format!("{} ms", r.ping_ms)), when: ss(ago(r.at)) }).collect()));

        // ---- speedometer: a running test, else live download, else the last test
        let (val, caption) = if let Some(p) = sp.as_ref().filter(|_| running) {
            (match p.phase { SpeedPhase::Download | SpeedPhase::Upload => p.mbps, _ => 0.0 },
             match p.phase { SpeedPhase::Upload => t!("Upload"), SpeedPhase::Download => t!("Download"), _ => t!("Preparing…") })
        } else if matches!(status, Status::Running(_)) {
            (traffic.down as f64 * 8.0 / 1e6, t!("Live download"))
        } else if let Some(r) = last { (r.down_mbps, t!("Last test")) } else { (0.0, t!("Tap to test")) };
        let scale = [10.0, 25.0, 50.0, 100.0, 250.0, 500.0, 1000.0, 2500.0].into_iter().find(|m| val <= *m).unwrap_or(10_000.0);
        ui.set_gauge_value(ss(if val >= 100.0 { format!("{val:.0}") } else { format!("{val:.1}") }));
        ui.set_gauge_caption(ss(caption));
        ui.set_gauge_max(ss(format!("{scale:.0}")));
        ui.set_gauge_track(ss(arc_band(1.0)));
        ui.set_gauge_fill(ss(arc_band(val / scale)));

        // ---- servers
        let sig = hash(&(st.nodes.iter().map(|n| (&n.id, &n.name, &n.address, n.port)).collect::<Vec<_>>(), &pings.iter().collect::<std::collections::BTreeMap<_, _>>(),
            &testing.iter().collect::<std::collections::BTreeSet<_>>(), &sel_id, &self.search, &st.settings.server_sort, st.settings.show_hidden, st.settings.mask_addresses, &st.hidden,
            st.subscriptions.iter().map(|s| (&s.id, &s.name)).collect::<Vec<_>>(), (&st.settings.favorites, &st.settings.collapsed_groups,
            st.settings.speeds.iter().map(|(k, v)| (k, (*v * 10.0) as i64)).collect::<Vec<_>>(), crate::i18n::is_ru())));
        if sig != self.sig_nodes {
            self.sig_nodes = sig;
            let q = self.search.to_lowercase();
            let mut visible: Vec<&ProxyNode> = st.nodes.iter().filter(|n| (st.settings.show_hidden || !st.hidden.contains(&n.identity()))
                && (q.is_empty() || n.name.to_lowercase().contains(&q) || n.address.to_lowercase().contains(&q))).collect();
            let pk = |n: &ProxyNode| pings.get(&n.id).map(|&p| if p >= 0 { p } else { i32::MAX - 1 }).unwrap_or(i32::MAX);
            let mut items = vec![];
            let fav = |n: &ProxyNode| st.settings.favorites.contains(&n.identity());
            match st.settings.server_sort.as_str() {
                "NAME" => visible.sort_by_key(|n| split_flag(&n.name).1.to_lowercase()),
                "PING" => visible.sort_by_key(|n| pk(n)),
                "RECENT" => visible.sort_by_key(|n| -n.last_used_at),
                "SPEED" => visible.sort_by_key(|n| -(st.settings.speeds.get(&n.identity()).copied().unwrap_or(-1.0) * 10.0) as i64),
                "COUNTRY" => visible.sort_by_key(|n| pk(n)),
                _ => {}
            }
            let has_fav = visible.iter().any(|n| fav(n));
            if st.settings.server_sort == "COUNTRY" {
                // one group per country flag, the best ping on the header
                let mut groups: Vec<(String, Vec<&ProxyNode>)> = vec![];
                for n in &visible {
                    let code = split_flag(&n.name).0.and_then(|f| crate::countries::code_of_flag(&f)).unwrap_or_else(|| "??".into());
                    match groups.iter_mut().find(|g| g.0 == code) { Some(g) => g.1.push(n), None => groups.push((code, vec![n])) }
                }
                groups.sort_by_key(|g| (g.0 == "??", crate::countries::name(&g.0)));
                for (code, members) in groups {
                    let gid = format!("cc:{code}");
                    let folded = st.settings.collapsed_groups.contains(&gid);
                    let best = members.iter().filter_map(|n| pings.get(&n.id).copied().filter(|&p| p >= 0)).min().unwrap_or(PING_UNKNOWN);
                    let flag = crate::view::flag_for_code(&code).and_then(|f| self.flag(&f));
                    let name = if code == "??" { t!("Other") } else { crate::countries::name(&code) };
                    items.push(NodeItem { id: ss(&gid), header: true, name: ss(name), count: members.len() as i32, collapsed: folded, ping: best,
                        has_flag: flag.is_some(), flag: flag.unwrap_or_default(), ..Default::default() });
                    if folded { continue; }
                    for n in members { items.push(self.node_item(n, &st, &pings, testing.contains(&n.id), &sel_id)); }
                }
            } else if st.settings.server_sort == "MANUAL" && (!st.subscriptions.is_empty() || has_fav) {
                // favourites get their own group on top; every group can be folded
                let mut groups: Vec<(String, String)> = vec![];
                if has_fav { groups.push(("fav".into(), t!("Favourites"))); }
                let mut subs: Vec<&Subscription> = st.subscriptions.iter().collect();
                subs.sort_by_key(|s| !s.pinned);
                groups.extend(subs.iter().map(|s| (s.id.clone(), s.name.clone())));
                groups.push(("manual".into(), t!("Manual servers")));
                for (gid, gname) in groups {
                    let members: Vec<&&ProxyNode> = visible.iter().filter(|n| match gid.as_str() {
                        "fav" => fav(n),
                        "manual" => !fav(n) && n.subscription_id.is_none(),
                        g => !fav(n) && n.subscription_id.as_deref() == Some(g),
                    }).collect();
                    if members.is_empty() { continue; }
                    let folded = st.settings.collapsed_groups.contains(&gid);
                    items.push(NodeItem { id: ss(&gid), header: true, name: ss(&gname), count: members.len() as i32, collapsed: folded, ping: PING_UNKNOWN, ..Default::default() });
                    if folded { continue; }
                    for n in members { items.push(self.node_item(n, &st, &pings, testing.contains(&n.id), &sel_id)); }
                }
            } else {
                visible.sort_by_key(|n| !fav(n)); // stable: keeps the chosen order inside both parts
                for n in &visible { items.push(self.node_item(n, &st, &pings, testing.contains(&n.id), &sel_id)); }
            }
            ui.set_node_count(visible.len() as i32);
            ui.set_nodes(model(items));
            let mut top: Vec<&ProxyNode> = visible.clone();
            top.sort_by_key(|n| (n.id != sel_id, !fav(n), pk(n)));
            let top_items: Vec<NodeItem> = top.iter().take(30).map(|n| self.node_item(n, &st, &pings, testing.contains(&n.id), &sel_id)).collect();
            ui.set_top_nodes(model(top_items));
        }
        ui.set_pinging(!testing.is_empty());
        match app.speed_all.lock().unwrap().clone() {
            Some((done, total, name)) => { ui.set_speed_all_running(true); ui.set_speed_all_text(ss(t!("Measuring speed %1/%2 · %3", done + 1, total, name))); }
            None => ui.set_speed_all_running(false),
        }
        ui.set_has_servers(!st.nodes.is_empty() || !st.subscriptions.is_empty());

        // ---- subscriptions
        let updating = app.updating.lock().unwrap().clone();
        let sig = hash(&(serde_json::to_string(&st.subscriptions).unwrap_or_default(), updating.iter().collect::<std::collections::BTreeSet<_>>(), st.nodes.len(), now_ms() / 60_000));
        if sig != self.sig_subs {
            self.sig_subs = sig;
            let mut subs: Vec<&Subscription> = st.subscriptions.iter().collect();
            subs.sort_by_key(|s| !s.pinned);
            let items: Vec<SubItem> = subs.iter().map(|s| {
                let logo = self.logo(&s.logo_file);
                let count = st.nodes.iter().filter(|n| n.subscription_id.as_ref() == Some(&s.id)).count();
                let used = s.upload + s.download;
                let hist: Vec<f64> = s.usage_history.iter().map(|p| p.used as f64).collect();
                SubItem {
                    id: ss(&s.id), name: ss(&s.name), url: ss(&s.url), has_logo: logo.is_some(), logo: logo.unwrap_or_default(),
                    info: ss(t!("%1 servers · updated %2", count, if s.updated_at > 0 { ago(s.updated_at) } else { t!("never") })),
                    quota: ss(if s.total > 0 { t!("%1 of %2", fmt_bytes(used), fmt_bytes(s.total)) } else if used > 0 { t!("Used %1 · unlimited", fmt_bytes(used)) } else { String::new() }),
                    expire: ss(expire_text(s)), used: if s.total > 0 { (used as f64 / s.total as f64) as f32 } else { -1.0 },
                    expired: s.expired(), used_up: s.used_up(), updating: updating.contains(&s.id), error: ss(&s.last_error), pinned: s.pinned,
                    announce: ss(&s.announce), support: ss(&s.support_url), website: ss(&s.web_page_url), auto_hours: s.auto_update_hours,
                    user_agent: ss(&s.user_agent),
                    history: { let mn = hist.iter().cloned().fold(f64::MAX, f64::min); let mx = hist.iter().cloned().fold(0.0, f64::max); model(hist.iter().map(|x| if mx > mn { (0.15 + 0.85 * (x - mn) / (mx - mn)) as f32 } else { 0.5 }).collect()) },
                }
            }).collect();
            ui.set_subs(model(items));
        }

        // ---- routing
        let r = &st.routing;
        let sig = hash(&(serde_json::to_string(r).unwrap_or_default(), serde_json::to_string(&st.settings.split).unwrap_or_default(), st.settings.app_paths.len()));
        if sig != self.sig_rules {
            self.sig_rules = sig;
            let rules: Vec<RuleItem> = r.rules.iter().map(|x| {
                let icon = if x.kind == "PROCESS" { self.app_icon(x.value.split(',').next().unwrap_or("").trim(), &st.settings.app_paths) } else { None };
                RuleItem { id: ss(&x.id), kind: ss(&x.kind), value: ss(&x.value), action: ss(&x.action), enabled: x.enabled, has_icon: icon.is_some(), icon: icon.unwrap_or_default() }
            }).collect();
            ui.set_rules(model(rules));
            ui.set_routing(Routing {
                mode: match r.mode.as_str() { "GLOBAL" => 1, "DIRECT" => 2, _ => 0 }, bypass_lan: r.bypass_lan, bypass_ru: r.bypass_ru, block_ads: r.block_ads,
                proxy_blocked_ru: r.proxy_blocked_ru, domain_strategy: match r.domain_strategy.as_str() { "AsIs" => 0, "IPOnDemand" => 2, _ => 1 },
                split_enabled: st.settings.split.enabled, split_mode: if st.settings.split.mode == "INCLUDE" { 1 } else { 0 },
                preset_streaming: r.preset_streaming, preset_ai: r.preset_ai, preset_social: r.preset_social,
                preset_games: r.preset_games_direct, preset_torrents: r.preset_block_torrents,
            });
            let apps: Vec<AppItem> = st.settings.split.apps.iter().map(|a| self.app_item(a, &st.settings.app_paths)).collect();
            ui.set_split_apps(model(apps));
        }

        // ---- widgets
        let sig = hash(&st.settings.home_widgets.iter().map(|w| (&w.id, &w.kind, w.x, w.y, w.w, w.h)).collect::<Vec<_>>());
        if sig != self.sig_widgets {
            self.sig_widgets = sig;
            ui.set_widgets(model(st.settings.home_widgets.iter().map(|w| WidgetItem { id: ss(&w.id), kind: ss(&w.kind), x: w.x, y: w.y, w: w.w, h: w.h }).collect()));
            ui.set_grid_rows(st.settings.home_widgets.iter().map(|w| w.y + w.h).max().unwrap_or(1));
        }

        // ---- logs
        let logs = app.core.logs.lock().unwrap();
        let lsig = (logs.len(), self.log_filter, logs.last().map(|l| l.time).unwrap_or(0) ^ hash(&self.log_search) as i64);
        if lsig != self.sig_logs && !self.logs_paused {
            let q = self.log_search.to_lowercase();
            self.sig_logs = lsig;
            let items: Vec<LogItem> = logs.iter().rev().filter(|l| match self.log_filter { 1 => l.level == 'W' || l.level == 'E', 2 => l.level == 'E', _ => true })
                .filter(|l| q.is_empty() || l.text.to_lowercase().contains(&q)).take(1000)
                .map(|l| LogItem { time: ss(clock(l.time)), level: ss(l.level.to_string()), text: ss(&l.text) }).collect();
            drop(logs);
            ui.set_logs(model(items));
        } else { drop(logs); }

        // ---- settings
        let sig = hash(&serde_json::to_string(&st.settings).unwrap_or_default());
        if sig != self.sig_settings {
            self.sig_settings = sig;
            let s = &st.settings;
            let seed = s.seed_color as u32;
            ui.set_settings(Settings {
                theme_mode: match s.theme_mode.as_str() { "LIGHT" => 1, "DARK" => 2, _ => 0 },
                color_source: if s.color_source == "WINDOWS_ACCENT" { 0 } else { 1 },
                seed: slint::Color::from_rgb_u8((seed >> 16) as u8, (seed >> 8) as u8, seed as u8),
                language: match s.language.as_str() { "en" => 1, "ru" => 2, _ => 0 },
                connect_mode: if s.connect_mode == "TUN" { 1 } else { 0 },
                socks_port: ss(s.socks_port.to_string()), http_port: ss(s.http_port.to_string()),
                allow_lan: s.allow_lan, sniffing: s.sniffing, mux: s.mux, ipv6: s.ipv6, remote_dns: ss(&s.remote_dns), direct_dns: ss(&s.direct_dns),
                log_level: ["debug", "info", "warning", "error", "none"].iter().position(|x| *x == s.log_level).unwrap_or(2) as i32,
                tun_strict_dns: s.tun_strict_dns, geo_source: if s.geo_source == "RUNETFREEDOM" { 1 } else { 0 }, user_agent: ss(&s.user_agent),
                client_spoof: match s.client_spoof.as_str() { "HAPP" => 1, "INCY" => 2, _ => 0 }, ping_url: ss(&s.ping_url),
                apply_panel_routing: s.apply_panel_routing, minimize_to_tray: s.minimize_to_tray, auto_connect: s.auto_connect,
                autostart: crate::win::autostart(), check_updates: s.check_updates, notify_connection: s.notify_connection, notify_errors: s.notify_errors,
                notify_subscription: s.notify_subscription, notify_updates: s.notify_updates, mask_addresses: s.mask_addresses, show_hidden: s.show_hidden,
                server_sort: match s.server_sort.as_str() { "NAME" => 1, "PING" => 2, "RECENT" => 3, "SPEED" => 4, "COUNTRY" => 5, _ => 0 }, hwid: ss(&s.hwid),
                clipboard_offer: s.clipboard_offer, auto_ping: s.auto_ping, auto_reconnect: s.auto_reconnect, kill_switch: s.kill_switch,
                engine: if s.core == "SINGBOX" { 1 } else { 0 },
            });
        }

        // ---- dialogs
        let err = app.last_error.lock().unwrap().clone();
        ui.set_show_error(app.flags.show_error.load(std::sync::atomic::Ordering::Relaxed) && err.is_some());
        if let Some(e) = err { ui.set_error_title(ss(&e.title)); ui.set_error_hint(ss(&e.hint)); ui.set_error_details(ss(&e.details)); }
        ui.set_need_admin(app.flags.need_admin.load(std::sync::atomic::Ordering::Relaxed));
        ui.set_need_amnezia(app.flags.need_amnezia.load(std::sync::atomic::Ordering::Relaxed));
        let pi = app.pending_import.lock().unwrap().clone();
        ui.set_pending_import(pi.is_some());
        if let Some((_, s)) = pi { ui.set_pending_summary(ss(s)); }
        let upd = app.update.lock().unwrap().clone();
        ui.set_update_available(upd.is_some());
        if let Some(u) = upd { ui.set_update_version(ss(&u.version)); ui.set_update_notes(ss(plain_notes(&u.notes))); }
        ui.set_update_progress(app.update_progress.lock().unwrap().unwrap_or(-1.0));
        ui.set_update_ready(app.update_ready.lock().unwrap().is_some());
        let wn = app.whats_new.lock().unwrap().clone();
        ui.set_whats_new(wn.is_some());
        if let Some(u) = wn { ui.set_whats_new_version(ss(&u.version)); ui.set_whats_new_notes(ss(plain_notes(&u.notes))); }
        ui.set_core_version(ss(app.core_version()));
        ui.set_singbox_version(ss(app.singbox_version()));
        ui.set_show_disclaimer(!st.settings.disclaimer_accepted);
        ui.set_is_admin(crate::win::is_admin());
    }

    /// Fields of the server editor (simple key/value list over the stored JSON).
    pub fn editor_fields(n: &ProxyNode) -> Vec<FieldItem> {
        let v = serde_json::to_value(n).unwrap_or_default();
        let keys: &[(&str, &str)] = match n.protocol.as_str() {
            "WIREGUARD" => &[("name", "Name"), ("address", "Address"), ("port", "Port"), ("wgPrivateKey", "Private key"), ("wgPublicKey", "Public key"),
                ("wgPreSharedKey", "Pre-shared key"), ("wgAddresses", "Addresses"), ("wgDns", "DNS"), ("wgAllowedIps", "Allowed IPs"), ("wgMtu", "MTU"), ("wgReserved", "Reserved")],
            "XRAY_CONFIG" => &[("name", "Name")],
            _ => &[("name", "Name"), ("address", "Address"), ("port", "Port"), ("uuid", "UUID"), ("password", "Password"), ("username", "Username"),
                ("method", "Method"), ("flow", "Flow"), ("network", "Transport"), ("host", "Host"), ("path", "Path"), ("serviceName", "gRPC service"),
                ("security", "Security"), ("sni", "SNI"), ("alpn", "ALPN"), ("fingerprint", "Fingerprint"), ("publicKey", "REALITY public key"),
                ("shortId", "REALITY short ID"), ("spiderX", "REALITY spiderX"), ("pinnedPeerCertSha256", "Pinned cert SHA-256"), ("obfsPassword", "Obfs password")],
        };
        keys.iter().map(|(k, l)| FieldItem { key: ss(*k), label: ss(t!(*l)), value: ss(match &v[*k] { serde_json::Value::String(s) => s.clone(), serde_json::Value::Null => String::new(), x => x.to_string() }) }).collect()
    }

    pub fn apply_editor(n: &ProxyNode, fields: &[FieldItem]) -> Option<ProxyNode> {
        let mut v = serde_json::to_value(n).ok()?;
        for f in fields {
            let k = f.key.as_str();
            v[k] = match &v[k] { serde_json::Value::Number(_) => serde_json::Value::from(f.value.trim().parse::<i64>().ok()?), _ => serde_json::Value::from(f.value.to_string()) };
        }
        serde_json::from_value(v).ok()
    }
}

// ------------------------------------------------------------------ formatting

/// Release notes are Markdown; the dialog shows plain text: no emphasis marks, bullets as •, no "Downloads" section.
pub fn plain_notes(md: &str) -> String {
    let mut out = vec![];
    for line in md.lines() {
        let l = line.trim_end();
        if l.trim_start().starts_with("**Downloads**") || l.trim_start().starts_with("## Download") { break; }
        let l = l.replace("**", "").replace('`', "");
        let t = l.trim_start();
        let l = if let Some(h) = t.strip_prefix("## ").or_else(|| t.strip_prefix("# ")) { h.to_string() }
            else if let Some(b) = t.strip_prefix("- ").or_else(|| t.strip_prefix("* ")) { format!("•  {b}") } else { l.clone() };
        out.push(l);
    }
    while out.last().is_some_and(|l| l.trim().is_empty()) { out.pop(); }
    out.join("\n")
}

pub fn fmt_bytes(b: i64) -> String {
    let b = b.max(0) as f64;
    let (v, u) = if b >= 1e12 { (b / 1099511627776.0, "TB") } else if b >= 1e9 { (b / 1073741824.0, "GB") } else if b >= 1e6 { (b / 1048576.0, "MB") } else if b >= 1e3 { (b / 1024.0, "KB") } else { (b, "B") };
    if u == "B" { format!("{v:.0} {u}") } else if v >= 100.0 { format!("{v:.0} {u}") } else { format!("{v:.1} {u}") }
}
pub fn fmt_speed(bps: u64) -> String { format!("{}/s", fmt_bytes(bps as i64)) }
fn fmt_duration(s: i64) -> String { let s = s.max(0); if s >= 3600 { format!("{}:{:02}:{:02}", s / 3600, s / 60 % 60, s % 60) } else { format!("{:02}:{:02}", s / 60, s % 60) } }

fn ago(t: i64) -> String {
    let m = (now_ms() - t) / 60_000;
    if m < 1 { t!("just now") } else if m < 60 { t!("%1 min ago", m) } else if m < 1440 { t!("%1 h ago", m / 60) } else { t!("%1 d ago", m / 1440) }
}

fn local(ms: i64) -> (i64, i64, i64, i64, i64) {
    // civil date from local time (offset from the system)
    #[repr(C)] struct Tz { bias: i32, _rest: [u8; 168] }
    #[link(name = "kernel32")] unsafe extern "system" { fn GetTimeZoneInformation(tz: *mut Tz) -> u32; }
    let mut tz = Tz { bias: 0, _rest: [0; 168] };
    let r = unsafe { GetTimeZoneInformation(&mut tz) };
    let dst = if r == 2 { i32::from_le_bytes([tz._rest[164], tz._rest[165], tz._rest[166], tz._rest[167]]) } else { 0 };
    let secs = ms / 1000 - (tz.bias as i64 + dst as i64) * 60;
    let days = secs.div_euclid(86400);
    let rem = secs.rem_euclid(86400);
    let z = days + 719468; let era = z.div_euclid(146097); let doe = z - era * 146097;
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365; let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153; let d = doy - (153 * mp + 2) / 5 + 1; let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let y = yoe + era * 400 + if m <= 2 { 1 } else { 0 };
    (y, m, d, rem / 3600, rem / 60 % 60)
}
fn clock(ms: i64) -> String { let (_, _, _, h, m) = local(ms); format!("{h:02}:{m:02}:{:02}", (ms / 1000) % 60) }
fn date(ms: i64) -> String { let (y, m, d, _, _) = local(ms); format!("{d:02}.{m:02}.{y}") }

fn expire_text(s: &Subscription) -> String {
    if s.expire <= 0 { return String::new(); }
    let ms = s.expire * 1000;
    if s.expired() { t!("Expired %1", date(ms)) } else { t!("Until %1 · %2 d", date(ms), (ms - now_ms()) / 86_400_000) }
}

fn norm(v: &[f64], max: f64) -> ModelRc<f32> { model(v.iter().map(|x| (x / max.max(1e-9)) as f32).collect()) }

#[allow(dead_code)]
/// A 270° gauge band (100 x 100 box) filled to `f` (0..1), with round ends.
pub fn arc_band(f: f64) -> String {
    let f = f.clamp(0.0, 1.0);
    if f < 0.005 { return String::new(); }
    let (c, ro, ri) = (50.0f64, 47.0f64, 37.0f64);
    let rc = (ro - ri) / 2.0;
    let a0 = 135f64.to_radians();
    let a1 = (135.0 + 270.0 * f).to_radians();
    let p = |r: f64, a: f64| (c + r * a.cos(), c + r * a.sin());
    let large = if 270.0 * f > 180.0 { 1 } else { 0 };
    let (o0, o1, i1, i0) = (p(ro, a0), p(ro, a1), p(ri, a1), p(ri, a0));
    format!("M {:.2} {:.2} A {ro} {ro} 0 {large} 1 {:.2} {:.2} A {rc} {rc} 0 0 1 {:.2} {:.2} A {ri} {ri} 0 {large} 0 {:.2} {:.2} A {rc} {rc} 0 0 1 {:.2} {:.2} Z",
        o0.0, o0.1, o1.0, o1.1, i1.0, i1.1, i0.0, i0.1, o0.0, o0.1)
}

/// SVG path in a 100 x 40 box.
pub fn chart(v: &[f64], max: f64, area: bool) -> String {
    if v.len() < 2 { return String::new(); }
    let n = v.len() - 1;
    let mut s = String::with_capacity(v.len() * 14);
    for (i, x) in v.iter().enumerate() {
        let px = i as f64 * 100.0 / n as f64;
        let py = 38.0 - (x / max).clamp(0.0, 1.0) * 36.0;
        s += &format!("{}{px:.1} {py:.1} ", if i == 0 { "M " } else { "L " });
    }
    if area { s += "L 100 40 L 0 40 Z"; }
    s
}
