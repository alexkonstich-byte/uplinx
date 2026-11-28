// Uplinx — a Material You proxy client for Windows powered by Xray-core.
#![windows_subsystem = "windows"]

mod app;
mod core;
mod countries;
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
    /// Clipboard text offered for import, and the last text already looked at (never offered twice).
    clip: RefCell<Option<String>>,
    clip_seen: RefCell<String>,
    /// Files dropped on the window, imported together.
    drops: RefCell<Vec<std::path::PathBuf>>,
    page: std::cell::Cell<i32>,
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
        self.save_geometry();
        let _ = self.w.hide();
        self.hidden.set(true);
        win::trim_memory();
    }

    fn quit(&self) {
        self.save_geometry();
        self.app.shutdown();
        win::tray_remove();
        let _ = slint::quit_event_loop();
    }

    fn tray_menu(&self) -> win::TrayMenu {
        let st = self.app.snapshot();
        let pings = self.app.pings.lock().unwrap().clone();
        let sel = st.selected_node().map(|n| n.id.clone()).unwrap_or_default();
        let mut nodes: Vec<&model::ProxyNode> = st.nodes.iter().filter(|n| !st.hidden.contains(&n.identity())).collect();
        let ping = |n: &model::ProxyNode| pings.get(&n.id).copied().filter(|&p| p >= 0).unwrap_or(i32::MAX);
        nodes.sort_by_key(|n| (!st.settings.favorites.contains(&n.identity()), ping(n)));
        let servers = nodes.iter().take(25).map(|n| {
            let p = ping(n);
            let name = view::split_flag(&n.name).1;
            (n.id.clone(), if p < i32::MAX { format!("{name}\t{p} ms") } else { name }, n.id == sel)
        }).collect();
        win::TrayMenu {
            connected: self.app.is_connected(), servers, connect_mode: st.settings.connect_mode.clone(), routing_mode: st.routing.mode.clone(),
            labels: vec![t!("Show"), if self.app.is_connected() { t!("Disconnect") } else { t!("Connect") }, t!("Connect to the fastest"),
                t!("Servers"), t!("Mode"), t!("System proxy"), "TUN".into(), t!("By rules"), t!("Global"), t!("Direct"), t!("Quit")],
        }
    }

    /// Remembers where the windows were, so they open there next time.
    fn save_geometry(&self) {
        let win = self.w.window();
        let mut rect = None;
        let maximized = win.is_maximized();
        if win.is_visible() && !maximized && !win.is_minimized() {
            let (p, z) = (win.position(), win.size());
            rect = Some(vec![p.x, p.y, z.width as i32, z.height as i32]);
        }
        let mini = self.mini.window().is_visible().then(|| { let p = self.mini.window().position(); vec![p.x, p.y] });
        let st = self.app.settings();
        let changed = rect.as_ref().is_some_and(|r| *r != st.window_rect) || (win.is_visible() && maximized != st.window_maximized)
            || mini.as_ref().is_some_and(|m| *m != st.mini_pos);
        if changed {
            self.app.edit(|s| {
                if let Some(r) = rect { s.settings.window_rect = r; }
                if win.is_visible() { s.settings.window_maximized = maximized; }
                if let Some(m) = mini { s.settings.mini_pos = m; }
            });
        }
    }

    fn restore_geometry(&self) {
        let st = self.app.settings();
        let (vx, vy, vw, vh) = win::virtual_screen();
        let on_screen = |x: i32, y: i32| x > vx - 100 && y >= vy - 10 && x < vx + vw - 100 && y < vy + vh - 60;
        if let [x, y, w, h] = st.window_rect[..] {
            if on_screen(x, y) && w >= 600 && h >= 400 {
                self.w.window().set_size(slint::PhysicalSize::new(w as u32, h as u32));
                self.w.window().set_position(slint::PhysicalPosition::new(x, y));
            }
        }
        if let [x, y] = st.mini_pos[..] { if on_screen(x, y) { self.mini.window().set_position(slint::PhysicalPosition::new(x, y)); } }
    }

    /// When the window gets focus: offer what's new in the clipboard (once per clipboard content).
    fn check_clipboard(&self) {
        if !self.app.settings().clipboard_offer { return; }
        let Some(text) = win::clipboard_text() else { return };
        if *self.clip_seen.borrow() == text { return; }
        *self.clip_seen.borrow_mut() = text.clone();
        let offer = self.app.clipboard_offer(&text);
        self.w.global::<Ui>().set_clip_offer(offer.clone().unwrap_or_default().into());
        *self.clip.borrow_mut() = offer.map(|_| text);
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
            win::tray_update(&tip);
        }
        let page = self.w.global::<Nav>().get_page();
        if page != self.page.get() { self.page.set(page); self.app.edit(|s| s.settings.last_page = page); }
        // snackbar: one message at a time, 5 s each
        let mut cur = self.msg.borrow_mut();
        if cur.as_ref().is_some_and(|(_, t)| t.elapsed() > Duration::from_secs(5)) { *cur = None; self.set_msg(None); }
        if cur.is_none() {
            if let Some(m) = self.app.messages.lock().unwrap().pop_front() { self.set_msg(Some(&m)); *cur = Some((m, Instant::now())); }
        }
    }

    /// Redraw now instead of on the next tick (the UI is waiting for the result of its own action).
    fn redraw(&self) {
        self.view.borrow_mut().refresh(&self.w, &self.app);
        self.last_rev.set(self.app.rev.load(Relaxed));
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
        last_rev: Default::default(), hidden: Default::default(), clip: Default::default(), clip_seen: Default::default(),
        drops: Default::default(), page: std::cell::Cell::new(st.settings.last_page) });
    w.global::<Nav>().set_page(st.settings.last_page.clamp(0, 5));
    sh.restore_geometry();

    wire(&sh);

    // tray + notifications
    {
        let s = Rc::downgrade(&sh);
        let m = Rc::downgrade(&sh);
        win::tray_create("Uplinx", move || m.upgrade().map(|s| s.tray_menu()).unwrap_or(win::TrayMenu {
            connected: false, servers: vec![], connect_mode: String::new(), routing_mode: String::new(), labels: vec![] }), move |e| {
            let Some(s) = s.upgrade() else { return };
            match e {
                win::TrayEvent::Show => s.show_main(),
                win::TrayEvent::Toggle => s.app.toggle(),
                win::TrayEvent::Quit => s.quit(),
                win::TrayEvent::Fastest => s.app.connect_fastest(),
                win::TrayEvent::Select(id) => s.app.select(&id),
                win::TrayEvent::ConnectMode(m) => s.app.set_connect_mode(m),
                win::TrayEvent::RoutingMode(m) => s.app.set_routing_mode(m),
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
        let s = Rc::downgrade(&sh);
        w.window().on_winit_window_event(move |_, e| {
            let Some(s) = s.upgrade() else { return EventResult::Propagate };
            let ui = s.w.global::<Ui>();
            match e {
                WindowEvent::Focused(f) => {
                    s.w.global::<Theme>().set_motion(*f);
                    if *f { s.check_clipboard(); }
                }
                // files dragged in from Explorer: configs, subscriptions in text files, QR pictures
                WindowEvent::HoveredFile(_) => ui.set_drop_hover(true),
                WindowEvent::HoveredFileCancelled => ui.set_drop_hover(false),
                WindowEvent::DroppedFile(path) => {
                    ui.set_drop_hover(false);
                    s.drops.borrow_mut().push(path.clone());
                    let weak = Rc::downgrade(&s);
                    slint::Timer::single_shot(Duration::from_millis(150), move || {
                        let Some(s) = weak.upgrade() else { return };
                        let files: Vec<_> = s.drops.borrow_mut().drain(..).collect();
                        if !files.is_empty() { let a = s.app.clone(); std::thread::spawn(move || a.import_files(files)); }
                    });
                }
                _ => {}
            }
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

    if let Some(p) = args.iter().find_map(|a| a.strip_prefix("--page=")).and_then(|p| p.parse::<i32>().ok()) { w.global::<Nav>().set_page(p.clamp(0, 5)); }
    // for screenshots of dialogs: --dialog=<name>[:<argument>]
    if let Some(d) = args.iter().find_map(|a| a.strip_prefix("--dialog=")) {
        let (name, arg) = d.split_once(':').unwrap_or((d, ""));
        if name == "processes" { w.global::<Logic>().invoke_refresh_processes(); }
        w.global::<Nav>().set_b(arg.into());
        w.global::<Nav>().set_dialog(name.into());
    }
    // nothing connects before the first-start notice was accepted
    if st.settings.disclaimer_accepted && (args.iter().any(|a| a == "--connect") || st.settings.auto_connect) { app.connect(); }
    if args.iter().any(|a| a == "--minimized") { sh.hidden.set(true); } else {
        w.show()?;
        if st.settings.window_maximized { w.window().set_maximized(true); w.set_is_maximized(true); }
        if st.settings.disclaimer_accepted { sh.check_clipboard(); } // opened right after copying a link: offer it at once
    }
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
    on!(on_toggle_mini, |s| { s.hide_to_tray(); s.restore_geometry(); let _ = s.mini.show(); s.last_rev.set(0); });
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
    on!(on_set_sort, |s, i| s.app.update_settings(|x| x.server_sort = ["MANUAL", "NAME", "PING", "RECENT", "SPEED", "COUNTRY"][i.clamp(0, 5) as usize].into()));
    on!(on_toggle_group, |s, id| s.app.toggle_group(&id));
    on!(on_connect_fastest, |s| s.app.connect_fastest());
    on!(on_clip_accept, |s| {
        if let Some(text) = s.clip.borrow_mut().take() { s.app.import_text(&text, None); }
        s.w.global::<Ui>().set_clip_offer("".into());
    });
    on!(on_clip_dismiss, |s| { s.clip.borrow_mut().take(); s.w.global::<Ui>().set_clip_offer("".into()); });
    on!(on_route_check, |s, text| {
        let ui = s.w.global::<Ui>();
        ui.set_route_checking(true);
        let (a, weak, text) = (s.app.clone(), s.w.as_weak(), text.to_string());
        std::thread::spawn(move || {
            let v = a.route_check(&text); // may resolve the name: off the UI thread
            let _ = weak.upgrade_in_event_loop(move |w| {
                let ui = w.global::<Ui>();
                ui.set_route_action(v.action.into());
                ui.set_route_reason(v.reason.into());
                ui.set_route_checking(false);
            });
        });
    });
    on!(on_export_logs, |s| {
        let text = s.app.core.logs.lock().unwrap().iter().map(|l| format!("{} {}", l.level, l.text)).collect::<Vec<_>>().join("\r\n");
        let (a, title) = (s.app.clone(), t!("Save logs"));
        std::thread::spawn(move || if let Some(p) = win::save_file(&title, "uplinx-log.txt") {
            match std::fs::write(&p, text) { Ok(()) => a.notify(t!("Saved"), None), Err(e) => a.notify(t!("Error: %1", e), None) }
        });
    });
    on!(on_set_log_search, |s, q| { s.view.borrow_mut().log_search = q.to_string(); s.app.bump(); });
    on!(on_set_logs_paused, |s, v| { s.view.borrow_mut().logs_paused = v; s.w.global::<Ui>().set_logs_paused(v); s.app.bump(); });
    on!(on_node_menu, |s, id, action| {
        let node = s.app.snapshot().nodes.into_iter().find(|n| n.id == id.as_str());
        match action.as_str() {
            "copy" => s.app.copy_link(&id),
            "fav" | "unfav" => s.app.toggle_favorite(&id),
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
        k => s.app.update_routing(|r| match k { "preset-streaming" => r.preset_streaming = v, "preset-ai" => r.preset_ai = v,
            "preset-social" => r.preset_social = v, "preset-games" => r.preset_games_direct = v, "preset-torrents" => r.preset_block_torrents = v,
            "bypass-lan" => r.bypass_lan = v, "bypass-ru" => r.bypass_ru = v, "block-ads" => r.block_ads = v, "proxy-blocked-ru" => r.proxy_blocked_ru = v, _ => {} }),
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
        let progs = win::running_programs();
        // remember where programs live, so their icons show even when they are not running
        let mut paths = s.app.settings().app_paths;
        let before = paths.len();
        for (name, path) in &progs { paths.insert(name.to_lowercase(), path.clone()); }
        if paths.len() != before { let p = paths.clone(); s.app.edit(|st| st.settings.app_paths = p); }
        let items: Vec<AppItem> = progs.iter().map(|(name, _)| s.view.borrow_mut().app_item(name, &paths)).collect();
        s.w.global::<Ui>().set_processes(slint::ModelRc::new(slint::VecModel::from(items)));
    });
    on!(on_rule_from_app, |s, name, action| {
        let rule = model::RouteRule { kind: "PROCESS".into(), value: name.to_string(), action: action.to_string(), ..Default::default() };
        s.app.upsert_rule(rule);
        s.app.notify(t!("Rule added: %1", name), None);
    });
    on!(on_speed_all, |s| s.app.speed_all());
    on!(on_speed_all_stop, |s| s.app.speed_all_stop());

    // ---- settings
    on!(on_set_bool, |s, key, v| match key.as_str() {
        "autostart" => { win::set_autostart(v); s.app.bump(); s.view.borrow_mut().refresh(&s.w, &s.app); }
        k => s.app.update_settings(|x| match k {
            "allow-lan" => x.allow_lan = v, "sniffing" => x.sniffing = v, "mux" => x.mux = v, "ipv6" => x.ipv6 = v, "tun-strict-dns" => x.tun_strict_dns = v,
            "apply-panel-routing" => x.apply_panel_routing = v, "minimize-to-tray" => x.minimize_to_tray = v, "auto-connect" => x.auto_connect = v,
            "check-updates" => x.check_updates = v, "notify-connection" => x.notify_connection = v, "notify-errors" => x.notify_errors = v,
            "notify-subscription" => x.notify_subscription = v, "notify-updates" => x.notify_updates = v, "mask-addresses" => x.mask_addresses = v,
            "show-hidden" => x.show_hidden = v, "clipboard-offer" => x.clipboard_offer = v, "auto-ping" => x.auto_ping = v,
            "auto-reconnect" => x.auto_reconnect = v, "kill-switch" => x.kill_switch = v, _ => {}
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
            "engine" => {
                s.app.update_settings(|x| x.core = if i == 1 { "SINGBOX".into() } else { "XRAY".into() });
                if i == 1 && !core::singbox::installed() { s.app.update_singbox(); } // fetch it now, not on connect
            }
            _ => {}
        }
        if key.as_str() == "theme-mode" || key.as_str() == "color-source" { let st = s.app.settings(); theme::apply(&s.w, &s.mini, &st); }
    });
    // returns false when the value is rejected: the field then shows the stored value again
    on!(on_set_text, |s, key, v| {
        let v = v.trim().to_string();
        let reject = |msg: String| { s.app.notify(msg, None); false };
        match key.as_str() {
            "socks-port" | "http-port" => match v.parse::<u16>() {
                Ok(p) if p > 0 => {
                    let st = s.app.settings();
                    let other = if key.as_str() == "socks-port" { st.http_port } else { st.socks_port };
                    if p == other || p == st.api_port { return reject(t!("This port is already used by another Uplinx port")); }
                    s.app.update_settings(|x| if key.as_str() == "socks-port" { x.socks_port = p } else { x.http_port = p });
                    true
                }
                _ => reject(t!("Ports must be numbers from 1 to 65535")),
            },
            "remote-dns" | "direct-dns" if !core::xray::valid_dns(&v) =>
                reject(t!("Enter a DNS server: an IP address (77.88.8.8, [2a02:6b8::feed:ff]:53) or a URL (https://dns.example/dns-query)")),
            "remote-dns" => { s.app.update_settings(|x| x.remote_dns = v); true }
            "direct-dns" => { s.app.update_settings(|x| x.direct_dns = v); true }
            "ping-url" if !(v.starts_with("http://") || v.starts_with("https://")) || v.contains(char::is_whitespace) =>
                reject(t!("The ping URL must start with http:// or https://")),
            "ping-url" => { s.app.update_settings(|x| x.ping_url = v); true }
            "user-agent" => { s.app.update_settings(|x| x.user_agent = v); true }
            _ => false,
        }
    });
    on!(on_set_seed, |s, c| {
        s.app.update_settings(|x| { x.seed_color = 0xFF00_0000 | ((c.red() as i64) << 16) | ((c.green() as i64) << 8) | c.blue() as i64; x.color_source = "CUSTOM".into(); });
        let st = s.app.settings(); theme::apply(&s.w, &s.mini, &st);
    });
    on!(on_update_core, |s| s.app.update_core());
    on!(on_update_singbox, |s| s.app.update_singbox());
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
    // the grid is redrawn right away (not on the next tick): a dropped tile springs straight into its new cell
    on!(on_widget_move, |s, id, x, y| { s.app.edit(|st| layout::place(&mut st.settings.home_widgets, &id, Some((x, y)), None)); s.redraw(); });
    on!(on_widget_resize, |s, id, w, h| { s.app.edit(|st| layout::place(&mut st.settings.home_widgets, &id, None, Some((w, h)))); s.redraw(); });
    on!(on_widget_add, |s, kind| { s.app.edit(|st| layout::add(&mut st.settings.home_widgets, &kind)); s.redraw(); });
    on!(on_widget_remove, |s, id| {
        let before = s.app.settings().home_widgets;
        s.app.edit(|st| { st.settings.home_widgets.retain(|w| w.id != id.as_str()); layout::compact(&mut st.settings.home_widgets); });
        s.app.notify(t!("Widget removed"), Some((t!("Undo"), Action::RestoreWidgets(before))));
        s.redraw();
    });
    on!(on_widgets_reset, |s| {
        let before = s.app.settings().home_widgets;
        s.app.edit(|st| st.settings.home_widgets = model::default_home_widgets());
        s.app.notify(t!("Layout reset"), Some((t!("Undo"), Action::RestoreWidgets(before))));
        s.redraw();
    });
    on!(on_refresh_ip, |s| { let a = s.app.clone(); std::thread::spawn(move || a.refresh_ip()); });
    on!(on_check_sites, |s| s.app.check_sites());
    on!(on_set_sites, |s, text| { s.app.set_sites(text.lines().map(str::to_string).collect()); s.app.check_sites(); });
    on!(on_run_speed_test, |s| s.app.run_speed_test(None));
    on!(on_accept_disclaimer, |s| {
        s.app.edit(|st| st.settings.disclaimer_accepted = true);
        s.check_clipboard();
    });
    on!(on_quit, |s| s.quit());

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
            "all" => {
                for f in [&s.app.flags.show_error, &s.app.flags.need_admin, &s.app.flags.need_amnezia] { f.store(false, Relaxed); }
                *s.app.whats_new.lock().unwrap() = None;
                ui.set_show_qr(false); ui.set_show_editor(false); ui.set_clip_offer("".into());
                s.msg.borrow_mut().take(); s.set_msg(None);
                s.app.bump();
            }
            _ => {}
        }
    });

    // ---- mini window
    let ml = sh.mini.global::<Logic>();
    { let a = sh.app.clone(); ml.on_toggle_connect(move || a.toggle()); }
    { let weak = Rc::downgrade(sh); ml.on_toggle_mini(move || if let Some(s) = weak.upgrade() { s.save_geometry(); s.show_main(); }); }
    { let weak = sh.mini.as_weak(); ml.on_drag(move || if let Some(m) = weak.upgrade() { m.window().with_winit_window(|w| { let _ = w.drag_window(); }); }); }
    let _ = Action::Reconnect;
}

/// Dashboard grid: 12 columns, widgets never overlap — whatever is in the way moves down.
pub(crate) use layout as layout_for_tests;
mod layout {
    use crate::model::HomeWidget;

    fn overlap(a: &HomeWidget, b: &HomeWidget) -> bool { a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h }

    /// The smallest size (columns, rows) at which a widget's content still fits.
    pub fn min_size(kind: &str) -> (i32, i32) {
        match kind {
            "CONNECT" => (3, 4),
            "MODES" => (4, 2),
            "SERVERS" | "SITES" | "USAGE" | "SPEEDTEST" | "SPEEDOMETER" => (3, 3),
            "SERVER" | "SPEED" | "QUOTA" => (3, 2),
            _ => (2, 2),
        }
    }

    /// Keeps every widget inside the grid and at least its minimum size (also repairs older saved layouts).
    fn fit(w: &mut HomeWidget) {
        let (mw, mh) = min_size(&w.kind);
        w.w = w.w.clamp(mw, 12);
        w.h = w.h.clamp(mh, 10);
        w.x = w.x.clamp(0, 12 - w.w);
        w.y = w.y.max(0);
    }

    /// Closes the holes a removed widget leaves.
    pub fn compact(ws: &mut Vec<HomeWidget>) {
        if let Some(first) = ws.first().map(|w| w.id.clone()) { settle(ws, &first); }
    }

    fn settle(ws: &mut Vec<HomeWidget>, fixed: &str) {
        for w in ws.iter_mut() { fit(w); }
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
        if let Some((w2, h2)) = size { w.w = w2; w.h = h2; }
        if let Some((x, y)) = pos { w.x = x; w.y = y; }
        fit(w);
        settle(ws, id);
    }

    pub fn add(ws: &mut Vec<HomeWidget>, kind: &str) {
        let (w, h) = match kind { "CONNECT" => (6, 6), "SERVERS" | "SITES" | "USAGE" | "SPEEDTEST" | "SPEEDOMETER" => (4, 4), "MODES" => (6, 2), "SPEED" => (5, 3), _ => (3, 2) };
        let (mw, mh) = min_size(kind);
        let (w, h) = (w.max(mw), h.max(mh));
        let bottom = ws.iter().map(|x| x.y + x.h).max().unwrap_or(0);
        let nw = HomeWidget { kind: kind.into(), x: 0, y: bottom, w, h, ..Default::default() };
        let id = nw.id.clone();
        ws.push(nw);
        settle(ws, &id);
    }
}
