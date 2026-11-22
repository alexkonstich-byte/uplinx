// Uplinx — a Material You proxy client for Windows powered by Xray-core.
#![windows_subsystem = "windows"]

mod app;
mod core;
#[macro_use]
mod i18n;
mod model;
mod parse;
mod qr;
mod state;
mod theme;
mod view;
mod win;
#[cfg(test)]
mod tests;

use app::{Action, App};
use slint::winit_030::{winit::event::WindowEvent, EventResult, WinitWindowAccessor};
use slint::{CloseRequestResponse, ComponentHandle, Model};
use std::cell::RefCell;
use std::rc::Rc;
use std::sync::atomic::Ordering::Relaxed;
use std::sync::Arc;
use std::time::{Duration, Instant};

slint::include_modules!();

fn apply_language(setting: &str) {
    i18n::apply(setting);
    let _ = slint::select_bundled_translation(if i18n::is_ru() { "ru" } else { "" });
}

struct Shell {
    w: AppWindow,
    mini: MiniWindow,
    app: Arc<App>,
    view: RefCell<view::View>,
    msg: RefCell<Option<(app::Msg, Instant)>>,
    last_rev: std::cell::Cell<u64>,
    hidden: std::cell::Cell<bool>,
}

impl Shell {
    fn show_main(&self) {
        let _ = self.mini.hide();
        let _ = self.w.show();
        self.w.window().set_minimized(false);
        self.hidden.set(false);
        self.w.window().with_winit_window(|w| w.focus_window());
        self.last_rev.set(0);
    }

    fn hide_to_tray(&self) {
        let _ = self.w.hide();
        self.hidden.set(true);
        win::trim_memory();
    }

    fn quit(&self) {
        self.app.shutdown();
        win::tray_remove();
        let _ = slint::quit_event_loop();
    }

    fn tray_labels(&self) -> [String; 3] {
        [t!("Show"), if self.app.is_connected() { t!("Disconnect") } else { t!("Connect") }, t!("Quit")]
    }

    fn tick(&self) {
        let rev = self.app.rev.load(Relaxed);
        if rev != self.last_rev.get() {
            self.last_rev.set(rev);
            if !self.hidden.get() || self.mini.window().is_visible() {
                self.view.borrow_mut().refresh(&self.w, &self.app);
                self.sync_mini();
            }
            for (title, text, err) in self.app.toasts.lock().unwrap().drain(..) { win::notify(&title, &text, err); }
            let ui = self.w.global::<Ui>();
            let tip = format!("Uplinx — {}", ui.get_status_text());
            win::tray_update(&tip, self.tray_labels(), self.app.is_connected());
        }
        // snackbar: one message at a time, 5 s each
        let mut cur = self.msg.borrow_mut();
        if cur.as_ref().is_some_and(|(_, t)| t.elapsed() > Duration::from_secs(5)) { *cur = None; self.set_msg(None); }
        if cur.is_none() {
            if let Some(m) = self.app.messages.lock().unwrap().pop_front() { self.set_msg(Some(&m)); *cur = Some((m, Instant::now())); }
        }
    }

    fn set_msg(&self, m: Option<&app::Msg>) {
        let ui = self.w.global::<Ui>();
        ui.set_message(m.map(|m| m.text.clone()).unwrap_or_default().into());
        ui.set_message_action(m.and_then(|m| m.action.as_ref().map(|a| a.0.clone())).unwrap_or_default().into());
    }

    fn sync_mini(&self) {
        let (a, b) = (self.w.global::<Ui>(), self.mini.global::<Ui>());
        b.set_status(a.get_status());
        b.set_status_text(a.get_status_text());
        b.set_server_name(a.get_server_name());
        b.set_down_speed(a.get_down_speed());
        b.set_up_speed(a.get_up_speed());
    }
}

