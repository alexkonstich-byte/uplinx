package dev.myproxy.core

import dev.myproxy.L

import dev.myproxy.data.GeoSource
import dev.myproxy.data.Paths
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.zip.ZipInputStream

sealed interface CoreStatus {
    data object Stopped : CoreStatus
    data class Preparing(val what: String, val progress: Float?) : CoreStatus
    data object Starting : CoreStatus
    data class Running(val since: Long) : CoreStatus
    data object Stopping : CoreStatus
    data class Failed(val message: String) : CoreStatus
}

data class Traffic(
    val up: Long = 0, val down: Long = 0,           // bytes/s
    val totalUp: Long = 0, val totalDown: Long = 0,
    val history: List<Pair<Long, Long>> = emptyList(), // last 60 samples (up, down)
)

data class LogLine(val time: Long, val level: Char, val text: String)

class CoreManager(private val scope: CoroutineScope) {
    private val http: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(20)).build()

    private val _status = MutableStateFlow<CoreStatus>(CoreStatus.Stopped)
    val status: StateFlow<CoreStatus> = _status.asStateFlow()
    private val _traffic = MutableStateFlow(Traffic())
    val traffic: StateFlow<Traffic> = _traffic.asStateFlow()
    private val _logs = MutableStateFlow<List<LogLine>>(emptyList())
    val logs: StateFlow<List<LogLine>> = _logs.asStateFlow()
    private val _version = MutableStateFlow<String?>(null)
    val version: StateFlow<String?> = _version.asStateFlow()

    private var process: Process? = null
    private var statsJob: Job? = null
    private val pidFile = File(Paths.home, "xray.pid")

    init {
        killOrphan()
        // a crash while an AmneziaWG tunnel was up: take it down so the user's routing is restored
        if (File(Paths.home, "awg.active").exists()) scope.launch(Dispatchers.IO) { runCatching { stopAmneziaBlocking() } }
        scope.launch(Dispatchers.IO) { _version.value = readVersion() }
    }

    fun log(text: String, level: Char = 'I') {
        _logs.update { (it + LogLine(System.currentTimeMillis(), level, text)).takeLast(2000) }
    }

    fun clearLogs() { _logs.value = emptyList() }

    /** For UI previews/screenshots only: fakes a state without touching the system. */
    internal fun previewState(status: CoreStatus, traffic: Traffic) { _status.value = status; _traffic.value = traffic }

    // ---------------------------------------------------------------- assets

    val hasCore get() = Paths.xrayExe.exists()

    private fun download(url: String, target: File, label: String) {
        val req = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofMinutes(5)).header("User-Agent", "MyProxy").build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream())
        if (resp.statusCode() !in 200..299) error("$label: HTTP ${resp.statusCode()}")
        val total = resp.headers().firstValueAsLong("content-length").orElse(-1)
        val tmp = File(target.parentFile, target.name + ".part")
        resp.body().use { input ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024); var done = 0L
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    out.write(buf, 0, n); done += n
                    _status.value = CoreStatus.Preparing(label, if (total > 0) done.toFloat() / total else null)
                }
            }
        }
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun unzip(zip: File, dir: File, only: (String) -> String?) {
        ZipInputStream(zip.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory) continue
                val outName = only(e.name) ?: continue
                File(dir, outName).outputStream().use { z.copyTo(it) }
            }
        }
    }

    /** Downloads/updates Xray (+ geo files). Called on first connect or from Settings. */
    suspend fun installCore(force: Boolean = false, geo: GeoSource) = withContext(Dispatchers.IO) {
        val prev = _status.value
        try {
            if (force || !Paths.xrayExe.exists()) {
                check(process == null) { L("Остановите подключение перед обновлением ядра") }
                val arch = if (System.getProperty("os.arch").contains("aarch64")) "arm64-v8a" else "64"
                val zip = File(Paths.core, "xray.zip")
                download("https://github.com/XTLS/Xray-core/releases/latest/download/Xray-windows-$arch.zip", zip, L("Ядро Xray"))
                unzip(zip, Paths.core) { name ->
                    val base = name.substringAfterLast('/')
                    base.takeIf { it == "xray.exe" || (geo == GeoSource.XRAY && it.endsWith(".dat")) || (it.endsWith(".dat") && !File(Paths.core, it).exists()) }
                }
                zip.delete()
                log(L("Xray установлен в %1", Paths.core))
            }
            if (force || geo == GeoSource.RUNETFREEDOM && !File(Paths.core, ".geo-runetfreedom").exists()) updateGeo(geo)
            _version.value = readVersion()
        } finally {
            if (_status.value is CoreStatus.Preparing) _status.value = prev.takeIf { it !is CoreStatus.Preparing } ?: CoreStatus.Stopped
        }
    }

    suspend fun updateGeo(geo: GeoSource) = withContext(Dispatchers.IO) {
        val base = when (geo) {
            GeoSource.XRAY -> "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download"
            GeoSource.RUNETFREEDOM -> "https://github.com/runetfreedom/russia-v2ray-rules-dat/releases/latest/download"
        }
        download("$base/geoip.dat", File(Paths.core, "geoip.dat"), "geoip.dat")
        download("$base/geosite.dat", File(Paths.core, "geosite.dat"), "geosite.dat")
        File(Paths.core, ".geo-runetfreedom").let { if (geo == GeoSource.RUNETFREEDOM) it.writeText("1") else it.delete() }
        log(L("Гео-базы обновлены (%1)", geo.title))
    }

    suspend fun ensureWintun() = withContext(Dispatchers.IO) {
        if (Paths.wintunDll.exists()) return@withContext
        val zip = File(Paths.core, "wintun.zip")
        download("https://www.wintun.net/builds/wintun-0.14.1.zip", zip, L("Драйвер Wintun"))
        val arch = if (System.getProperty("os.arch").contains("aarch64")) "arm64" else "amd64"
        unzip(zip, Paths.core) { name -> "wintun.dll".takeIf { name == "wintun/bin/$arch/wintun.dll" } }
        zip.delete()
        check(Paths.wintunDll.exists()) { L("Не удалось распаковать wintun.dll") }
    }

    private fun readVersion(): String? = runCatching {
        if (!Paths.xrayExe.exists()) return null
        val p = ProcessBuilder(Paths.xrayExe.path, "version").redirectErrorStream(true).start()
        val first = p.inputStream.bufferedReader().readLine().orEmpty()
        p.waitFor()
        Regex("""Xray (\S+)""").find(first)?.groupValues?.get(1)
    }.getOrNull()

    // ---------------------------------------------------------------- run

    private fun xray(vararg args: String) = ProcessBuilder(Paths.xrayExe.path, *args)
        .directory(Paths.core)
        .redirectErrorStream(true)
        .also { it.environment()["XRAY_LOCATION_ASSET"] = Paths.core.path }

    /** Validates a config with `xray run -test` without starting anything. Returns error text or null. */
    fun testConfig(file: File): String? {
        val p = xray("run", "-test", "-c", file.path).start()
        val out = p.inputStream.bufferedReader().readText()
        return if (p.waitFor() == 0) null else out.lines().filter { it.isNotBlank() }.takeLast(4).joinToString("\n")
    }

    suspend fun start(configJson: String, apiPort: Int) = withContext(Dispatchers.IO) {
        check(process == null) { L("Ядро уже запущено") }
        _status.value = CoreStatus.Starting
        Paths.runConfig.writeText(configJson)
        testConfig(Paths.runConfig)?.let { err ->
            _status.value = CoreStatus.Failed(err); log(err, 'E'); error(err)
        }
        val p = xray("run", "-c", Paths.runConfig.path).start()
        process = p
        pidFile.writeText(p.pid().toString())
        scope.launch(Dispatchers.IO) {
            p.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val lvl = when {
                        "[Error]" in line -> 'E'; "[Warning]" in line -> 'W'; "[Debug]" in line -> 'D'; else -> 'I'
                    }
                    log(line.replace(Regex("""^\d{4}/\d\d/\d\d \d\d:\d\d:\d\d(\.\d+)? """), ""), lvl)
                }
            }
        }
        // give it a moment: a config that passed -test can still fail to bind ports / create the TUN
        delay(1200)
        if (!p.isAlive) {
            process = null; pidFile.delete()
            val err = _logs.value.takeLast(3).joinToString("\n") { it.text }.ifBlank { L("Xray завершился с кодом %1", p.exitValue()) }
            _status.value = CoreStatus.Failed(err)
            error(err)
        }
        _status.value = CoreStatus.Running(System.currentTimeMillis())
        _traffic.value = Traffic()
        startStats(apiPort)
        p.onExit().thenAccept {
            if (process === p) {
                process = null; pidFile.delete(); statsJob?.cancel()
                _status.value = CoreStatus.Failed(L("Xray неожиданно завершился (код %1)", it.exitValue()))
            }
        }
    }

    // ---------------------------------------------------------------- AmneziaWG engine

    /** Name of the AmneziaWG tunnel service we installed, if any. */
    private var awgTunnel: String? = null
    private val awgDir = File(System.getenv("ProgramFiles") ?: "C:/Program Files", "AmneziaWG")
    val amneziaInstalled get() = File(awgDir, "amneziawg.exe").exists()

    private fun awgCmd(vararg args: String): Pair<Int, String> {
        val p = ProcessBuilder(File(awgDir, "amneziawg.exe").path, *args).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        return p.waitFor() to out
    }

    /** Runs a full AmneziaWG tunnel through the official AmneziaWG service (admin required). */
    suspend fun startAmnezia(confText: String) = withContext(Dispatchers.IO) {
        check(process == null && awgTunnel == null) { L("Уже подключено") }
        check(amneziaInstalled) { L("AmneziaWG не установлен") }
        _status.value = CoreStatus.Starting
        val name = "MyProxyAWG"
        val dir = File(Paths.home, "awg").apply { mkdirs() }
        val conf = File(dir, "$name.conf").apply { writeText(confText) }
        // a stale service from a crash would block the install
        awgCmd("/uninstalltunnelservice", name)
        val (code, out) = awgCmd("/installtunnelservice", conf.path)
        if (code != 0) {
            val err = out.ifBlank { L("amneziawg.exe завершился с кодом %1", code) }
            _status.value = CoreStatus.Failed(err); log(err, 'E'); error(err)
        }
        awgTunnel = name
        File(Paths.home, "awg.active").writeText(name)
        log(L("AmneziaWG: туннель %1 запущен", name))
        _status.value = CoreStatus.Running(System.currentTimeMillis())
        _traffic.value = Traffic()
        startAwgStats(name)
    }

    private fun stopAmneziaBlocking() {
        val name = awgTunnel ?: File(Paths.home, "awg.active").takeIf { it.exists() }?.readText()?.trim() ?: return
        statsJob?.cancel()
        if (amneziaInstalled) awgCmd("/uninstalltunnelservice", name)
        awgTunnel = null
        File(Paths.home, "awg.active").delete()
        log(L("AmneziaWG: туннель %1 остановлен", name))
    }

    private fun startAwgStats(name: String) {
        statsJob?.cancel()
        statsJob = scope.launch(Dispatchers.IO) {
            var lastRx = -1L; var lastTx = -1L
            while (isActive) {
                runCatching {
                    val p = ProcessBuilder(File(awgDir, "awg.exe").path, "show", name, "transfer").redirectErrorStream(true).start()
                    val line = p.inputStream.bufferedReader().readText().lines().firstOrNull { it.isNotBlank() }; p.waitFor()
                    val parts = line?.split(Regex("\\s+")).orEmpty()
                    val rx = parts.getOrNull(1)?.trim()?.toLongOrNull() ?: return@runCatching
                    val tx = parts.getOrNull(2)?.trim()?.toLongOrNull() ?: return@runCatching
                    if (lastRx >= 0) {
                        val down = (rx - lastRx).coerceAtLeast(0); val up = (tx - lastTx).coerceAtLeast(0)
                        _traffic.update { t -> t.copy(up = up, down = down, totalUp = tx, totalDown = rx, history = (t.history + (up to down)).takeLast(60)) }
                    }
                    lastRx = rx; lastTx = tx
                }
                delay(1000)
            }
        }
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        if (awgTunnel != null) {
            _status.value = CoreStatus.Stopping
            stopAmneziaBlocking()
            _traffic.value = Traffic()
            _status.value = CoreStatus.Stopped
            return@withContext
        }
        val p = process ?: run { _status.value = CoreStatus.Stopped; return@withContext }
        _status.value = CoreStatus.Stopping
        process = null
        statsJob?.cancel()
        p.destroy()
        if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly().waitFor()
        pidFile.delete()
        _traffic.value = Traffic()
        _status.value = CoreStatus.Stopped
    }

    /** Synchronous variant for shutdown hooks. */
    fun stopBlocking() {
        runCatching { stopAmneziaBlocking() }
        process?.let { it.destroy(); if (!it.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) it.destroyForcibly() }
        process = null
        pidFile.delete()
    }

    private fun killOrphan() {
        val pid = pidFile.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: return
        ProcessHandle.of(pid).ifPresent { h ->
            if (h.info().command().orElse("").endsWith("xray.exe", ignoreCase = true)) h.destroyForcibly()
        }
        pidFile.delete()
    }

    private fun startStats(apiPort: Int) {
        statsJob?.cancel()
        statsJob = scope.launch(Dispatchers.IO) {
            val json = Json { ignoreUnknownKeys = true }
            while (isActive) {
                val t0 = System.nanoTime()
                runCatching {
                    val p = xray("api", "statsquery", "--server=127.0.0.1:$apiPort", "-pattern", "outbound>>>", "-reset").start()
                    val out = p.inputStream.bufferedReader().readText(); p.waitFor()
                    var up = 0L; var down = 0L
                    json.parseToJsonElement(out).jsonObject["stat"]?.jsonArray?.forEach { e ->
                        val o = e.jsonObject
                        val name = o["name"]?.jsonPrimitive?.content.orEmpty()
                        val v = o["value"]?.jsonPrimitive?.longOrNull ?: 0
                        // count only traffic that went through a proxy outbound (raw configs have their own tags)
                        val tag = name.split(">>>").getOrNull(1).orEmpty()
                        if (tag in setOf("direct", "block", "dns-out", "api") || tag.startsWith("direct") || tag.startsWith("block")) return@forEach
                        if (name.endsWith("uplink")) up += v else if (name.endsWith("downlink")) down += v
                    }
                    _traffic.update { t ->
                        t.copy(up = up, down = down, totalUp = t.totalUp + up, totalDown = t.totalDown + down,
                            history = (t.history + (up to down)).takeLast(60))
                    }
                }
                delay((1000 - (System.nanoTime() - t0) / 1_000_000).coerceAtLeast(100))
            }
        }
    }
}
