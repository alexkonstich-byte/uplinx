//! Material You colours: seed from the Windows accent (or the user's colour), scheme by material-colors.

use crate::model::AppSettings;
use crate::Theme;
#[allow(unused_imports)]
use material_colors::{color::Rgb, theme::ThemeBuilder};
use slint::{Color, ComponentHandle};
use winreg::{enums::HKEY_CURRENT_USER, RegKey};

fn accent() -> Option<u32> {
    let k = RegKey::predef(HKEY_CURRENT_USER).open_subkey("Software\\Microsoft\\Windows\\DWM").ok()?;
    let abgr: u32 = k.get_value("AccentColor").ok()?;
    let (r, g, b) = (abgr & 0xFF, (abgr >> 8) & 0xFF, (abgr >> 16) & 0xFF);
    Some((r << 16) | (g << 8) | b)
}

pub fn system_dark() -> bool {
    RegKey::predef(HKEY_CURRENT_USER)
        .open_subkey("Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize").ok()
        .and_then(|k| k.get_value::<u32, _>("AppsUseLightTheme").ok())
        .map(|v| v == 0)
        .unwrap_or(true)
}

fn c(rgb: Rgb) -> Color { Color::from_rgb_u8(rgb.red, rgb.green, rgb.blue) }

pub fn is_dark(s: &AppSettings) -> bool { match s.theme_mode.as_str() { "LIGHT" => false, "DARK" => true, _ => system_dark() } }

pub fn apply(w: &crate::AppWindow, mini: &crate::MiniWindow, s: &AppSettings) {
    let seed = if s.color_source == "WINDOWS_ACCENT" { accent() } else { None }.unwrap_or((s.seed_color as u32) & 0xFFFFFF);
    let dark = is_dark(s);
    let theme = ThemeBuilder::with_source(Rgb::from_u32(seed)).build();
    let sc = if dark { &theme.schemes.dark } else { &theme.schemes.light };
    macro_rules! paint { ($app:expr) => {{ let app = $app;
    let t = app.global::<Theme>();
    t.set_dark(dark);
    t.set_primary(c(sc.primary));
    t.set_on_primary(c(sc.on_primary));
    t.set_primary_container(c(sc.primary_container));
    t.set_on_primary_container(c(sc.on_primary_container));
    t.set_secondary(c(sc.secondary));
    t.set_secondary_container(c(sc.secondary_container));
    t.set_on_secondary_container(c(sc.on_secondary_container));
    t.set_tertiary(c(sc.tertiary));
    t.set_tertiary_container(c(sc.tertiary_container));
    t.set_on_tertiary_container(c(sc.on_tertiary_container));
    t.set_surface(c(sc.surface));
    t.set_surface_container_low(c(sc.surface_container_low));
    t.set_surface_container(c(sc.surface_container));
    t.set_surface_container_high(c(sc.surface_container_high));
    t.set_surface_container_highest(c(sc.surface_container_highest));
    t.set_on_surface(c(sc.on_surface));
    t.set_on_surface_variant(c(sc.on_surface_variant));
    t.set_outline(c(sc.outline));
    t.set_outline_variant(c(sc.outline_variant));
    t.set_error(c(sc.error));
    t.set_error_container(c(sc.error_container));
    t.set_on_error_container(c(sc.on_error_container));
    t.set_inverse_surface(c(sc.inverse_surface));
    t.set_inverse_on_surface(c(sc.inverse_on_surface));
    if dark { t.set_good(Color::from_rgb_u8(0x4f, 0xd1, 0x8b)); t.set_warn(Color::from_rgb_u8(0xf2, 0xb8, 0x4b)); }
    else { t.set_good(Color::from_rgb_u8(0x1b, 0x8a, 0x4c)); t.set_warn(Color::from_rgb_u8(0xa8, 0x6a, 0x00)); }
    }}; }
    paint!(w);
    paint!(mini);
}
