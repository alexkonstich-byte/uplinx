//! Small Windows bits: tray icon + notifications, autostart, deep links, admin, single instance, memory trim.

use std::cell::RefCell;
use std::io::{BufRead, BufReader, Write};
use std::net::{Ipv4Addr, TcpListener, TcpStream};
use std::os::windows::process::CommandExt;
use windows_sys::Win32::Foundation::*;
use windows_sys::Win32::System::LibraryLoader::GetModuleHandleW;
use windows_sys::Win32::UI::Shell::*;
use windows_sys::Win32::UI::WindowsAndMessaging::*;
use winreg::{enums::*, RegKey};

pub const CREATE_NO_WINDOW: u32 = 0x0800_0000;

fn w(s: &str) -> Vec<u16> { s.encode_utf16().chain(std::iter::once(0)).collect() }
fn copy_w(dst: &mut [u16], s: &str) { let v: Vec<u16> = s.encode_utf16().take(dst.len() - 1).collect(); dst[..v.len()].copy_from_slice(&v); dst[v.len()] = 0; }

pub fn exe() -> String { std::env::current_exe().map(|p| p.to_string_lossy().to_string()).unwrap_or_default() }
fn is_packaged() -> bool { exe().to_lowercase().ends_with("uplinx.exe") }

pub fn is_admin() -> bool { unsafe { IsUserAnAdmin() != 0 } }

/// Starts an elevated copy (UAC). False if cancelled.
pub fn relaunch_as_admin(extra: &str) -> bool {
    let (file, params, verb) = (w(&exe()), w(extra), w("runas"));
    let r = unsafe { ShellExecuteW(std::ptr::null_mut(), verb.as_ptr(), file.as_ptr(), params.as_ptr(), std::ptr::null(), SW_SHOWNORMAL) };
    r as isize > 32
}

pub fn open_url(url: &str) {
    let (op, f) = (w("open"), w(url));
    unsafe { ShellExecuteW(std::ptr::null_mut(), op.as_ptr(), f.as_ptr(), std::ptr::null(), std::ptr::null(), SW_SHOWNORMAL); }
}

/// Drops pages we don't need right now (after hiding to tray / minimising).
pub fn trim_memory() {
    #[link(name = "kernel32")]
    unsafe extern "system" { fn K32EmptyWorkingSet(h: HANDLE) -> i32; fn GetCurrentProcess() -> HANDLE; }
    unsafe { K32EmptyWorkingSet(GetCurrentProcess()); }
}

const RUN_KEY: &str = "Software\\Microsoft\\Windows\\CurrentVersion\\Run";
fn sandboxed() -> bool { std::env::var_os("UPLINX_HOME").is_some() }

pub fn autostart() -> bool {
    RegKey::predef(HKEY_CURRENT_USER).open_subkey(RUN_KEY).and_then(|k| k.get_value::<String, _>("Uplinx")).is_ok()
}
pub fn set_autostart(on: bool) {
    if sandboxed() { return; }
    if let Ok(k) = RegKey::predef(HKEY_CURRENT_USER).open_subkey_with_flags(RUN_KEY, KEY_WRITE) {
        if on { let _ = k.set_value("Uplinx", &format!("\"{}\" --minimized", exe())); } else { let _ = k.delete_value("Uplinx"); }
    }
}

/// uplinx:// links open this exe.
pub fn register_integration() {
    if sandboxed() || !is_packaged() { return; }
    let hk = RegKey::predef(HKEY_CURRENT_USER);
    for scheme in ["uplinx"] {
        if let Ok((k, _)) = hk.create_subkey(format!("Software\\Classes\\{scheme}")) {
            let _ = k.set_value("", &"URL:Uplinx");
            let _ = k.set_value("URL Protocol", &"");
            if let Ok((c, _)) = k.create_subkey("shell\\open\\command") { let _ = c.set_value("", &format!("\"{}\" \"%1\"", exe())); }
        }
    }
}

