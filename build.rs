fn flags() {
    // Twemoji flags (CC-BY 4.0) rasterised once at build time: small PNGs embedded in the exe.
    let out = std::path::PathBuf::from(std::env::var("OUT_DIR").unwrap()).join("flags");
    std::fs::create_dir_all(&out).unwrap();
    let mut table = String::from("pub static FLAGS: &[(&str, &[u8])] = &[\n");
    let mut entries: Vec<_> = std::fs::read_dir("assets/flags").unwrap().flatten().map(|e| e.path()).collect();
    entries.sort();
    for p in entries {
        let id = p.file_stem().unwrap().to_string_lossy().to_string();
        let tree = resvg::usvg::Tree::from_data(&std::fs::read(&p).unwrap(), &Default::default()).unwrap();
        let size = 64u32;
        let mut pix = resvg::tiny_skia::Pixmap::new(size, size).unwrap();
        let s = size as f32 / tree.size().width().max(tree.size().height());
        resvg::render(&tree, resvg::tiny_skia::Transform::from_scale(s, s), &mut pix.as_mut());
        // tiny-skia stores premultiplied RGBA; unpremultiply for PNG
        let mut data = pix.data().to_vec();
        for px in data.chunks_mut(4) {
            let a = px[3] as u32;
            if a > 0 && a < 255 { for c in &mut px[..3] { *c = ((*c as u32 * 255 + a / 2) / a).min(255) as u8; } }
        }
        let f = out.join(format!("{id}.png"));
        let mut enc = png::Encoder::new(std::io::BufWriter::new(std::fs::File::create(&f).unwrap()), size, size);
        enc.set_color(png::ColorType::Rgba);
        enc.set_compression(png::Compression::High);
        enc.write_header().unwrap().write_image_data(&data).unwrap();
        table += &format!("    (\"{id}\", include_bytes!(r\"{}\")),\n", f.display());
    }
    table += "];\n";
    std::fs::write(std::path::PathBuf::from(std::env::var("OUT_DIR").unwrap()).join("flags.rs"), table).unwrap();
    println!("cargo:rerun-if-changed=assets/flags");
}

/// Russian .po for the UI, generated from the single EN→RU table in src/i18n.rs.
fn translations() -> std::path::PathBuf {
    let src = std::fs::read_to_string("src/i18n.rs").unwrap();
    let mut po = String::from("msgid \"\"\nmsgstr \"\"\n\"Content-Type: text/plain; charset=UTF-8\\n\"\n\n");
    for line in src.lines() {
        let l = line.trim();
        if !l.starts_with("(\"") || !l.ends_with("\"),") { continue; }
        let body = &l[2..l.len() - 3];
        if let Some((en, ru)) = body.split_once("\", \"") { po += &format!("msgid \"{en}\"\nmsgstr \"{ru}\"\n\n"); }
    }
    let dir = std::path::PathBuf::from(std::env::var("OUT_DIR").unwrap()).join("tr");
    std::fs::create_dir_all(dir.join("ru").join("LC_MESSAGES")).unwrap();
    std::fs::write(dir.join("ru").join("LC_MESSAGES").join("uplinx.po"), po).unwrap();
    println!("cargo:rerun-if-changed=src/i18n.rs");
    dir
}

fn main() {
    let cfg = slint_build::CompilerConfiguration::new()
        .with_style("material".into())
        .with_bundled_translations(translations())
        .with_default_translation_context(slint_build::DefaultTranslationContext::None);
    slint_build::compile_with_config("ui/app.slint", cfg).expect("Slint UI failed to compile");
    flags();
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("windows") {
        let mut res = winresource::WindowsResource::new();
        res.set_icon("assets/uplinx.ico") // resource id 1: exe, window and tray icon
            .set("FileDescription", "Uplinx Client")
            .set("ProductName", "Uplinx")
            .set("CompanyName", "Uplinx")
            .set("LegalCopyright", "MIT");
        res.compile().expect("resource compile failed");
    }
    println!("cargo:rerun-if-changed=assets/uplinx.ico");
}
