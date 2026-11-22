package dev.myproxy.parse

import dev.myproxy.data.Protocol
import dev.myproxy.data.ProxyNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64

/** Pieces of `scheme://userinfo@host:port?query#fragment`, tolerant of things java.net.URI rejects. */
internal data class RawUri(
    val scheme: String,
    val userInfo: String,
    val host: String,
    val port: Int,
    val query: Map<String, String>,
    val fragment: String,
)

object LinkParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    val supportedSchemes = setOf("vless", "vmess", "trojan", "ss", "socks", "socks5", "http", "https", "hysteria2", "hy2", "wireguard", "wg", "vpn")

    /** Parses every link found in [text] (one per line, or a base64 blob of lines). */
    fun parseMany(text: String, nameHint: String? = null): List<ProxyNode> {
        val body = text.trim().removePrefix("﻿")
        // whole-document formats first
        if (WgConfParser.looksLike(body)) return listOf(WgConfParser.parse(body, nameHint))
        if (XrayJsonParser.looksLike(body)) return runCatching { XrayJsonParser.parse(body) }.getOrDefault(emptyList())
        val lines = if (looksLikeLinks(body)) body else decodeBase64OrNull(body)?.let { d ->
            when {
                looksLikeLinks(d) -> d
                XrayJsonParser.looksLike(d) -> return runCatching { XrayJsonParser.parse(d) }.getOrDefault(emptyList())
                else -> null
            }
        } ?: body
        return lines.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .flatMap { line ->
                if (AmneziaParser.looksLike(line)) runCatching { AmneziaParser.parse(line) }.getOrDefault(emptyList()).asSequence()
                else listOfNotNull(runCatching { parse(line) }.getOrNull()).asSequence()
            }
            .toList()
    }

    private fun looksLikeLinks(s: String) = s.lineSequence().any { line ->
        if (AmneziaParser.looksLike(line)) return@any true
        val scheme = line.substringBefore("://", "").lowercase()
        scheme in supportedSchemes
    }

    fun parse(link: String): ProxyNode? {
        val scheme = link.substringBefore("://").lowercase()
        return when (scheme) {
            "vless" -> parseVlessLike(link, Protocol.VLESS)
            "trojan" -> parseVlessLike(link, Protocol.TROJAN)
            "vmess" -> parseVmess(link)
            "ss" -> parseShadowsocks(link)
            "socks", "socks5" -> parseSocksHttp(link, Protocol.SOCKS)
            "http", "https" -> parseSocksHttp(link, Protocol.HTTP)
            "hysteria2", "hy2" -> parseHysteria2(link)
            "wireguard", "wg" -> parseWireguard(link)
            else -> null
        }?.copy(rawLink = link)
    }

    // ---------------------------------------------------------------- vless / trojan

    private fun parseVlessLike(link: String, protocol: Protocol): ProxyNode {
        val u = splitUri(link)
        val q = u.query
        val security = q["security"]?.ifBlank { null } ?: if (protocol == Protocol.TROJAN) "tls" else "none"
        val network = (q["type"] ?: "tcp").let { if (it == "raw") "tcp" else it }
        return ProxyNode(
            name = u.fragment.ifBlank { u.host },
            protocol = protocol,
            address = u.host,
            port = u.port,
            uuid = if (protocol == Protocol.VLESS) u.userInfo else "",
            password = if (protocol == Protocol.TROJAN) u.userInfo else "",
            flow = q["flow"].orEmpty(),
            encryption = q["encryption"]?.ifBlank { null } ?: "none",
            network = network,
            headerType = q["headerType"]?.ifBlank { null } ?: "none",
            host = q["host"].orEmpty(),
            path = q["path"].orEmpty(),
            serviceName = q["serviceName"].orEmpty(),
            grpcMode = q["mode"]?.takeIf { network == "grpc" } ?: "gun",
            xhttpMode = q["mode"]?.takeIf { network == "xhttp" } ?: "auto",
            xhttpExtra = q["extra"].orEmpty(),
            security = security,
            sni = q["sni"] ?: q["peer"] ?: "",
            alpn = q["alpn"].orEmpty(),
            fingerprint = q["fp"].orEmpty(),
            publicKey = q["pbk"].orEmpty(),
            shortId = q["sid"].orEmpty(),
            spiderX = q["spx"].orEmpty(),
            allowInsecure = q["allowInsecure"] == "1" || q["insecure"] == "1" || q["allowInsecure"] == "true",
            pinnedPeerCertSha256 = q["pcs"].orEmpty(),
            verifyPeerCertByName = q["vcn"].orEmpty(),
            echConfigList = q["ech"].orEmpty(),
        )
    }

    // ---------------------------------------------------------------- vmess

    private fun parseVmess(link: String): ProxyNode {
        val payload = link.substringAfter("://").substringBefore("#")
        val decoded = decodeBase64OrNull(payload)
        if (decoded == null || !decoded.trimStart().startsWith("{")) {
            // some panels emit vmess in the vless-style URI form
            return parseVlessLike(link, Protocol.VLESS).copy(protocol = Protocol.VMESS, uuid = splitUri(link).userInfo)
        }
        val o = json.parseToJsonElement(decoded).jsonObject
        fun s(k: String) = (o[k] as? JsonPrimitive)?.content.orEmpty()
        val net = s("net").ifBlank { "tcp" }
        val tls = s("tls")
        return ProxyNode(
            name = s("ps").ifBlank { s("add") },
            protocol = Protocol.VMESS,
            address = s("add"),
            port = s("port").toIntOrNull() ?: 443,
            uuid = s("id"),
            alterId = s("aid").toIntOrNull() ?: 0,
            vmessSecurity = s("scy").ifBlank { "auto" },
            network = if (net == "raw") "tcp" else net,
            headerType = s("type").ifBlank { "none" },
            host = s("host"),
            path = s("path"),
            serviceName = if (net == "grpc") s("path") else "",
            security = if (tls == "tls" || tls == "reality") tls else "none",
            sni = s("sni"),
            alpn = s("alpn"),
            fingerprint = s("fp"),
        )
    }

    // ---------------------------------------------------------------- shadowsocks

    private fun parseShadowsocks(link: String): ProxyNode {
        val noScheme = link.substringAfter("://")
        val fragment = noScheme.substringAfter("#", "").let(::urlDecode)
        val main = noScheme.substringBefore("#").substringBefore("?")
        val (method, password, hostPort) = if (main.contains('@')) {
            // SIP002: base64(method:password)@host:port, or plain method:password@host:port
            val info = main.substringBeforeLast('@')
            val creds = decodeBase64OrNull(urlDecode(info))?.takeIf { it.contains(':') } ?: urlDecode(info)
            Triple(creds.substringBefore(':'), creds.substringAfter(':'), main.substringAfterLast('@'))
        } else {
            // legacy: base64(method:password@host:port)
            val d = decodeBase64OrNull(main) ?: error("bad ss link")
            val creds = d.substringBeforeLast('@')
            Triple(creds.substringBefore(':'), creds.substringAfter(':'), d.substringAfterLast('@'))
        }
        val (host, port) = splitHostPort(hostPort)
        return ProxyNode(
            name = fragment.ifBlank { host },
            protocol = Protocol.SHADOWSOCKS,
            address = host, port = port, method = method, password = password,
        )
    }

    // ---------------------------------------------------------------- socks / http

    private fun parseSocksHttp(link: String, protocol: Protocol): ProxyNode {
        val u = splitUri(link)
        var user = ""; var pass = ""
        if (u.userInfo.isNotEmpty()) {
            val creds = if (u.userInfo.contains(':')) u.userInfo else decodeBase64OrNull(u.userInfo) ?: u.userInfo
            user = creds.substringBefore(':'); pass = creds.substringAfter(':', "")
        }
        return ProxyNode(
            name = u.fragment.ifBlank { u.host }, protocol = protocol,
            address = u.host, port = u.port, username = user, password = pass,
            security = if (link.startsWith("https", true)) "tls" else "none",
        )
    }

    // ---------------------------------------------------------------- hysteria2

    private fun parseHysteria2(link: String): ProxyNode {
        val u = splitUri(link)
        val q = u.query
        return ProxyNode(
            name = u.fragment.ifBlank { u.host }, protocol = Protocol.HYSTERIA2,
            address = u.host, port = u.port,
            password = u.userInfo,
            network = "hysteria", security = "tls",
            sni = q["sni"].orEmpty(),
            alpn = q["alpn"].orEmpty(),
            allowInsecure = q["insecure"] == "1",
            pinnedPeerCertSha256 = q["pcs"] ?: q["pinSHA256"] ?: "",
            obfs = q["obfs"].orEmpty(),
            obfsPassword = q["obfs-password"].orEmpty(),
            upMbps = q["up"]?.filter(Char::isDigit)?.toIntOrNull() ?: 0,
            downMbps = q["down"]?.filter(Char::isDigit)?.toIntOrNull() ?: 0,
        )
    }

    // ---------------------------------------------------------------- wireguard

    private fun parseWireguard(link: String): ProxyNode {
        val u = splitUri(link)
        val q = u.query
        return ProxyNode(
            name = u.fragment.ifBlank { u.host }, protocol = Protocol.WIREGUARD,
            address = u.host, port = u.port,
            network = "udp",
            wgPrivateKey = u.userInfo,
            wgPublicKey = q["publickey"] ?: q["publicKey"] ?: "",
            wgPreSharedKey = q["presharedkey"] ?: q["psk"] ?: "",
            wgAddresses = q["address"] ?: q["ip"] ?: "10.0.0.2/32",
            wgReserved = q["reserved"].orEmpty(),
            wgMtu = q["mtu"]?.toIntOrNull() ?: 1420,
        )
    }

    // ---------------------------------------------------------------- helpers

    internal fun splitUri(link: String): RawUri {
        val scheme = link.substringBefore("://")
        var rest = link.substringAfter("://")
        val fragment = rest.substringAfter('#', "").let(::urlDecode)
        rest = rest.substringBefore('#')
        val queryStr = rest.substringAfter('?', "")
        rest = rest.substringBefore('?').trimEnd('/')
        val userInfo = if (rest.contains('@')) urlDecode(rest.substringBeforeLast('@')) else ""
        val hostPort = rest.substringAfterLast('@')
        val (host, port) = splitHostPort(hostPort)
        val query = queryStr.split('&').filter { it.isNotEmpty() }.associate {
            urlDecode(it.substringBefore('=')) to urlDecode(it.substringAfter('=', ""))
        }
        return RawUri(scheme, userInfo, host, port, query, fragment)
    }

    private fun splitHostPort(s: String): Pair<String, Int> {
        val clean = s.trimEnd('/')
        return if (clean.startsWith("[")) {
            clean.substringAfter('[').substringBefore(']') to (clean.substringAfter("]:", "").toIntOrNull() ?: 443)
        } else {
            clean.substringBeforeLast(':') to (clean.substringAfterLast(':', "").toIntOrNull() ?: 443)
        }
    }

    internal fun urlDecode(s: String): String = runCatching { URLDecoder.decode(s.replace("+", "%2B"), Charsets.UTF_8) }.getOrDefault(s)

    fun decodeBase64OrNull(s: String): String? {
        val t = s.trim().replace("\r", "").replace("\n", "").replace(" ", "")
        if (t.isEmpty()) return null
        val padded = t.padEnd((t.length + 3) / 4 * 4, '=')
        for (dec in listOf(Base64.getDecoder(), Base64.getUrlDecoder())) {
            val r = runCatching { String(dec.decode(padded), Charsets.UTF_8) }.getOrNull()
            if (r != null && r.none { it == '�' }) return r
        }
        return null
    }
}

