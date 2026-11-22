## Uplinx 1.6.3

**Download:** `Uplinx-1.6.3-setup.exe` (installer), `Uplinx-1.6.3.msi`, or `Uplinx-1.6.3-portable-win64.zip`.

### Update in the background
- The update window has a new **In background** button. The window closes and you keep working while the update downloads.
- The title bar shows the progress (↓ 42%), then a **Restart to update** button. A Windows notification tells you when the update is ready.
- If you don't restart, the update installs by itself when you quit Uplinx.

### Quiet in-place updates
- Updates no longer open the installer wizard (with its "remove the old version, install the new one" screens).
- Uplinx closes, upgrades itself silently (`msiexec /passive`: just a small progress bar, no admin prompt) and **starts again on its own**.
- Your servers, subscriptions and settings stay as they were.
- Portable builds keep updating themselves from the zip.

**Note:** this is the last update that still opens the installer. Quiet updates apply from 1.6.3 to all later versions.
