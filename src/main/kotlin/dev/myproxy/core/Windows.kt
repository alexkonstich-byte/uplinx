package dev.myproxy.core

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg.HKEY_CURRENT_USER
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import dev.myproxy.data.Paths
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

private interface WinInet : StdCallLibrary {
    fun InternetSetOptionW(hInternet: Pointer?, option: Int, buffer: Pointer?, length: Int): Boolean
}

private interface Shell32Ex : StdCallLibrary {
    fun IsUserAnAdmin(): Boolean
}

/**
 * Windows system proxy (WinINet / "Settings → Proxy"). The previous values are saved to disk before
 * we touch them, so a crash or kill never leaves the user without internet: the next start restores them.
 */
object SystemProxy {
    private const val KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings"
    private const val INTERNET_OPTION_SETTINGS_CHANGED = 39
    private const val INTERNET_OPTION_REFRESH = 37
    private val backupFile = File(Paths.home, "proxy-backup.json")
    private val wininet by lazy { Native.load("wininet", WinInet::class.java, W32APIOptions.DEFAULT_OPTIONS) }

    @Serializable
    private data class Backup(val enable: Int, val server: String?, val override: String?, val autoConfigUrl: String?)

    private const val BYPASS = "localhost;127.*;10.*;172.16.*;172.17.*;172.18.*;172.19.*;172.20.*;172.21.*;172.22.*;172.23.*;" +
        "172.24.*;172.25.*;172.26.*;172.27.*;172.28.*;172.29.*;172.30.*;172.31.*;192.168.*;<local>"

    private fun str(name: String) =
        if (Advapi32Util.registryValueExists(HKEY_CURRENT_USER, KEY, name)) Advapi32Util.registryGetStringValue(HKEY_CURRENT_USER, KEY, name) else null

    val isEnabledByUs get() = backupFile.exists()

    fun enable(httpPort: Int, socksPort: Int) {
        if (!backupFile.exists()) {
            val enable = if (Advapi32Util.registryValueExists(HKEY_CURRENT_USER, KEY, "ProxyEnable"))
                Advapi32Util.registryGetIntValue(HKEY_CURRENT_USER, KEY, "ProxyEnable") else 0
            backupFile.writeText(Json.encodeToString(Backup.serializer(), Backup(enable, str("ProxyServer"), str("ProxyOverride"), str("AutoConfigURL"))))
        }
        Advapi32Util.registrySetStringValue(HKEY_CURRENT_USER, KEY, "ProxyServer",
            "http=127.0.0.1:$httpPort;https=127.0.0.1:$httpPort;socks=127.0.0.1:$socksPort")
        Advapi32Util.registrySetStringValue(HKEY_CURRENT_USER, KEY, "ProxyOverride", BYPASS)
        if (str("AutoConfigURL") != null) Advapi32Util.registryDeleteValue(HKEY_CURRENT_USER, KEY, "AutoConfigURL")
        Advapi32Util.registrySetIntValue(HKEY_CURRENT_USER, KEY, "ProxyEnable", 1)
        refresh()
    }

    /** Puts back whatever the user had before. Safe to call any number of times. */
    fun restore() {
        if (!backupFile.exists()) return
        val b = runCatching { Json.decodeFromString(Backup.serializer(), backupFile.readText()) }.getOrNull()
        fun setOrDelete(name: String, v: String?) {
            if (v != null) Advapi32Util.registrySetStringValue(HKEY_CURRENT_USER, KEY, name, v)
            else if (str(name) != null) Advapi32Util.registryDeleteValue(HKEY_CURRENT_USER, KEY, name)
        }
        if (b != null) {
            Advapi32Util.registrySetIntValue(HKEY_CURRENT_USER, KEY, "ProxyEnable", b.enable)
            setOrDelete("ProxyServer", b.server)
            setOrDelete("ProxyOverride", b.override)
            setOrDelete("AutoConfigURL", b.autoConfigUrl)
        } else {
            Advapi32Util.registrySetIntValue(HKEY_CURRENT_USER, KEY, "ProxyEnable", 0)
        }
        refresh()
        backupFile.delete()
    }

