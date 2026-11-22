# Uplinx

<img src="assets/uplinx-512.png" width="96" align="right">

**English** · [Русский](README.ru.md)

A fast, tiny proxy client for Windows in **Material You 3 Expressive**, powered by **Xray-core**.
Written in Rust with [Slint](https://slint.dev): about **10 MB of RAM** and **0% CPU** when idle.

<p>
  <img src="docs/screenshots/home-dark.png" width="49%">
  <img src="docs/screenshots/home-light.png" width="49%">
</p>
<p>
  <img src="docs/screenshots/servers.png" width="49%">
  <img src="docs/screenshots/subscriptions.png" width="49%">
</p>
<p>
  <img src="docs/screenshots/routing.png" width="49%">
  <img src="docs/screenshots/settings.png" width="49%">
</p>

## Download

Get `Uplinx-<version>.msi` or the portable `Uplinx-<version>-portable-win64.zip` from
[Releases](https://github.com/alexkonstich-byte/uplinx/releases/latest).
The installer doesn't need administrator rights. Xray-core, the geo databases and Wintun are downloaded on first connect.

## Features

**Import anything.** Paste it, drop files onto the window, press Ctrl+V or scan a QR code on screen —
and if you open Uplinx with a new link copied, it offers to add it:
- share links: `vless`, `vmess`, `trojan`, `ss`, `socks`, `http(s)`, `hy2` / `hysteria2`, `wireguard`;
- subscriptions (plain or base64), nested subscriptions, JSON arrays of links, proxy pools (`ip:port[:user:pass]`);
- WireGuard and AmneziaWG `.conf`, Amnezia `vpn://` keys;
- Xray JSON (balancers and chains are kept whole), sing-box JSON, Clash / Mihomo YAML with rules, JSONC;
- Happ routing profiles (`happ://routing/add/…`) and `uplinx://add/…` deep links.

**Subscriptions.** Traffic and expiry (`subscription-userinfo`), title, update interval, support and website links,
announcements, logos, panel routing profiles and rules, HWID header, usage history.
If a panel answers "App not supported", Uplinx retries as other popular clients and remembers what worked.

**Connection.**
- System proxy (previous settings are backed up and restored even after a crash) or TUN for all apps.
- Reconnects by itself after a crash, a network change or sleep; optional kill switch keeps traffic from going around the VPN.
- **Connect to the fastest** server in one click (from the app or the tray).
- AmneziaWG configs with S/H obfuscation run through the official AmneziaWG service.
- Routing: by rules / global / direct; presets for LAN, Russian sites, ads and runetfreedom lists; custom rules by
  domain, IP, geosite, geoip, process and port; split tunnelling by program.
- **Where will a site go?** Type a site or IP and see whether it goes through the proxy, directly or is blocked — and which rule decides.

**Measurements.** Real URL-test ping through a throwaway Xray bound to the physical adapter (another VPN can't fake it),
balancer members included; detection of TUN adapters that fake TCP handshakes; speed test (Cloudflare); site checks.

**Home dashboard.** Widgets on a 12-column grid — connect, server, modes, speed, ping, your IP, subscription quota,
servers, session, sites, speed test, usage. Drag, resize, add and remove them.

**Servers.** Favourites on top, foldable groups, search, sorting by name / ping / recent; new servers are pinged right away.

**And also:** Windows accent colours or your own, light / dark, English / Russian, a tray menu with servers and modes,
notifications, mini window, autostart, logs with search, pause and export, server editor, QR export, tooltips,
the window remembers its size and place, updates from GitHub Releases (quiet MSI upgrade or portable self-update).

**Shortcuts:** Ctrl+V import · Ctrl+N add · Ctrl+F search servers · Ctrl+1…6 screens · F5 test / update · Esc close.

## Build

```
cargo test --release
cargo build --release
```

The release workflow (`.github/workflows/release.yml`) builds the MSI with WiX and the portable zip on every `v*` tag.

## Credits

[Xray-core](https://github.com/XTLS/Xray-core) (MPL-2.0) · [Slint](https://slint.dev) · flags by
[Twemoji](https://github.com/jdecked/twemoji) (CC-BY 4.0) · Material Icons, Rubik and Unbounded fonts (OFL / Apache-2.0).
