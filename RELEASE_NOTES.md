## Uplinx 1.3.1

**Download:** `Uplinx-1.3.1-setup.exe` (installer), `Uplinx-1.3.1.msi`, or `Uplinx-1.3.1-portable-win64.zip`.
MyProxy 1.1–1.2 and Uplinx 1.3.0 offer this update automatically. Settings move from `%APPDATA%\MyProxy` to `%APPDATA%\Uplinx` on first start.

### Fixed: every server showed 1 ms
When another VPN runs in TUN mode (Happ, for example), its virtual adapter answers every TCP handshake itself, so a plain ping showed **1 ms for every server**.
- Ping is now always a **real URL test through Xray**. If the core isn't downloaded yet, it is fetched first; nothing is started or routed on the system.
- Test connections are **bound to your physical network adapter**, so another VPN's tunnel (or Uplinx's own TUN) can't fake or reroute them.
- When a plain TCP ping can't be avoided, Uplinx detects faked handshakes and shows **TUN?** with an explanation instead of a fake 1 ms.

### Design
- **Server avatars:** the flag sits like a sticker in a soft circle, and the selected server's tile blooms into a scalloped Material You shape. Servers without a flag show a globe; balancers show a hub icon.
- **Pinned group headers** stay on top while long lists scroll, with a slim Material scrollbar.
- Calmer background shapes in dark mode, and none of them overlap the screen title anymore.

### From 1.3.0: MyProxy is now Uplinx
New name, a new extruded **u** icon in Material You tonal colours, and `uplinx://` deep links (`myproxy://` still works). **Portable MyProxy users:** start `Uplinx.exe` once after this update; later updates restart on their own.