// ------------------------------------------------------------------ single instance

fn instance_port() -> u16 { std::env::var("UPLINX_INSTANCE_PORT").ok().and_then(|p| p.parse().ok()).unwrap_or(47821) }

/// Ok(listener) when we are the first instance; Err(()) after forwarding args to the running one.
pub fn single_instance(args: &[String]) -> Result<Option<TcpListener>, ()> {
    match TcpListener::bind((Ipv4Addr::LOCALHOST, instance_port())) {
        Ok(l) => Ok(Some(l)),
        Err(_) => match TcpStream::connect((Ipv4Addr::LOCALHOST, instance_port())) {
            Ok(mut s) => { let msg = if args.is_empty() { "--show".to_string() } else { args.join("\n") }; let _ = writeln!(s, "{msg}"); Err(()) }
            Err(_) => Ok(None),
        },
    }
}

pub fn listen_instances(l: TcpListener, on: impl Fn(Vec<String>) + Send + 'static) {
    std::thread::spawn(move || {
        for s in l.incoming().flatten() {
            let lines: Vec<String> = BufReader::new(s).lines().map_while(Result::ok).filter(|x| !x.is_empty()).collect();
            on(lines);
        }
    });
}

// ------------------------------------------------------------------ tray

#[derive(Clone, Debug)]
pub enum TrayEvent { Show, Toggle, Quit, Fastest, Select(String), ConnectMode(&'static str), RoutingMode(&'static str) }

/// What the tray menu shows; built fresh every time it opens.
pub struct TrayMenu {
    pub connected: bool,
    pub servers: Vec<(String, String, bool)>, // id, name, selected
    pub connect_mode: String,
    pub routing_mode: String,
    pub labels: Vec<String>, // show, connect/disconnect, fastest, servers, mode, system proxy, TUN, by rules, global, direct, quit
}

const WM_TRAY: u32 = WM_APP + 1;
const ID_SHOW: usize = 1;
const ID_TOGGLE: usize = 2;
const ID_QUIT: usize = 3;
const ID_FASTEST: usize = 4;
const ID_PROXY: usize = 10;
const ID_TUN: usize = 11;
const ID_RULE: usize = 20;
const ID_GLOBAL: usize = 21;
const ID_DIRECT: usize = 22;
const ID_SERVER: usize = 100;

thread_local! {
    static TRAY: RefCell<Option<Tray>> = const { RefCell::new(None) };
}

struct Tray { hwnd: HWND, cb: Box<dyn Fn(TrayEvent)>, menu: Box<dyn Fn() -> TrayMenu>, server_ids: Vec<String> }

unsafe extern "system" fn wndproc(hwnd: HWND, msg: u32, wp: WPARAM, lp: LPARAM) -> LRESULT {
    if msg == WM_TRAY {
        let ev = (lp & 0xffff) as u32;
        if ev == WM_LBUTTONUP || ev == NIN_SELECT { fire(TrayEvent::Show); }
        if ev == WM_RBUTTONUP || ev == WM_CONTEXTMENU { show_menu(hwnd); }
        return 0;
    }
    if msg == WM_COMMAND {
        let id = wp & 0xffff;
        let e = match id {
            ID_SHOW => Some(TrayEvent::Show), ID_TOGGLE => Some(TrayEvent::Toggle), ID_QUIT => Some(TrayEvent::Quit), ID_FASTEST => Some(TrayEvent::Fastest),
            ID_PROXY => Some(TrayEvent::ConnectMode("SYSTEM_PROXY")), ID_TUN => Some(TrayEvent::ConnectMode("TUN")),
            ID_RULE => Some(TrayEvent::RoutingMode("RULE")), ID_GLOBAL => Some(TrayEvent::RoutingMode("GLOBAL")), ID_DIRECT => Some(TrayEvent::RoutingMode("DIRECT")),
            i if i >= ID_SERVER => TRAY.with(|t| t.borrow().as_ref().and_then(|t| t.server_ids.get(i - ID_SERVER).cloned())).map(TrayEvent::Select),
            _ => None,
        };
        if let Some(e) = e { fire(e); }
        return 0;
    }
    unsafe { DefWindowProcW(hwnd, msg, wp, lp) }
}

