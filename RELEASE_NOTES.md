## Uplinx 1.3.0 — MyProxy is now Uplinx

**Download:** `Uplinx-1.3.0-setup.exe` (installer), `Uplinx-1.3.0.msi`, or `Uplinx-1.3.0-portable-win64.zip`.

### New name, new icon
- The app is now **Uplinx**: an uplink to your server, with an X for Xray.
- New icon: a chunky, extruded lowercase **u** with a tail, painted with Material You tonal roles (container tile, primary → tertiary face, deep-tone depth). The installer uses a warm amber-brown scheme; inside the app the icon (window, tray, navigation rail) recolours itself with your current theme and accent.
- Deep links are now `uplinx://add/<link>`. Old `myproxy://` links keep working.

### Moving from MyProxy
- Your servers, subscriptions and settings move automatically from `%APPDATA%\MyProxy` to `%APPDATA%\Uplinx` on first start.
- The old "start with Windows" entry is replaced with the new one.
- **Portable MyProxy users:** the update unpacks fine, but the old updater tries to restart `MyProxy.exe`, which no longer exists. Start `Uplinx.exe` from the same folder once; later updates restart on their own.

### Also
- The whole code base, packages and build use the new name.
