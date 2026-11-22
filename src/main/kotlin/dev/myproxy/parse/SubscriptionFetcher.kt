package dev.myproxy.parse

import dev.myproxy.data.ProxyNode
import dev.myproxy.data.Subscription
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

data class FetchResult(val subscription: Subscription, val nodes: List<ProxyNode>)

object SubscriptionFetcher {
    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(15))
        .build()

    fun fetch(sub: Subscription, userAgent: String): FetchResult {
        val req = HttpRequest.newBuilder(URI(sub.url.trim()))
            .timeout(Duration.ofSeconds(30))
            .header("User-Agent", userAgent)
            .header("Accept", "*/*")
            .GET().build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() !in 200..299) error("HTTP ${resp.statusCode()}")
        val headers = resp.headers()
        fun h(name: String) = headers.firstValue(name).orElse("")

        val nodes = LinkParser.parseMany(resp.body()).map { it.copy(subscriptionId = sub.id) }
        if (nodes.isEmpty()) error("В подписке не найдено поддерживаемых серверов")

        var updated = sub.copy(updatedAt = System.currentTimeMillis(), lastError = "")
        parseUserInfo(h("subscription-userinfo"))?.let { (up, down, total, exp) ->
            updated = updated.copy(upload = up, download = down, total = total, expire = exp)
        }
        decodeHeader(h("profile-title")).takeIf { it.isNotBlank() }?.let {
            // only replace the name if the user did not rename it themselves
            if (sub.name == sub.url || sub.name.isBlank() || sub.name == "Новая подписка") updated = updated.copy(name = it)
        }
        h("profile-update-interval").toIntOrNull()?.let { updated = updated.copy(autoUpdateHours = it) }
        decodeHeader(h("support-url")).takeIf { it.isNotBlank() }?.let { updated = updated.copy(supportUrl = it) }
        decodeHeader(h("profile-web-page-url")).takeIf { it.isNotBlank() }?.let { updated = updated.copy(webPageUrl = it) }
        decodeHeader(h("announce")).let { updated = updated.copy(announce = it) }
        return FetchResult(updated, nodes)
    }

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