object LinkExporter {
    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")
    private fun host(n: ProxyNode) = if (n.address.contains(':')) "[${n.address}]" else n.address

    private fun query(vararg pairs: Pair<String, String>) =
        pairs.filter { it.second.isNotBlank() }.joinToString("&") { "${it.first}=${enc(it.second)}" }

    fun toLink(n: ProxyNode): String = when (n.protocol) {
        Protocol.VLESS, Protocol.TROJAN -> {
            val user = if (n.protocol == Protocol.VLESS) n.uuid else n.password
            val q = query(
                "type" to n.network, "security" to n.security, "encryption" to n.encryption.takeIf { n.protocol == Protocol.VLESS }.orEmpty(),
                "flow" to n.flow, "sni" to n.sni, "fp" to n.fingerprint, "alpn" to n.alpn,
                "pbk" to n.publicKey, "sid" to n.shortId, "spx" to n.spiderX,
                "host" to n.host, "path" to n.path, "serviceName" to n.serviceName,
                "headerType" to n.headerType.takeIf { it != "none" }.orEmpty(),
                "mode" to when (n.network) { "grpc" -> n.grpcMode; "xhttp" -> n.xhttpMode; else -> "" },
                "extra" to n.xhttpExtra,
                "pcs" to n.pinnedPeerCertSha256, "vcn" to n.verifyPeerCertByName, "ech" to n.echConfigList,
            )
            "${n.protocol.name.lowercase()}://${enc(user)}@${host(n)}:${n.port}?$q#${enc(n.name)}"
        }
        Protocol.VMESS -> {
            val o = JsonObject(mapOf(
                "v" to JsonPrimitive("2"), "ps" to JsonPrimitive(n.name), "add" to JsonPrimitive(n.address),
                "port" to JsonPrimitive(n.port.toString()), "id" to JsonPrimitive(n.uuid), "aid" to JsonPrimitive(n.alterId.toString()),
                "scy" to JsonPrimitive(n.vmessSecurity), "net" to JsonPrimitive(n.network), "type" to JsonPrimitive(n.headerType),
                "host" to JsonPrimitive(n.host), "path" to JsonPrimitive(if (n.network == "grpc") n.serviceName else n.path),
                "tls" to JsonPrimitive(if (n.security == "none") "" else n.security), "sni" to JsonPrimitive(n.sni),
                "alpn" to JsonPrimitive(n.alpn), "fp" to JsonPrimitive(n.fingerprint),
            ))
            "vmess://" + Base64.getEncoder().encodeToString(o.toString().toByteArray())
        }
        Protocol.SHADOWSOCKS -> {
            val creds = Base64.getUrlEncoder().withoutPadding().encodeToString("${n.method}:${n.password}".toByteArray())
            "ss://$creds@${host(n)}:${n.port}#${enc(n.name)}"
        }
        Protocol.SOCKS, Protocol.HTTP -> {
            val scheme = if (n.protocol == Protocol.SOCKS) "socks" else if (n.security == "tls") "https" else "http"
            val auth = if (n.username.isNotEmpty()) "${enc(n.username)}:${enc(n.password)}@" else ""
            "$scheme://$auth${host(n)}:${n.port}#${enc(n.name)}"
        }
        Protocol.HYSTERIA2 -> {
            val q = query("sni" to n.sni, "alpn" to n.alpn, "obfs" to n.obfs, "obfs-password" to n.obfsPassword,
                "pcs" to n.pinnedPeerCertSha256)
            "hysteria2://${enc(n.password)}@${host(n)}:${n.port}?$q#${enc(n.name)}"
        }
        Protocol.WIREGUARD -> {
            val q = query("publickey" to n.wgPublicKey, "presharedkey" to n.wgPreSharedKey, "address" to n.wgAddresses,
                "reserved" to n.wgReserved, "mtu" to n.wgMtu.toString())
            "wireguard://${enc(n.wgPrivateKey)}@${host(n)}:${n.port}?$q#${enc(n.name)}"
        }
    }
}
