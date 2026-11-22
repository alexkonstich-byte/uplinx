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

    private fun get(url: String, userAgent: String): HttpResponse<String> {
        val req = HttpRequest.newBuilder(URI(url.trim()))
            .timeout(Duration.ofSeconds(30))
            .header("User-Agent", userAgent)
            .header("Accept", "*/*")
            .GET().build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() !in 200..299) error("HTTP ${resp.statusCode()}")
        return resp
    }

    fun fetch(sub: Subscription, userAgent: String): FetchResult {
        val resp = get(sub.url, userAgent)
        val headers = resp.headers()
        fun h(name: String) = headers.firstValue(name).orElse("")

        var parsed = Importer.parse(resp.body())
        // nested subscriptions: a subscription listing other subscription URLs (one level deep)
        parsed.nestedSubscriptions.distinct().take(20).forEach { url ->
            runCatching { parsed += Importer.parse(get(url, userAgent).body()).copy(nestedSubscriptions = emptyList()) }
        }
        val nodes = parsed.nodes.map { it.copy(subscriptionId = sub.id) }
        if (nodes.isEmpty()) error("В подписке не найдено поддерживаемых серверов")

        var updated = sub.copy(updatedAt = System.currentTimeMillis(), lastError = "")
        parseUserInfo(h("subscription-userinfo"))?.let { (up, down, total, exp) ->
            updated = updated.copy(upload = up, download = down, total = total, expire = exp)
        }
        decodeHeader(h("profile-title")).takeIf { it.isNotBlank() }?.let {
            if (sub.name == sub.url || sub.name.isBlank() || sub.name == "Новая подписка") updated = updated.copy(name = it)
        }
        h("profile-update-interval").toIntOrNull()?.let { updated = updated.copy(autoUpdateHours = it) }
        val links = linkedMapOf<String, String>()
        decodeHeader(h("support-url")).takeIf { it.isNotBlank() }?.let { links["Поддержка"] = it; updated = updated.copy(supportUrl = it) }
        decodeHeader(h("profile-web-page-url")).takeIf { it.isNotBlank() }?.let { links["Сайт"] = it; updated = updated.copy(webPageUrl = it) }
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
