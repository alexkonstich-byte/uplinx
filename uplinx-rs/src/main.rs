// Uplinx Client — Rust/Slint port (phase 1: shell, theme, state).
#![windows_subsystem = "windows"]

mod state;
mod theme;

use slint::ComponentHandle;

slint::include_modules!();

fn main() -> anyhow::Result<()> {
    let app = AppWindow::new()?;
    let st = state::load();

    theme::apply(&app, &st);
    if let Some(node) = st.selected_node() {
        app.set_server_name(node.name.clone().into());
        app.set_server_detail(format!("{}:{} · {}", node.address, node.port, node.protocol).into());
    } else {
        app.set_server_name("No server yet".into());
    }

    let logic = app.global::<Logic>();
    {
        let weak = app.as_weak();
        logic.on_toggle_connect(move || {
            // phase 1: visual only — the Xray core is wired in the next step
            if let Some(app) = weak.upgrade() {
                let next = if app.get_status() == 2 { 0 } else { 2 };
                app.set_status(next);
            }
        });
    }
    {
        let weak = app.as_weak();
        logic.on_minimize(move || {
            if let Some(app) = weak.upgrade() { app.window().set_minimized(true); }
        });
    }
    {
        let weak = app.as_weak();
        logic.on_toggle_maximize(move || {
            if let Some(app) = weak.upgrade() {
                let m = !app.window().is_maximized();
                app.window().set_maximized(m);
                app.set_maximized(m);
            }
        });
    }
    {
        let weak = app.as_weak();
        logic.on_close(move || {
            if let Some(app) = weak.upgrade() { let _ = app.hide(); }
        });
    }
    {
        let weak = app.as_weak();
        logic.on_drag(move || {
            use slint::winit_030::WinitWindowAccessor;
            if let Some(app) = weak.upgrade() {
                app.window().with_winit_window(|w| { let _ = w.drag_window(); });
            }
        });
    }

    app.run()?;
    Ok(())
}
