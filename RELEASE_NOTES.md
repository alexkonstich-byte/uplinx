## Uplinx 1.3.0 — MyProxy is now Uplinx

**Download:** `Uplinx-1.3.0-setup.exe` (installer), `Uplinx-1.3.0.msi`, or `Uplinx-1.3.0-portable-win64.zip`.

### New name, new icon
- The app is now **Uplinx**: an uplink to your server, with an X for Xray.
- New icon: a geometric **U** in a "signal going up" gradient: electric indigo → azure → teal → neon lime. It's used in the window, the tray (with a green or red status dot), the navigation rail and the installer.
- Deep links are now `uplinx://add/<link>`. Old `myproxy://` links keep working.

### Moving from MyProxy
- Your servers, subscriptions and settings move automatically from `%APPDATA%\MyProxy` to `%APPDATA%\Uplinx` on first start.
- The old "start with Windows" entry is replaced with the new one.
- **Portable MyProxy users:** the update unpacks fine, but the old updater tries to restart `MyProxy.exe`, which no longer exists. Start `Uplinx.exe` from the same folder once; later updates restart on their own.

### Also
- The whole code base, packages and build use the new name.
