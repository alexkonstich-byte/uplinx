## Uplinx 1.6.2

**Download:** `Uplinx-1.6.2-setup.exe` (installer), `Uplinx-1.6.2.msi`, or `Uplinx-1.6.2-portable-win64.zip`.

### Fixed
- **Home widgets jumped back.** After you added or removed widgets, dragging or editing one restored the deleted widgets and dropped the new ones. Layout changes now always apply to the current layout.
- **Task Manager name.** The process shows as **Uplinx Client** (it used to show a long description).
- **Segmented buttons** no longer wrap a label onto two lines. The geo-database switch shows *v2fly* and *runetfreedom (RU)*.

### Faster and lighter
- **CPU:** decorative motion (living background, spinning button, orbits, title-bar pulse, wavy progress bars) pauses whenever the window is in the background or minimized. A minimized Uplinx now uses ~0% CPU (it was ~3–16%); in front it uses about 4%.
- Idle animations that ran even when unused are gone: the edit-mode wiggle, the subscription sync spinner and the site-check pulse.
- **Memory:** a smaller Java heap that is returned to Windows when idle, about 400 MB instead of ~800 MB.