fn main() -> anyhow::Result<()> {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let listener = match win::single_instance(&args) { Ok(l) => l, Err(()) => return Ok(()) };

    let app = App::new();
    win::register_integration();
    let st = app.snapshot();

    let w = AppWindow::new()?;
    let mini = MiniWindow::new()?;
    apply_language(&st.settings.language); // needs the Slint context the first window creates
    theme::apply(&w, &mini, &st.settings);
    w.global::<Ui>().set_app_version(core::update::VERSION.into());

    let sh = Rc::new(Shell { w: w.clone_strong(), mini: mini.clone_strong(), app: app.clone(), view: Default::default(), msg: Default::default(),
        last_rev: Default::default(), hidden: Default::default() });

    wire(&sh);

    // tray + notifications
    {
        let s = Rc::downgrade(&sh);
        win::tray_create("Uplinx", sh.tray_labels(), move |e| {
            let Some(s) = s.upgrade() else { return };
            match e {
                win::TrayEvent::Show => s.show_main(),
                win::TrayEvent::Toggle => s.app.toggle(),
                win::TrayEvent::Quit => s.quit(),
            }
        });
    }

    // a second launch (or an uplinx:// link) hands its arguments over
    if let Some(l) = listener {
        let weak = w.as_weak();
        let a2 = app.clone();
        win::listen_instances(l, move |lines| {
            let a2 = a2.clone();
            let _ = weak.upgrade_in_event_loop(move |w| {
                let _ = w.show();
                w.window().set_minimized(false);
                w.window().with_winit_window(|x| x.focus_window());
                for l in lines.iter().filter(|l| !l.starts_with("--")) { a2.request_import(l); }
            });
        });
    }
    for l in args.iter().filter(|a| !a.starts_with("--")) { app.request_import(l); }

    // motion only while the window is focused: no idle animations eat CPU in the background
    {
        let weak = w.as_weak();
        w.window().on_winit_window_event(move |_, e| {
            if let WindowEvent::Focused(f) = e { if let Some(w) = weak.upgrade() { w.global::<Theme>().set_motion(*f); } }
            EventResult::Propagate
        });
        let weak = mini.as_weak();
        mini.window().on_winit_window_event(move |_, e| {
            if let WindowEvent::Focused(f) = e { if let Some(w) = weak.upgrade() { w.global::<Theme>().set_motion(*f); } }
            EventResult::Propagate
        });
    }
    {
        let s = Rc::downgrade(&sh);
        w.window().on_close_requested(move || {
            if let Some(s) = s.upgrade() { if s.app.settings().minimize_to_tray { s.hide_to_tray(); } else { s.quit(); } }
            CloseRequestResponse::KeepWindowShown
        });
    }

    let timer = slint::Timer::default();
    {
        let s = Rc::downgrade(&sh);
        timer.start(slint::TimerMode::Repeated, Duration::from_millis(200), move || { if let Some(s) = s.upgrade() { s.tick(); } });
    }

    if let Some(p) = args.iter().find_map(|a| a.strip_prefix("--page=")).and_then(|p| p.parse().ok()) { w.global::<Nav>().set_page(p); }
    if args.iter().any(|a| a == "--connect") || st.settings.auto_connect { app.connect(); }
    if args.iter().any(|a| a == "--minimized") { sh.hidden.set(true); } else { w.show()?; }
    sh.tick();
    slint::run_event_loop_until_quit()?;
    app.shutdown();
    Ok(())
}