fn fire(e: TrayEvent) {
    // the callback may re-enter TRAY (e.g. to update the tooltip): call it outside the borrow
    let cb = TRAY.with(|t| t.borrow().as_ref().map(|t| &*t.cb as *const dyn Fn(TrayEvent)));
    if let Some(cb) = cb { unsafe { (*cb)(e) } }
}

fn show_menu(hwnd: HWND) {
    let m = TRAY.with(|t| t.borrow().as_ref().map(|t| (t.menu)()));
    let Some(m) = m else { return };
    let l = |i: usize| w(m.labels.get(i).map(String::as_str).unwrap_or(""));
    unsafe {
        let add = |menu: HMENU, flags: u32, id: usize, text: &[u16]| { AppendMenuW(menu, flags, id, text.as_ptr()); };
        let root = CreatePopupMenu();
        add(root, MF_STRING | MF_DEFAULT, ID_SHOW, &l(0));
        add(root, MF_SEPARATOR, 0, &[0]);
        add(root, MF_STRING, ID_TOGGLE, &l(1));
        add(root, MF_STRING, ID_FASTEST, &l(2));
        // servers: favourites and the fastest first, at most 25
        let servers = CreatePopupMenu();
        for (i, (_, name, sel)) in m.servers.iter().enumerate() {
            add(servers, MF_STRING | if *sel { MF_CHECKED } else { 0 }, ID_SERVER + i, &w(name));
        }
        if m.servers.is_empty() { add(servers, MF_STRING | MF_GRAYED, 0, &w("—")); }
        add(root, MF_POPUP, servers as usize, &l(3));
        let modes = CreatePopupMenu();
        add(modes, MF_STRING | if m.connect_mode != "TUN" { MF_CHECKED } else { 0 }, ID_PROXY, &l(5));
        add(modes, MF_STRING | if m.connect_mode == "TUN" { MF_CHECKED } else { 0 }, ID_TUN, &l(6));
        add(modes, MF_SEPARATOR, 0, &[0]);
        add(modes, MF_STRING | if m.routing_mode == "RULE" { MF_CHECKED } else { 0 }, ID_RULE, &l(7));
        add(modes, MF_STRING | if m.routing_mode == "GLOBAL" { MF_CHECKED } else { 0 }, ID_GLOBAL, &l(8));
        add(modes, MF_STRING | if m.routing_mode == "DIRECT" { MF_CHECKED } else { 0 }, ID_DIRECT, &l(9));
        add(root, MF_POPUP, modes as usize, &l(4));
        add(root, MF_SEPARATOR, 0, &[0]);
        add(root, MF_STRING, ID_QUIT, &l(10));
        TRAY.with(|t| if let Some(t) = t.borrow_mut().as_mut() { t.server_ids = m.servers.iter().map(|x| x.0.clone()).collect(); });
        let mut p = POINT { x: 0, y: 0 };
        GetCursorPos(&mut p);
        SetForegroundWindow(hwnd);
        TrackPopupMenu(root, TPM_RIGHTBUTTON, p.x, p.y, 0, hwnd, std::ptr::null());
        DestroyMenu(root); // also destroys the submenus
    }
}

fn nid(hwnd: HWND) -> NOTIFYICONDATAW {
    let mut n: NOTIFYICONDATAW = unsafe { std::mem::zeroed() };
    n.cbSize = std::mem::size_of::<NOTIFYICONDATAW>() as u32;
    n.hWnd = hwnd;
    n.uID = 1;
    n
}

