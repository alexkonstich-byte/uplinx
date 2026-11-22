package dev.uplinx.core

import dev.uplinx.data.AppSettings
import dev.uplinx.data.ConnectMode
import dev.uplinx.data.Paths
import dev.uplinx.data.Protocol
import dev.uplinx.data.ProxyNode
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*
import java.io.File
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Real delay ("URL test") for many servers at once without touching the system:
 * one throwaway Xray process gets a local HTTP inbound per server, each routed to that server,
 * and we time a request to the probe URL through each inbound.
 */
object LatencyTester {
    data class Target(val key: String, val outbound: JsonObject)

    /** Expands a node into probe targets: one for plain servers, one per member for balancers. */
    fun targets(node: ProxyNode, s: AppSettings): List<Target> {
        val probeSettings = s.copy(connectMode = ConnectMode.SYSTEM_PROXY, mux = false)
        return when {
            node.needsAmneziaEngine -> emptyList()
            node.protocol == Protocol.XRAY_CONFIG -> {
                val outs = (Json.parseToJsonElement(node.rawConfig).jsonObject["outbounds"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                val wanted = node.balancerMembers.ifEmpty { outs.firstOrNull()?.get("tag")?.jsonPrimitive?.contentOrNull?.let(::listOf).orEmpty() }
                outs.filter { it["tag"]?.jsonPrimitive?.contentOrNull in wanted }
                    // chained outbounds need their dialer: skip those, members are usually plain
                    .filter { it["proxySettings"] == null }
                    .map { Target("${node.id}#${it["tag"]!!.jsonPrimitive.content}", it) }
            }
            else -> listOf(Target(node.id, XrayConfig.outbound(node, probeSettings)))
        }
    }

    private fun freePorts(n: Int): List<Int> {
        val sockets = (0 until n).map { ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()) }
        return sockets.map { it.localPort }.also { sockets.forEach { s -> s.close() } }
    }

    private var physical: Pair<Long, String?>? = null

    /**
     * Friendly name of the adapter that carries the default route, skipping VPN/TUN adapters
     * (Happ, wintun, WireGuard, TAP…). Probes are bound to it so another VPN — or our own TUN —
     * can't fake or reroute the measurement. Cached for a minute.
     */
    fun physicalInterface(): String? {
        physical?.let { (t, name) -> if (System.currentTimeMillis() - t < 60_000) return name }
        val ps = "Get-NetRoute -DestinationPrefix 0.0.0.0/0 -ErrorAction SilentlyContinue | Sort-Object RouteMetric | " +
            "ForEach-Object { Get-NetAdapter -InterfaceIndex \$_.ifIndex -ErrorAction SilentlyContinue } | " +
            "Where-Object { \$_.Status -eq 'Up' -and \$_.InterfaceDescription -notmatch 'tun|tunnel|wintun|wireguard|tap|vpn|virtual' -and \$_.Name -notmatch 'tun|vpn|xray|happ|uplinx' } | " +
            "Select-Object -First 1 -ExpandProperty Name"
        val name = runCatching {
            val p = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", ps).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText().trim().lineSequence().lastOrNull()?.trim()
            p.waitFor()
            out?.takeIf { it.isNotBlank() && !it.contains("Exception") }
        }.getOrNull()
        physical = System.currentTimeMillis() to name
        return name
    }

    private fun bind(outbound: JsonObject, iface: String?): JsonObject {
        if (iface == null) return outbound
        val stream = (outbound["streamSettings"] as? JsonObject) ?: JsonObject(emptyMap())
        val sockopt = (stream["sockopt"] as? JsonObject) ?: JsonObject(emptyMap())
        return JsonObject(outbound + ("streamSettings" to JsonObject(stream + ("sockopt" to JsonObject(sockopt + ("interface" to JsonPrimitive(iface)))))))
    }

    /** Binds an outbound to the physical adapter (when one is found). Used by the speed test too. */
    fun bindToPhysical(outbound: JsonObject): JsonObject = bind(outbound, physicalInterface())

    /** Parallelism like Chumpy: 10, or 25 for long lists. */
    fun concurrencyFor(n: Int) = if (n > 45) 25 else 10

    /**
     * Probes [targets] and reports each result through [onResult] (ms, or -1). Runs at most 120 targets per Xray process.
     */
    suspend fun probe(targets: List<Target>, url: String, onResult: (String, Int) -> Unit) = coroutineScope {
        if (!Paths.xrayExe.exists() || targets.isEmpty()) return@coroutineScope
        val sem = Semaphore(concurrencyFor(targets.size))
        val iface = physicalInterface()
        targets.chunked(120).forEach { chunk ->
            val ports = freePorts(chunk.size)
            val cfg = buildJsonObject {
                putJsonObject("log") { put("loglevel", "none") }
                putJsonArray("inbounds") {
                    chunk.forEachIndexed { i, _ ->
                        addJsonObject { put("tag", "in$i"); put("protocol", "http"); put("listen", "127.0.0.1"); put("port", ports[i]) }
                    }
                }
                putJsonArray("outbounds") {
                    chunk.forEachIndexed { i, t -> add(bind(JsonObject(t.outbound + ("tag" to JsonPrimitive("out$i"))), iface)) }
                }
                putJsonObject("routing") {
                    putJsonArray("rules") {
                        chunk.indices.forEach { i ->
                            addJsonObject { put("type", "field"); putJsonArray("inboundTag") { add("in$i") }; put("outboundTag", "out$i") }
                        }
                    }
                }
            }
            val file = File.createTempFile("probe", ".json", Paths.home).apply { writeText(cfg.toString()); deleteOnExit() }
            val proc = ProcessBuilder(Paths.xrayExe.path, "run", "-c", file.path).directory(Paths.core)
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .also { it.environment()["XRAY_LOCATION_ASSET"] = Paths.core.path }.start()
            try {
                delay(700) // let inbounds bind
                if (!proc.isAlive) { chunk.forEach { onResult(it.key, -1) }; return@forEach }
                chunk.mapIndexed { i, t ->
                    launch(Dispatchers.IO) {
                        sem.withPermit { onResult(t.key, measure(ports[i], url)) }
                    }
                }.joinAll()
            } finally {
                proc.destroyForcibly()
                file.delete()
            }
        }
    }

    private fun measure(port: Int, url: String): Int = runCatching {
        val client = HttpClient.newBuilder()
            .proxy(ProxySelector.of(InetSocketAddress("127.0.0.1", port)))
            .connectTimeout(Duration.ofSeconds(6)).build()
        val req = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(8)).GET().build()
        // first request pays for the handshake; the second is the real round trip, as other clients report it
        client.send(req, HttpResponse.BodyHandlers.discarding())
        val t0 = System.nanoTime()
        val r = client.send(req, HttpResponse.BodyHandlers.discarding())
        if (r.statusCode() >= 500) -1 else ((System.nanoTime() - t0) / 1_000_000).toInt().coerceAtLeast(1)
    }.getOrDefault(-1)
}
