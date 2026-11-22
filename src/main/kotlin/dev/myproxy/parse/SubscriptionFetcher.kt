package dev.myproxy.parse

import dev.myproxy.data.Paths
import dev.myproxy.data.ProxyNode
import dev.myproxy.data.RouteRule
import dev.myproxy.data.Subscription
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

data class FetchResult(val subscription: Subscription, val nodes: List<ProxyNode>, val rules: List<RouteRule> = emptyList())

object SubscriptionFetcher {
    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(15))
        .build()

    /** Stable per-install device id; panels with device limits (Remnawave HWID) require it. */
    var hwid: String = ""

    private fun get(url: String, userAgent: String): HttpResponse<String> {
        val b = HttpRequest.newBuilder(URI(url.trim()))
            .timeout(Duration.ofSeconds(30))
            .header("User-Agent", userAgent)
            .header("Accept", "*/*")
            .header("x-device-os", "Windows")
            .header("x-ver-os", System.getProperty("os.version") ?: "10.0")
            .header("x-device-model", "PC")
            .header("Accept-Language", if (dev.myproxy.I18n.lang == dev.myproxy.Lang.RU) "ru" else "en")
        if (hwid.isNotBlank()) b.header("x-hwid", hwid)
        val resp = client.send(b.GET().build(), HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() !in 200..299) error("HTTP ${resp.statusCode()}")
        return resp
    }

    /**
     * Panels answer unknown clients with a stub ("App not supported", 0.0.0.0:1) or nothing at all.
     * Such an answer is not a real server list.
     */
    fun isStub(nodes: List<ProxyNode>): Boolean = nodes.isEmpty() || nodes.all { n ->
        n.port <= 1 || n.address in setOf("0.0.0.0", "127.0.0.1", "localhost", "::", "") ||
            n.name.contains("not supported", ignoreCase = true) || n.name.contains("не поддерж", ignoreCase = true)
    }

    /** User agents tried in order when the configured one gets a stub. */
    val fallbackAgents = listOf("Happ/3.1.0", "v2rayN/7.13", "sing-box 1.12.0", "clash-verge/v2.3.0", "v2rayNG/1.10.5")

    /** Fetches with [userAgent]; on a stub retries with the fallbacks and reports which agent worked. */
    fun fetch(sub: Subscription, userAgent: String): FetchResult {
        val first = sub.userAgent.ifBlank { userAgent }
        val agents = (listOf(first) + fallbackAgents).distinct()
        var lastError: Throwable? = null
        for (ua in agents) {
            val r = runCatching { fetchWith(sub, ua) }
            r.onFailure { lastError = it }
            val res = r.getOrNull() ?: continue
            if (!isStub(res.nodes)) {
                return if (ua == userAgent && sub.userAgent.isBlank()) res
                else res.copy(subscription = res.subscription.copy(userAgent = if (ua == userAgent) "" else ua))
            }
        }
        throw lastError ?: IllegalStateException(dev.myproxy.L("Сервис не отдал серверы ни одному клиенту. Возможно, подписка истекла или превышен лимит устройств"))
    }

    private fun fetchWith(sub: Subscription, userAgent: String): FetchResult {
        val resp = get(sub.url, userAgent)
        val headers = resp.headers()
        fun h(name: String) = headers.firstValue(name).orElse("")

        var parsed = Importer.parse(resp.body())
        // nested subscriptions: a subscription listing other subscription URLs (one level deep)
        parsed.nestedSubscriptions.distinct().take(20).forEach { url ->
            runCatching { parsed += Importer.parse(get(url, userAgent).body()).copy(nestedSubscriptions = emptyList()) }
        }
        val nodes = parsed.nodes.map { it.copy(subscriptionId = sub.id) }
        if (nodes.isEmpty()) error(dev.myproxy.L("В подписке не найдено поддерживаемых серверов"))

        var updated = sub.copy(updatedAt = System.currentTimeMillis(), lastError = "")
        parseUserInfo(h("subscription-userinfo"))?.let { (up, down, total, exp) ->
            updated = updated.copy(upload = up, download = down, total = total, expire = exp)
        }
        decodeHeader(h("profile-title")).takeIf { it.isNotBlank() }?.let {
            if (sub.name == sub.url || sub.name.isBlank() || sub.name == "Новая подписка" || sub.name == "New subscription") updated = updated.copy(name = it)
        }
        h("profile-update-interval").toIntOrNull()?.let { updated = updated.copy(autoUpdateHours = it) }
        val links = linkedMapOf<String, String>()
        decodeHeader(h("support-url")).takeIf { it.isNotBlank() }?.let { links["support"] = it; updated = updated.copy(supportUrl = it) }
        decodeHeader(h("profile-web-page-url")).takeIf { it.isNotBlank() }?.let { links["website"] = it; updated = updated.copy(webPageUrl = it) }
        updated = updated.copy(links = links, announce = decodeHeader(h("announce")))

        // panel-pushed routing profile (header carries a routing link or the base64 JSON itself)
        val routingHeader = decodeHeader(h("routing"))
        val profile = parsed.routingProfile
            ?: HappRouting.extract(routingHeader)
            ?: LinkParser.decodeBase64OrNull(routingHeader)?.takeIf { it.trimStart().startsWith("{") }
            ?: routingHeader.takeIf { it.trimStart().startsWith("{") }
        updated = updated.copy(routingProfile = profile.orEmpty())

        decodeHeader(h("profile-logo")).takeIf { it.startsWith("http") }?.let { url ->
            downloadLogo(sub.id, url, userAgent)?.let { updated = updated.copy(logoFile = it.path) }
        }
        return FetchResult(updated, nodes, parsed.rules)
    }

    /** PNG/JPEG/SVG up to 2 MB, cached in app data. */
    private fun downloadLogo(id: String, url: String, userAgent: String): File? = runCatching {
        val req = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(20)).header("User-Agent", userAgent).GET().build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray())
        val bytes = resp.body()
        if (resp.statusCode() !in 200..299 || bytes.size > 2 * 1024 * 1024 || bytes.isEmpty()) return null
        val type = resp.headers().firstValue("content-type").orElse("")
        val head = String(bytes.copyOfRange(0, minOf(256, bytes.size)), Charsets.UTF_8)
        val ext = when {
            "svg" in type || head.contains("<svg") -> "svg"
            bytes.size > 3 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> "png"
            bytes.size > 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "jpg"
            else -> return null
        }
        val dir = File(Paths.home, "logos").apply { mkdirs() }
        dir.listFiles { f -> f.nameWithoutExtension == id }?.forEach { it.delete() }
        File(dir, "$id.$ext").apply { writeBytes(bytes) }
    }.getOrNull()

    /** `upload=1; download=2; total=3; expire=4` */
    fun parseUserInfo(v: String): List<Long>? {
        if (v.isBlank()) return null
        val map = v.split(';').associate { part ->
            part.substringBefore('=').trim().lowercase() to (part.substringAfter('=', "").trim().toDoubleOrNull()?.toLong() ?: 0L)
        }
        return listOf(map["upload"] ?: 0, map["download"] ?: 0, map["total"] ?: 0, map["expire"] ?: 0)
    }

    fun decodeHeader(v: String): String =
        if (v.startsWith("base64:")) LinkParser.decodeBase64OrNull(v.removePrefix("base64:")) ?: v else v
}