    private fun refresh() {
        runCatching {
            wininet.InternetSetOptionW(null, INTERNET_OPTION_SETTINGS_CHANGED, null, 0)
            wininet.InternetSetOptionW(null, INTERNET_OPTION_REFRESH, null, 0)
        }
    }
}

object WinUtil {
    private val shell by lazy { Native.load("shell32", Shell32Ex::class.java, W32APIOptions.DEFAULT_OPTIONS) }

    val isAdmin: Boolean get() = runCatching { shell.IsUserAnAdmin() }.getOrDefault(false)

    /** ARGB accent colour from DWM, or null. Registry stores it as 0xAABBGGRR. */
    fun accentColor(): Long? = runCatching {
        val abgr = Advapi32Util.registryGetIntValue(HKEY_CURRENT_USER, "Software\\Microsoft\\Windows\\DWM", "AccentColor").toLong() and 0xFFFFFFFF
        val r = abgr and 0xFF; val g = (abgr shr 8) and 0xFF; val b = (abgr shr 16) and 0xFF
        0xFF000000 or (r shl 16) or (g shl 8) or b
    }.getOrNull()

    fun systemDark(): Boolean = runCatching {
        Advapi32Util.registryGetIntValue(HKEY_CURRENT_USER,
            "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize", "AppsUseLightTheme") == 0
    }.getOrDefault(false)

    private fun selfCommand(): Pair<String, List<String>>? {
        val info = ProcessHandle.current().info()
        val cmd = info.command().orElse(null) ?: return null
        return cmd to info.arguments().map { it.toList() }.orElse(emptyList())
    }

    /** Starts a new elevated copy of the app (UAC prompt). Returns false if the user cancelled. */
    fun relaunchAsAdmin(extraArg: String): Boolean {
        val (cmd, args) = selfCommand() ?: return false
        fun q(s: String) = "'" + s.replace("'", "''") + "'"
        val argList = (args + extraArg).joinToString(",") { q(if (it.contains(' ')) "\"$it\"" else it) }
        val ps = "Start-Process -FilePath ${q(cmd)} -ArgumentList $argList -Verb RunAs"
        val p = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", ps).redirectErrorStream(true).start()
        return p.waitFor() == 0
    }

    private const val RUN_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"

    var autostart: Boolean
        get() = runCatching { Advapi32Util.registryValueExists(HKEY_CURRENT_USER, RUN_KEY, "MyProxy") }.getOrDefault(false)
        set(value) {
            runCatching {
                if (value) {
                    val (cmd, _) = selfCommand() ?: return
                    Advapi32Util.registrySetStringValue(HKEY_CURRENT_USER, RUN_KEY, "MyProxy", "\"$cmd\" --minimized")
                } else if (Advapi32Util.registryValueExists(HKEY_CURRENT_USER, RUN_KEY, "MyProxy")) {
                    Advapi32Util.registryDeleteValue(HKEY_CURRENT_USER, RUN_KEY, "MyProxy")
                }
            }
        }

    /** Registers myproxy:// (and happ-style deep links of our own) for the packaged exe. */
    fun registerUrlScheme() {
        runCatching {
            val (cmd, _) = selfCommand() ?: return
            if (!cmd.endsWith("MyProxy.exe", ignoreCase = true)) return // only for the installed app, not `gradle run`
            val base = "Software\\Classes\\myproxy"
            Advapi32Util.registryCreateKey(HKEY_CURRENT_USER, "$base\\shell\\open\\command")
            Advapi32Util.registrySetStringValue(HKEY_CURRENT_USER, base, "", "URL:MyProxy")
            Advapi32Util.registrySetStringValue(HKEY_CURRENT_USER, base, "URL Protocol", "")
            Advapi32Util.registrySetStringValue(HKEY_CURRENT_USER, "$base\\shell\\open\\command", "", "\"$cmd\" \"%1\"")
        }
    }

    fun openUrl(url: String) {
        runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) }
    }

    fun openFolder(f: File) {
        runCatching { java.awt.Desktop.getDesktop().open(f) }
    }
}