fn wire(sh: &Rc<Shell>) {
    let w = &sh.w;
    let logic = w.global::<Logic>();
    macro_rules! on {
        ($name:ident, |$s:ident $(, $arg:ident)*| $body:expr) => {{
            let weak = Rc::downgrade(sh);
            logic.$name(move |$($arg),*| { let Some($s) = weak.upgrade() else { return Default::default() }; $body });
        }};
    }

    // ---- window
    on!(on_minimize, |s| { s.w.window().set_minimized(true); win::trim_memory(); });
    on!(on_toggle_maximize, |s| { let m = !s.w.window().is_maximized(); s.w.window().set_maximized(m); s.w.set_is_maximized(m); });
    on!(on_close, |s| { if s.app.settings().minimize_to_tray { s.hide_to_tray(); } else { s.quit(); } });
    on!(on_drag, |s| { s.w.window().with_winit_window(|w| { let _ = w.drag_window(); }); });
    on!(on_toggle_mini, |s| { s.hide_to_tray(); let _ = s.mini.show(); s.last_rev.set(0); });
    on!(on_open_url, |s, url| win::open_url(&url));
    on!(on_copy, |s, text| { win::set_clipboard(&text); s.app.notify(t!("Copied"), None); });
    on!(on_open_data_folder, |s| win::open_url(&state::home().to_string_lossy()));

    // ---- connection
    on!(on_toggle_connect, |s| s.app.toggle());
    on!(on_set_connect_mode, |s, i| s.app.set_connect_mode(if i == 1 { "TUN" } else { "SYSTEM_PROXY" }));
    on!(on_set_routing_mode, |s, i| s.app.set_routing_mode(["RULE", "GLOBAL", "DIRECT"][i.clamp(0, 2) as usize]));

    // ---- servers
    on!(on_select_node, |s, id| s.app.select(&id));
    on!(on_ping_all, |s| s.app.ping_all(None));
    on!(on_ping_node, |s, id| s.app.ping_all(Some(vec![id.to_string()])));
    on!(on_ping_current, |s| s.app.ping_current());
    on!(on_search, |s, q| { s.view.borrow_mut().search = q.to_string(); s.app.bump(); });
    on!(on_set_sort, |s, i| s.app.update_settings(|x| x.server_sort = ["MANUAL", "NAME", "PING", "RECENT"][i.clamp(0, 3) as usize].into()));
    on!(on_toggle_group, |s, _id| {});
    on!(on_node_menu, |s, id, action| {
        let node = s.app.snapshot().nodes.into_iter().find(|n| n.id == id.as_str());
        match action.as_str() {
            "copy" => s.app.copy_link(&id),
            "delete" => s.app.delete_node(&id),
            "hide" => s.app.set_hidden(&id, true),
            "unhide" => s.app.set_hidden(&id, false),
            "speed" => { s.app.run_speed_test(node); }
            "qr" => if let Some(n) = node {
                let text = if n.protocol == "WIREGUARD" { parse::wg_to_conf(&n) } else { parse::links::to_link(&n) };
                if let Some((px, size)) = qr::encode(&text) {
                    let ui = s.w.global::<Ui>();
                    ui.set_qr_image(slint::Image::from_rgba8(slint::SharedPixelBuffer::clone_from_slice(&px, size, size)));
                    ui.set_qr_title(view::split_flag(&n.name).1.into());
                    ui.set_show_qr(true);
                }
            },
            "edit" => if let Some(n) = node {
                let ui = s.w.global::<Ui>();
                ui.set_editor_title(t!("Edit server").into());
                ui.set_editor_fields(slint::ModelRc::new(slint::VecModel::from(view::View::editor_fields(&n))));
                s.view.borrow_mut().editing = Some(n.id.clone());
                ui.set_show_editor(true);
            },
            _ => {}
        }
    });
    on!(on_editor_save, |s, fields| {
        let id = s.view.borrow_mut().editing.take();
        if let Some(n) = id.and_then(|id| s.app.snapshot().nodes.into_iter().find(|n| n.id == id)) {
            let f: Vec<FieldItem> = fields.iter().collect();
            match view::View::apply_editor(&n, &f) { Some(nn) => s.app.upsert_node(nn), None => s.app.notify(t!("Ports must be numbers from 1 to 65535"), None) }
        }
        s.w.global::<Ui>().set_show_editor(false);
    });

    // ---- import
    {
        let weak = Rc::downgrade(sh);
        logic.on_import_text(move |text| { let Some(s) = weak.upgrade() else { return 0 }; s.app.import_text(&text, None) as i32 });
    }
    on!(on_import_clipboard, |s| s.app.import_clipboard());
    on!(on_import_file, |s| {
        let a = s.app.clone();
        let title = t!("Choose files");
        std::thread::spawn(move || { let files = win::open_files(&title); if !files.is_empty() { a.import_files(files); } });
    });
    on!(on_scan_screen, |s| s.app.scan_screen_qr());
    on!(on_confirm_import, |s, ok| {
        if let Some((text, _)) = s.app.pending_import.lock().unwrap().take() { if ok { s.app.import_text(&text, None); } }
        s.app.bump();
    });

    // ---- subscriptions
    on!(on_add_sub, |s, name, url| s.app.add_subscription(&name, &url));
    on!(on_update_sub, |s, id| s.app.update_subscription(&id));
    on!(on_update_all_subs, |s| s.app.update_all_subscriptions());
    on!(on_edit_sub, |s, id, name, url, hours, ua| {
        if let Some(mut sub) = s.app.snapshot().subscriptions.into_iter().find(|x| x.id == id.as_str()) {
            sub.name = name.trim().to_string(); sub.url = url.trim().to_string(); sub.auto_update_hours = hours.max(0); sub.user_agent = ua.trim().to_string();
            s.app.edit_subscription(sub);
        }
    });
    on!(on_delete_sub, |s, id| s.app.delete_subscription(&id));
    on!(on_pin_sub, |s, id| if let Some(mut sub) = s.app.snapshot().subscriptions.into_iter().find(|x| x.id == id.as_str()) { sub.pinned = !sub.pinned; s.app.edit_subscription(sub); });

    // ---- routing
    on!(on_set_routing_bool, |s, key, v| match key.as_str() {
        "split-enabled" => s.app.update_settings(|x| x.split.enabled = v),
        k => s.app.update_routing(|r| match k { "bypass-lan" => r.bypass_lan = v, "bypass-ru" => r.bypass_ru = v, "block-ads" => r.block_ads = v, "proxy-blocked-ru" => r.proxy_blocked_ru = v, _ => {} }),
    });
    on!(on_set_domain_strategy, |s, i| s.app.update_routing(|r| r.domain_strategy = ["AsIs", "IPIfNonMatch", "IPOnDemand"][i.clamp(0, 2) as usize].into()));
    on!(on_save_rule, |s, id, kind, value, action| {
        let mut rule = s.app.snapshot().routing.rules.into_iter().find(|r| r.id == id.as_str()).unwrap_or_default();
        rule.kind = kind.to_string(); rule.value = value.trim().to_string(); rule.action = action.to_string();
        s.app.upsert_rule(rule);
    });
    on!(on_toggle_rule, |s, id, v| { if let Some(mut r) = s.app.snapshot().routing.rules.into_iter().find(|r| r.id == id.as_str()) { r.enabled = v; s.app.upsert_rule(r); } });
    on!(on_delete_rule, |s, id| s.app.delete_rule(&id));
    on!(on_move_rule, |s, id, d| s.app.move_rule(&id, d));
    on!(on_apply_preset, |s, _name| s.app.update_routing(|r| { r.mode = "RULE".into(); r.bypass_lan = true; r.bypass_ru = true; r.block_ads = true; }));
    on!(on_split_add, |s, name| {
        let n = name.trim().trim_matches('"').to_string();
        if !n.is_empty() { s.app.update_settings(|x| if !x.split.apps.iter().any(|a| a.eq_ignore_ascii_case(&n)) { x.split.apps.push(n.clone()); }); }
    });
    on!(on_split_remove, |s, name| s.app.update_settings(|x| x.split.apps.retain(|a| a != name.as_str())));
    on!(on_refresh_processes, |s| {
        let list: Vec<slint::SharedString> = win::running_processes().into_iter().map(Into::into).collect();
        s.w.global::<Ui>().set_processes(slint::ModelRc::new(slint::VecModel::from(list)));
    });

    // ---- settings
    on!(on_set_bool, |s, key, v| match key.as_str() {
        "autostart" => { win::set_autostart(v); s.app.bump(); s.view.borrow_mut().refresh(&s.w, &s.app); }
        k => s.app.update_settings(|x| match k {
            "allow-lan" => x.allow_lan = v, "sniffing" => x.sniffing = v, "mux" => x.mux = v, "ipv6" => x.ipv6 = v, "tun-strict-dns" => x.tun_strict_dns = v,
            "apply-panel-routing" => x.apply_panel_routing = v, "minimize-to-tray" => x.minimize_to_tray = v, "auto-connect" => x.auto_connect = v,
            "check-updates" => x.check_updates = v, "notify-connection" => x.notify_connection = v, "notify-errors" => x.notify_errors = v,
            "notify-subscription" => x.notify_subscription = v, "notify-updates" => x.notify_updates = v, "mask-addresses" => x.mask_addresses = v,
            "show-hidden" => x.show_hidden = v, _ => {}
        }),
    });
    on!(on_set_int, |s, key, i| {
        match key.as_str() {
            "theme-mode" => s.app.update_settings(|x| x.theme_mode = ["SYSTEM", "LIGHT", "DARK"][i.clamp(0, 2) as usize].into()),
            "color-source" => s.app.update_settings(|x| x.color_source = if i == 1 { "CUSTOM".into() } else { "WINDOWS_ACCENT".into() }),
            "language" => { s.app.update_settings(|x| x.language = ["system", "en", "ru"][i.clamp(0, 2) as usize].into()); apply_language(&s.app.settings().language); }
            "log-level" => s.app.update_settings(|x| x.log_level = ["debug", "info", "warning", "error", "none"][i.clamp(0, 4) as usize].into()),
            "geo-source" => { s.app.update_settings(|x| x.geo_source = if i == 1 { "RUNETFREEDOM".into() } else { "XRAY".into() }); s.app.update_geo(); }
            "client-spoof" => s.app.update_settings(|x| x.client_spoof = ["NONE", "HAPP", "INCY"][i.clamp(0, 2) as usize].into()),
            "split-mode" => s.app.update_settings(|x| x.split.mode = if i == 1 { "INCLUDE".into() } else { "EXCLUDE".into() }),
            _ => {}
        }
        if key.as_str() == "theme-mode" || key.as_str() == "color-source" { let st = s.app.settings(); theme::apply(&s.w, &s.mini, &st); }
    });
    on!(on_set_text, |s, key, v| {
        let v = v.trim().to_string();
        match key.as_str() {
            "socks-port" | "http-port" => match v.parse::<u16>() {
                Ok(p) if p > 0 => s.app.update_settings(|x| if key.as_str() == "socks-port" { x.socks_port = p } else { x.http_port = p }),
                _ => s.app.notify(t!("Ports must be numbers from 1 to 65535"), None),
            },
            "remote-dns" => s.app.update_settings(|x| x.remote_dns = v),
            "direct-dns" => s.app.update_settings(|x| x.direct_dns = v),
            "ping-url" => s.app.update_settings(|x| x.ping_url = v),
            "user-agent" => s.app.update_settings(|x| x.user_agent = v),
            _ => {}
        }
    });
    on!(on_set_seed, |s, c| {
        s.app.update_settings(|x| { x.seed_color = 0xFF00_0000 | ((c.red() as i64) << 16) | ((c.green() as i64) << 8) | c.blue() as i64; x.color_source = "CUSTOM".into(); });
        let st = s.app.settings(); theme::apply(&s.w, &s.mini, &st);
    });
    on!(on_update_core, |s| s.app.update_core());
    on!(on_update_geo, |s| s.app.update_geo());
    on!(on_check_updates, |s| { let a = s.app.clone(); std::thread::spawn(move || a.check_updates(true)); });
    on!(on_install_update, |s| s.app.install_update());
    on!(on_update_in_background, |s| s.app.update_in_background());
    on!(on_dismiss_update, |s| { *s.app.update.lock().unwrap() = None; s.app.bump(); });
    on!(on_relaunch_admin, |s| {
        s.app.flags.need_admin.store(false, Relaxed);
        if win::relaunch_as_admin("--connect") { s.quit(); } else { s.app.notify(t!("Administrator launch cancelled"), None); }
    });
    on!(on_install_amnezia, |s| { s.app.flags.need_amnezia.store(false, Relaxed); s.app.bump(); win::open_url("https://github.com/amnezia-vpn/amneziawg-windows-client/releases/latest"); });

    // ---- logs
    on!(on_clear_logs, |s| { s.app.core.clear_logs(); s.app.bump(); });
    on!(on_copy_logs, |s| {
        let text = s.app.core.logs.lock().unwrap().iter().map(|l| format!("{} {}", l.level, l.text)).collect::<Vec<_>>().join("\n");
        win::set_clipboard(&text); s.app.notify(t!("Copied"), None);
    });
    on!(on_set_log_filter, |s, i| { s.view.borrow_mut().log_filter = i; s.app.bump(); });

    // ---- home widgets
    on!(on_widget_move, |s, id, x, y| s.app.edit(|st| layout::place(&mut st.settings.home_widgets, &id, Some((x, y)), None)));
    on!(on_widget_resize, |s, id, w, h| s.app.edit(|st| layout::place(&mut st.settings.home_widgets, &id, None, Some((w, h)))));
    on!(on_widget_add, |s, kind| s.app.edit(|st| layout::add(&mut st.settings.home_widgets, &kind)));
    on!(on_widget_remove, |s, id| s.app.edit(|st| st.settings.home_widgets.retain(|w| w.id != id.as_str())));
    on!(on_widgets_reset, |s| s.app.edit(|st| st.settings.home_widgets = model::default_home_widgets()));
    on!(on_refresh_ip, |s| { let a = s.app.clone(); std::thread::spawn(move || a.refresh_ip()); });
    on!(on_check_sites, |s| s.app.check_sites());
    on!(on_set_sites, |s, text| { s.app.set_sites(text.lines().map(str::to_string).collect()); s.app.check_sites(); });
    on!(on_run_speed_test, |s| s.app.run_speed_test(None));

    // ---- misc
    on!(on_message_action, |s| {
        let m = s.msg.borrow_mut().take();
        s.set_msg(None);
        if let Some((m, _)) = m { if let Some((_, a)) = m.action { s.app.run_action(a); } }
    });
    on!(on_dismiss, |s, which| {
        let ui = s.w.global::<Ui>();
        match which.as_str() {
            "message" => { s.msg.borrow_mut().take(); s.set_msg(None); }
            "error" => { s.app.flags.show_error.store(false, Relaxed); s.app.bump(); }
            "admin" => { s.app.flags.need_admin.store(false, Relaxed); s.app.bump(); }
            "amnezia" => { s.app.flags.need_amnezia.store(false, Relaxed); s.app.bump(); }
            "whats-new" => { *s.app.whats_new.lock().unwrap() = None; s.app.bump(); }
            "qr" => ui.set_show_qr(false),
            "editor" => { ui.set_show_editor(false); s.view.borrow_mut().editing = None; }
            _ => {}
        }
    });

    // ---- mini window
    let ml = sh.mini.global::<Logic>();
    { let a = sh.app.clone(); ml.on_toggle_connect(move || a.toggle()); }
    { let weak = Rc::downgrade(sh); ml.on_toggle_mini(move || if let Some(s) = weak.upgrade() { s.show_main(); }); }
    { let weak = sh.mini.as_weak(); ml.on_drag(move || if let Some(m) = weak.upgrade() { m.window().with_winit_window(|w| { let _ = w.drag_window(); }); }); }
    let _ = Action::Reconnect;
}