fn app_icon() -> HICON {
    unsafe {
        let h = GetModuleHandleW(std::ptr::null());
        let i = LoadImageW(h, 1 as _, IMAGE_ICON, GetSystemMetrics(SM_CXSMICON), GetSystemMetrics(SM_CYSMICON), 0);
        if i.is_null() { LoadIconW(std::ptr::null_mut(), IDI_APPLICATION) } else { i as HICON }
    }
}

/// Must run on the UI thread (winit pumps its messages).
pub fn tray_create(tooltip: &str, menu: impl Fn() -> TrayMenu + 'static, cb: impl Fn(TrayEvent) + 'static) {
    unsafe {
        let class = w("UplinxTray");
        let wc = WNDCLASSW { lpfnWndProc: Some(wndproc), hInstance: GetModuleHandleW(std::ptr::null()), lpszClassName: class.as_ptr(), ..std::mem::zeroed() };
        RegisterClassW(&wc);
        let hwnd = CreateWindowExW(0, class.as_ptr(), class.as_ptr(), 0, 0, 0, 0, 0, std::ptr::null_mut(), std::ptr::null_mut(), wc.hInstance, std::ptr::null());
        let mut n = nid(hwnd);
        n.uFlags = NIF_MESSAGE | NIF_ICON | NIF_TIP;
        n.uCallbackMessage = WM_TRAY;
        n.hIcon = app_icon();
        copy_w(&mut n.szTip, tooltip);
        Shell_NotifyIconW(NIM_ADD, &n);
        n.Anonymous.uVersion = NOTIFYICON_VERSION_4;
        Shell_NotifyIconW(NIM_SETVERSION, &n);
        TRAY.with(|t| *t.borrow_mut() = Some(Tray { hwnd, cb: Box::new(cb), menu: Box::new(menu), server_ids: vec![] }));
    }
}

pub fn tray_update(tooltip: &str) {
    TRAY.with(|t| {
        let t = t.borrow();
        let Some(t) = t.as_ref() else { return };
        let mut n = nid(t.hwnd);
        n.uFlags = NIF_TIP;
        copy_w(&mut n.szTip, tooltip);
        unsafe { Shell_NotifyIconW(NIM_MODIFY, &n); }
    });
}

/// Windows notification (shown as a toast on Windows 10/11).
pub fn notify(title: &str, text: &str, error: bool) {
    TRAY.with(|t| {
        let t = t.borrow();
        let Some(t) = t.as_ref() else { return };
        let mut n = nid(t.hwnd);
        n.uFlags = NIF_INFO;
        n.dwInfoFlags = if error { NIIF_ERROR } else { NIIF_INFO };
        copy_w(&mut n.szInfoTitle, title);
        copy_w(&mut n.szInfo, text);
        unsafe { Shell_NotifyIconW(NIM_MODIFY, &n); }
    });
}

pub fn tray_remove() {
    TRAY.with(|t| {
        if let Some(t) = t.borrow_mut().take() { let n = nid(t.hwnd); unsafe { Shell_NotifyIconW(NIM_DELETE, &n); DestroyWindow(t.hwnd); } }
    });
}

// ------------------------------------------------------------------ clipboard & processes

pub fn clipboard_text() -> Option<String> { arboard::Clipboard::new().ok()?.get_text().ok() }
pub fn set_clipboard(text: &str) { if let Ok(mut c) = arboard::Clipboard::new() { let _ = c.set_text(text.to_string()); } }

/// Running programs (exe names), for split tunnelling and process rules.
pub fn running_processes() -> Vec<String> {
    let out = std::process::Command::new("tasklist").args(["/FO", "CSV", "/NH"]).creation_flags(CREATE_NO_WINDOW).output();
    let mut v: Vec<String> = out.map(|o| String::from_utf8_lossy(&o.stdout).lines()
        .filter_map(|l| l.split("\",\"").next().map(|x| x.trim_matches('"').to_string()))
        .filter(|n| n.to_lowercase().ends_with(".exe")).collect()).unwrap_or_default();
    v.sort_by_key(|x| x.to_lowercase());
    v.dedup_by_key(|x| x.to_lowercase());
    v
}

