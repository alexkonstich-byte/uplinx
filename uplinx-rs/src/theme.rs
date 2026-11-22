//! Material You colours: seed from the Windows accent (or the user's seed), scheme by material-colors.

use crate::{model::AppState, AppWindow, Theme};
use material_colors::{color::Rgb, theme::ThemeBuilder};
use slint::{Color, ComponentHandle};
use winreg::{enums::HKEY_CURRENT_USER, RegKey};

fn accent() -> Option<u32> {
    let k = RegKey::predef(HKEY_CURRENT_USER).open_subkey("Software\\Microsoft\\Windows\\DWM").ok()?;
    let abgr: u32 = k.get_value("AccentColor").ok()?;
    let (r, g, b) = (abgr & 0xFF, (abgr >> 8) & 0xFF, (abgr >> 16) & 0xFF);
    Some((r << 16) | (g << 8) | b)
}

fn system_dark() -> bool {
    RegKey::predef(HKEY_CURRENT_USER)
        .open_subkey("Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize").ok()
        .and_then(|k| k.get_value::<u32, _>("AppsUseLightTheme").ok())
        .map(|v| v == 0)
        .unwrap_or(true)
}

fn c(rgb: Rgb) -> Color { Color::from_rgb_u8(rgb.red, rgb.green, rgb.blue) }

pub fn apply(app: &AppWindow, st: &AppState) {
    let seed = if st.settings.color_source == "WINDOWS_ACCENT" { accent() } else { None }
        .unwrap_or((st.settings.seed_color as u32) & 0xFFFFFF);
    let dark = match st.settings.theme_mode.as_str() { "LIGHT" => false, "DARK" => true, _ => system_dark() };
    let theme = ThemeBuilder::with_source(Rgb::from_u32(seed)).build();
    let s = if dark { &theme.schemes.dark } else { &theme.schemes.light };
    let t = app.global::<Theme>();
    t.set_primary(c(s.primary));
    t.set_on_primary(c(s.on_primary));
    t.set_primary_container(c(s.primary_container));
    t.set_on_primary_container(c(s.on_primary_container));
    t.set_secondary_container(c(s.secondary_container));
    t.set_on_secondary_container(c(s.on_secondary_container));
    t.set_tertiary(c(s.tertiary));
    t.set_surface(c(s.surface));
    t.set_surface_container(c(s.surface_container));
    t.set_surface_container_high(c(s.surface_container_high));
    t.set_surface_container_highest(c(s.surface_container_highest));
    t.set_on_surface(c(s.on_surface));
    t.set_on_surface_variant(c(s.on_surface_variant));
    t.set_outline_variant(c(s.outline_variant));
    t.set_error(c(s.error));
}
