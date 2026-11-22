//! QR codes from the screen (all monitors), a clipboard image or an image file — the desktop "camera".

use std::path::Path;

fn decode_luma(w: usize, h: usize, luma: impl Fn(usize, usize) -> u8) -> Vec<String> {
    let mut img = rqrr::PreparedImage::prepare_from_greyscale(w, h, |x, y| luma(x, y));
    let mut out: Vec<String> = img.detect_grids().into_iter().filter_map(|g| g.decode().ok().map(|(_, s)| s)).collect();
    out.dedup();
    out
}

fn decode_rgba(w: usize, h: usize, px: &[u8]) -> Vec<String> {
    decode_luma(w, h, |x, y| { let i = (y * w + x) * 4; ((px[i] as u32 * 299 + px[i + 1] as u32 * 587 + px[i + 2] as u32 * 114) / 1000) as u8 })
}

pub fn is_image(p: &Path) -> bool {
    matches!(p.extension().map(|e| e.to_string_lossy().to_lowercase()).as_deref(), Some("png" | "jpg" | "jpeg" | "bmp" | "gif" | "webp"))
}

pub fn from_file(p: &Path) -> Vec<String> {
    match image::open(p) { Ok(i) => { let g = i.to_luma8(); decode_luma(g.width() as usize, g.height() as usize, |x, y| g.get_pixel(x as u32, y as u32).0[0]) } Err(_) => vec![] }
}

pub fn from_clipboard() -> Vec<String> {
    let Ok(mut c) = arboard::Clipboard::new() else { return vec![] };
    match c.get_image() { Ok(img) => decode_rgba(img.width, img.height, &img.bytes), Err(_) => vec![] }
}

/// Grabs the whole virtual screen with GDI and looks for codes.
pub fn from_screen() -> Vec<String> {
    use windows_sys::Win32::Graphics::Gdi::*;
    use windows_sys::Win32::UI::WindowsAndMessaging::*;
    unsafe {
        let (x, y) = (GetSystemMetrics(SM_XVIRTUALSCREEN), GetSystemMetrics(SM_YVIRTUALSCREEN));
        let (w, h) = (GetSystemMetrics(SM_CXVIRTUALSCREEN), GetSystemMetrics(SM_CYVIRTUALSCREEN));
        if w <= 0 || h <= 0 { return vec![]; }
        let screen = GetDC(std::ptr::null_mut());
        let mem = CreateCompatibleDC(screen);
        let bmp = CreateCompatibleBitmap(screen, w, h);
        let old = SelectObject(mem, bmp);
        BitBlt(mem, 0, 0, w, h, screen, x, y, SRCCOPY);
        let mut bi: BITMAPINFO = std::mem::zeroed();
        bi.bmiHeader.biSize = std::mem::size_of::<BITMAPINFOHEADER>() as u32;
        bi.bmiHeader.biWidth = w;
        bi.bmiHeader.biHeight = -h; // top-down
        bi.bmiHeader.biPlanes = 1;
        bi.bmiHeader.biBitCount = 32;
        let mut px = vec![0u8; (w * h * 4) as usize];
        GetDIBits(mem, bmp, 0, h as u32, px.as_mut_ptr().cast(), &mut bi, DIB_RGB_COLORS);
        SelectObject(mem, old);
        DeleteObject(bmp);
        DeleteDC(mem);
        ReleaseDC(std::ptr::null_mut(), screen);
        let (w, h) = (w as usize, h as usize);
        // BGRA
        decode_luma(w, h, |x, y| { let i = (y * w + x) * 4; ((px[i + 2] as u32 * 299 + px[i + 1] as u32 * 587 + px[i] as u32 * 114) / 1000) as u8 })
    }
}

/// QR image (as RGBA pixels + size) for sharing a server link.
pub fn encode(text: &str) -> Option<(Vec<u8>, u32)> {
    let code = qrcode::QrCode::new(text.as_bytes()).ok()?;
    let w = code.width();
    let scale = 6usize;
    let quiet = 4usize;
    let size = (w + quiet * 2) * scale;
    let mut px = vec![255u8; size * size * 4];
    for (i, c) in code.to_colors().iter().enumerate() {
        if *c != qrcode::Color::Dark { continue; }
        let (cx, cy) = (i % w + quiet, i / w + quiet);
        for dy in 0..scale { for dx in 0..scale {
            let o = ((cy * scale + dy) * size + cx * scale + dx) * 4;
            px[o] = 0; px[o + 1] = 0; px[o + 2] = 0;
        } }
    }
    Some((px, size as u32))
}
