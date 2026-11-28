## Uplinx 2.3

**Home widgets, reworked**
- Dragging a widget follows the pointer exactly — no more jitter — and a tinted cell shows where it will land. On release it springs into place while the others glide out of the way.
- Moving or resizing one widget no longer makes the whole dashboard flash and reappear.
- Arranging widgets no longer scrolls the page by accident.
- Every widget has a minimum size its content fits in; layouts saved by older versions are repaired automatically.
- Removing a widget closes the gap it leaves, and both removing and "Reset" can be undone. A newly added widget scrolls into view.

**Motion and design (Material 3 Expressive)**
- One scale for corners, spacing, sizes and motion across the whole app.
- Buttons, chips, segments, switches and list rows respond to every press with a small spring; the navigation indicator grows out of its icon.
- Connecting sends a ring out of the button; the status dot breathes while connecting; ping is measured with waving dots; charts, progress bars and menus move smoothly; text fields float their labels.

**Fixes**
- Switches always show the real setting (a rule's switch could stay put when the rule was moved).
- Settings fields return to the saved value when an entry is rejected; SOCKS and HTTP can't share a port; the ping URL must be http(s).
- Server search and log filters are kept when you leave the screen and come back.
- Messages show above open dialogs, so a failed import inside the "Add servers" dialog tells you why.
- Undo after deleting a server or subscription restores just that — nothing else that changed meanwhile.
- Pasting a link you already have doesn't add a second copy.
- A second press on "Connecting…" cancels the core download; slow connections no longer cut the download off after 60 seconds.
- A Direct DNS given as a hostname (DoH), IPv6 or with a port no longer stops Xray from starting; sing-box reads IPv6 DNS addresses correctly.

## Also new since 2.1

**sing-box engine**
- Choose Xray or sing-box in Settings → Core. sing-box is downloaded when you pick it and runs the same servers, modes, rules and split tunnelling.
- Servers sing-box can't run (xhttp, Xray JSON, AmneziaWG) automatically use Xray, and if sing-box ever refuses a config, Xray takes over — you are never left offline.

**Servers**
- Measure the real download speed of every server in one go; each server shows its speed, and you can sort by it.
- "Countries": servers grouped by country with flags and the best ping of each group.

**Routing**
- Ready-made rule sets: streaming, AI services and social networks through the VPN, games directly, block torrents.
- Programs: pick running apps — with their icons — and send them through the VPN, directly or block them. Icons also show in rules and split tunnelling.
- A rule that names a category missing from your geo databases is skipped instead of stopping the core.

**Home**
- Speedometer widget: a live dial for the running test or your current download speed.

**Look & feel**
- A short notice on first start: author, MIT licence and terms of use, with a language choice.

**Downloads**
- `Uplinx-2.3.0.msi` — installs for the current user, no admin rights needed.
- `Uplinx-2.3.0-portable-win64.zip` — unpack anywhere and run `Uplinx.exe`.
- `SHA256SUMS.txt` — checksums.
