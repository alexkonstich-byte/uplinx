## Uplinx 1.6.4 — much lighter on memory

**Download:** `Uplinx-1.6.4-setup.exe` (installer), `Uplinx-1.6.4.msi`, or `Uplinx-1.6.4-portable-win64.zip`.

Memory as Task Manager shows it (private working set):

| State | Before | Now |
|---|---|---|
| Window open | ~885 MB | **~245 MB** |
| Minimized | ~885 MB | **~11 MB** |
| Closed to tray (VPN keeps running) | ~885 MB | **~60 MB** |

How:
- When the window is closed to the tray, it is **destroyed**, not hidden, which frees its whole UI and graphics memory. It is rebuilt instantly from the tray.
- After minimizing or hiding, Uplinx collects garbage and hands the unused pages back to Windows.
- The Java runtime is tuned for footprint: a small heap that shrinks, compact object headers (Java 25), lighter JIT and smaller thread stacks.