/// Standard "Open" dialog; several files allowed. Blocks the calling thread.
pub fn open_files(title: &str) -> Vec<std::path::PathBuf> {
    use windows_sys::Win32::UI::Controls::Dialogs::*;
    let mut buf = vec![0u16; 32 * 1024];
    let filter: Vec<u16> = "Configs, QR (*.txt;*.conf;*.json;*.yaml;*.yml;*.png;*.jpg)\0*.txt;*.conf;*.json;*.jsonc;*.yaml;*.yml;*.png;*.jpg;*.jpeg;*.bmp;*.webp\0All files\0*.*\0\0".encode_utf16().collect();
    let t = w(title);
    let mut ofn: OPENFILENAMEW = unsafe { std::mem::zeroed() };
    ofn.lStructSize = std::mem::size_of::<OPENFILENAMEW>() as u32;
    ofn.lpstrFilter = filter.as_ptr();
    ofn.lpstrFile = buf.as_mut_ptr();
    ofn.nMaxFile = buf.len() as u32;
    ofn.lpstrTitle = t.as_ptr();
    ofn.Flags = OFN_ALLOWMULTISELECT | OFN_EXPLORER | OFN_FILEMUSTEXIST | OFN_NOCHANGEDIR;
    if unsafe { GetOpenFileNameW(&mut ofn) } == 0 { return vec![]; }
    let parts: Vec<String> = buf.split(|&c| c == 0).take_while(|p| !p.is_empty()).map(String::from_utf16_lossy).collect();
    match parts.as_slice() {
        [one] => vec![one.into()],
        [dir, files @ ..] => files.iter().map(|f| std::path::Path::new(dir).join(f)).collect(),
        _ => vec![],
    }
}

/// Standard "Save as" dialog. Blocks the calling thread.
pub fn save_file(title: &str, default_name: &str) -> Option<std::path::PathBuf> {
    use windows_sys::Win32::UI::Controls::Dialogs::*;
    let mut buf = vec![0u16; 4096];
    let d: Vec<u16> = default_name.encode_utf16().collect();
    buf[..d.len()].copy_from_slice(&d);
    let filter: Vec<u16> = "Text (*.txt)\0*.txt\0All files\0*.*\0\0".encode_utf16().collect();
    let t = w(title);
    let ext = w("txt");
    let mut ofn: OPENFILENAMEW = unsafe { std::mem::zeroed() };
    ofn.lStructSize = std::mem::size_of::<OPENFILENAMEW>() as u32;
    ofn.lpstrFilter = filter.as_ptr();
    ofn.lpstrFile = buf.as_mut_ptr();
    ofn.nMaxFile = buf.len() as u32;
    ofn.lpstrTitle = t.as_ptr();
    ofn.lpstrDefExt = ext.as_ptr();
    ofn.Flags = OFN_OVERWRITEPROMPT | OFN_NOCHANGEDIR | OFN_EXPLORER;
    if unsafe { GetSaveFileNameW(&mut ofn) } == 0 { return None; }
    let n = buf.iter().position(|&c| c == 0).unwrap_or(buf.len());
    Some(String::from_utf16_lossy(&buf[..n]).into())
}

/// Virtual screen rectangle (all monitors): x, y, width, height.
pub fn virtual_screen() -> (i32, i32, i32, i32) {
    unsafe { (GetSystemMetrics(SM_XVIRTUALSCREEN), GetSystemMetrics(SM_YVIRTUALSCREEN), GetSystemMetrics(SM_CXVIRTUALSCREEN), GetSystemMetrics(SM_CYVIRTUALSCREEN)) }
}

// ------------------------------------------------------------------ programs with icons

