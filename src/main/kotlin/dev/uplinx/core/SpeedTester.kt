package dev.uplinx.core

import dev.uplinx.data.AppSettings
import dev.uplinx.data.Paths
import dev.uplinx.data.ProxyNode
import dev.uplinx.data.SpeedResult
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.File
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

enum class SpeedPhase { PREPARING, PING, DOWNLOAD, UPLOAD, DONE }

/** Live state of a running test: current phase and instantaneous throughput. */
data class SpeedProgress(val phase: SpeedPhase, val mbps: Double = 0.0, val fraction: Float = 0f,
                         val down: Double? = null, val up: Double? = null, val ping: Int? = null)

/**
 * Download / upload / ping of any server without connecting to it: a throwaway Xray with a single local
 * HTTP inbound routed to that server (bound to the physical adapter), and Cloudflare's speed endpoints.
 */
object SpeedTester {
    private const val DOWN_URL = "https://speed.cloudflare.com/__down?bytes=200000000"
    private const val UP_URL = "https://speed.cloudflare.com/__up"
    private const val DOWN_SECONDS = 10.0
    private const val UP_BYTES = 25_000_000L
    private const val UP_SECONDS = 8.0

    private fun mbps(bytes: Long, nanos: Long) = if (nanos <= 0) 0.0 else bytes * 8.0 / 1e6 / (nanos / 1e9)

    suspend fun run(node: ProxyNode, settings: AppSettings, onProgress: (SpeedProgress) -> Unit): SpeedResult = withContext(Dispatchers.IO) {
        onProgress(SpeedProgress(SpeedPhase.PREPARING))
        check(Paths.xrayExe.exists()) { "core missing" }
        val target = LatencyTester.targets(node, settings).firstOrNull() ?: error("This server can't be tested through Xray")
        val port = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { it.localPort }
        val outbound = LatencyTester.bindToPhysical(JsonObject(target.outbound + ("tag" to JsonPrimitive("out"))))
        val cfg = buildJsonObject {
            putJsonObject("log") { put("loglevel", "none") }
            putJsonArray("inbounds") { addJsonObject { put("tag", "in"); put("protocol", "http"); put("listen", "127.0.0.1"); put("port", port) } }
            putJsonArray("outbounds") { add(outbound) }
        }
        val file = File.createTempFile("speed", ".json", Paths.home).apply { writeText(cfg.toString()); deleteOnExit() }
        val proc = ProcessBuilder(Paths.xrayExe.path, "run", "-c", file.path).directory(Paths.core)
            .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .also { it.environment()["XRAY_LOCATION_ASSET"] = Paths.core.path }.start()
        try {
            delay(700)
            check(proc.isAlive) { "Xray didn't start" }
            val client = HttpClient.newBuilder().proxy(ProxySelector.of(InetSocketAddress("127.0.0.1", port)))
                .connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NORMAL).build()

            // ping: warm-up, then best of three
            onProgress(SpeedProgress(SpeedPhase.PING))
            val pingReq = HttpRequest.newBuilder(URI(settings.pingUrl)).timeout(Duration.ofSeconds(8)).GET().build()
            runCatching { client.send(pingReq, HttpResponse.BodyHandlers.discarding()) }
            val ping = (1..3).mapNotNull {
                runCatching { val t = System.nanoTime(); client.send(pingReq, HttpResponse.BodyHandlers.discarding()); ((System.nanoTime() - t) / 1_000_000).toInt() }.getOrNull()
            }.minOrNull() ?: -1
            onProgress(SpeedProgress(SpeedPhase.PING, ping = ping))

            // download: stream for up to DOWN_SECONDS
            val down = run {
                val req = HttpRequest.newBuilder(URI(DOWN_URL)).timeout(Duration.ofSeconds(30)).GET().build()
                val resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream())
                val buf = ByteArray(256 * 1024)
                var total = 0L
                val t0 = System.nanoTime()
                var lastT = t0; var lastBytes = 0L
                resp.body().use { input ->
                    while (isActive) {
                        val n = input.read(buf); if (n < 0) break
                        total += n
                        val now = System.nanoTime()
                        if (now - lastT > 200_000_000) {
                            val inst = mbps(total - lastBytes, now - lastT)
                            onProgress(SpeedProgress(SpeedPhase.DOWNLOAD, inst, ((now - t0) / 1e9 / DOWN_SECONDS).toFloat().coerceAtMost(1f), ping = ping))
                            lastT = now; lastBytes = total
                        }
                        if ((now - t0) / 1e9 >= DOWN_SECONDS) break
                    }
                }
                mbps(total, System.nanoTime() - t0)
            }
            onProgress(SpeedProgress(SpeedPhase.DOWNLOAD, down, 1f, down = down, ping = ping))

            // upload: a generated body, timed as it is read by the client
            val up = run {
                var sent = 0L
                val t0 = System.nanoTime()
                var lastT = t0; var lastBytes = 0L
                val chunk = ByteArray(64 * 1024)
                val stream = object : InputStream() {
                    override fun read(): Int = if (sent >= UP_BYTES) -1 else { sent++; 0 }
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (sent >= UP_BYTES || (System.nanoTime() - t0) / 1e9 > UP_SECONDS) return -1
                        val n = minOf(len.toLong(), chunk.size.toLong(), UP_BYTES - sent).toInt()
                        System.arraycopy(chunk, 0, b, off, n)
                        sent += n
                        val now = System.nanoTime()
                        if (now - lastT > 200_000_000) {
                            onProgress(SpeedProgress(SpeedPhase.UPLOAD, mbps(sent - lastBytes, now - lastT), ((now - t0) / 1e9 / UP_SECONDS).toFloat().coerceAtMost(1f), down = down, ping = ping))
                            lastT = now; lastBytes = sent
                        }
                        return n
                    }
                }
                val req = HttpRequest.newBuilder(URI(UP_URL)).timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/octet-stream")
                    .POST(HttpRequest.BodyPublishers.ofInputStream { stream }).build()
                runCatching { client.send(req, HttpResponse.BodyHandlers.discarding()) }
                mbps(sent, System.nanoTime() - t0)
            }
            val result = SpeedResult(node.id, node.name, down, up, ping, System.currentTimeMillis())
            onProgress(SpeedProgress(SpeedPhase.DONE, down = down, up = up, ping = ping))
            result
        } finally {
            proc.destroyForcibly()
            file.delete()
        }
    }
}