/// Dashboard grid: 12 columns, widgets never overlap — whatever is in the way moves down.
pub(crate) use layout as layout_for_tests;
mod layout {
    use crate::model::HomeWidget;

    fn overlap(a: &HomeWidget, b: &HomeWidget) -> bool { a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h }

    fn settle(ws: &mut Vec<HomeWidget>, fixed: &str) {
        // the moved widget keeps its place; others are pushed down in reading order
        ws.sort_by_key(|w| (w.id != fixed, w.y, w.x));
        for i in 1..ws.len() {
            loop {
                let hit = (0..i).any(|j| overlap(&ws[i], &ws[j]));
                if !hit { break; }
                ws[i].y += 1;
            }
        }
        // compact upwards so no holes stay behind
        let mut order: Vec<usize> = (0..ws.len()).collect();
        order.sort_by_key(|&i| (ws[i].y, ws[i].x));
        for &i in &order {
            while ws[i].y > 0 {
                let mut t = ws[i].clone(); t.y -= 1;
                if (0..ws.len()).any(|j| j != i && overlap(&t, &ws[j])) { break; }
                ws[i].y -= 1;
            }
        }
    }

    pub fn place(ws: &mut Vec<HomeWidget>, id: &str, pos: Option<(i32, i32)>, size: Option<(i32, i32)>) {
        let Some(w) = ws.iter_mut().find(|w| w.id == id) else { return };
        if let Some((w2, h2)) = size { w.w = w2.clamp(2, 12); w.h = h2.clamp(2, 10); }
        if let Some((x, y)) = pos { w.x = x; w.y = y.max(0); }
        w.x = w.x.clamp(0, 12 - w.w);
        settle(ws, id);
    }

    pub fn add(ws: &mut Vec<HomeWidget>, kind: &str) {
        let (w, h) = match kind { "CONNECT" => (6, 6), "SERVERS" | "SITES" | "USAGE" | "SPEEDTEST" => (4, 4), "MODES" => (6, 2), "SPEED" => (5, 3), _ => (3, 2) };
        let bottom = ws.iter().map(|x| x.y + x.h).max().unwrap_or(0);
        let nw = HomeWidget { kind: kind.into(), x: 0, y: bottom, w, h, ..Default::default() };
        let id = nw.id.clone();
        ws.push(nw);
        settle(ws, &id);
    }
}