/// Running programs: (exe name, full path), Windows' own binaries left out, one entry per exe name.
pub fn running_programs() -> Vec<(String, String)> {
    use windows_sys::Win32::System::Diagnostics::ToolHelp::*;
    use windows_sys::Win32::System::Threading::*;
    let mut out: Vec<(String, String)> = vec![];
    unsafe {
        let snap = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
        if snap == INVALID_HANDLE_VALUE { return out; }
        let mut e: PROCESSENTRY32W = std::mem::zeroed();
        e.dwSize = std::mem::size_of::<PROCESSENTRY32W>() as u32;
        let mut ok = Process32FirstW(snap, &mut e) != 0;
        let windir = std::env::var("WINDIR").unwrap_or_else(|_| r"C:\Windows".into()).to_lowercase();
        while ok {
            let n = e.szExeFile.iter().position(|&c| c == 0).unwrap_or(e.szExeFile.len());
            let name = String::from_utf16_lossy(&e.szExeFile[..n]);
            let h = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, e.th32ProcessID);
            if !h.is_null() {
                let mut buf = vec![0u16; 1024];
                let mut len = buf.len() as u32;
                if QueryFullProcessImageNameW(h, 0, buf.as_mut_ptr(), &mut len) != 0 {
                    let path = String::from_utf16_lossy(&buf[..len as usize]);
                    if !path.to_lowercase().starts_with(&windir) && name.to_lowercase().ends_with(".exe")
                        && !out.iter().any(|(x, _)| x.eq_ignore_ascii_case(&name)) {
                        out.push((name.clone(), path));
                    }
                }
                CloseHandle(h);
            }
            ok = Process32NextW(snap, &mut e) != 0;
        }
        CloseHandle(snap);
    }
    out.sort_by_key(|x| x.0.to_lowercase());
    out
}

/// The shell icon of a file as RGBA pixels (32 x 32).
pub fn file_icon(path: &str) -> Option<(Vec<u8>, u32)> {
    use windows_sys::Win32::Graphics::Gdi::*;
    unsafe {
        let mut sfi: SHFILEINFOW = std::mem::zeroed();
        let p = w(path);
        if SHGetFileInfoW(p.as_ptr(), 0, &mut sfi, std::mem::size_of::<SHFILEINFOW>() as u32, SHGFI_ICON | SHGFI_LARGEICON) == 0 || sfi.hIcon.is_null() { return None; }
        let mut ii: ICONINFO = std::mem::zeroed();
        let got = GetIconInfo(sfi.hIcon, &mut ii) != 0;
        let mut result = None;
        if got && !ii.hbmColor.is_null() {
            let size = 32i32;
            let dc = CreateCompatibleDC(std::ptr::null_mut());
            let mut bi: BITMAPINFO = std::mem::zeroed();
            bi.bmiHeader.biSize = std::mem::size_of::<BITMAPINFOHEADER>() as u32;
            bi.bmiHeader.biWidth = size;
            bi.bmiHeader.biHeight = -size;
            bi.bmiHeader.biPlanes = 1;
            bi.bmiHeader.biBitCount = 32;
            let mut px = vec![0u8; (size * size * 4) as usize];
            if GetDIBits(dc, ii.hbmColor, 0, size as u32, px.as_mut_ptr().cast(), &mut bi, DIB_RGB_COLORS) != 0 {
                let has_alpha = px.chunks(4).any(|c| c[3] != 0);
                for c in px.chunks_mut(4) { c.swap(0, 2); if !has_alpha { c[3] = 255; } } // BGRA → RGBA
                result = Some((px, size as u32));
            }
            DeleteDC(dc);
        }
        if !ii.hbmColor.is_null() { DeleteObject(ii.hbmColor); }
        if !ii.hbmMask.is_null() { DeleteObject(ii.hbmMask); }
        DestroyIcon(sfi.hIcon);
        result
    }
}
