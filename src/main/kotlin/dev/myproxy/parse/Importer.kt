package dev.myproxy.parse

import dev.myproxy.data.*
import kotlinx.serialization.json.*
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

/** Everything one piece of text can contain. */
data class ImportResult(
    val nodes: List<ProxyNode> = emptyList(),
    val rules: List<RouteRule> = emptyList(),
    /** Subscription URLs found inside a subscription (nested subscriptions). */
    val nestedSubscriptions: List<String> = emptyList(),
    /** A routing profile (Happ-compatible JSON) found in the text. */
    val routingProfile: String? = null,
) {
    operator fun plus(o: ImportResult) = ImportResult(nodes + o.nodes, rules + o.rules, nestedSubscriptions + o.nestedSubscriptions, o.routingProfile ?: routingProfile)
    val isEmpty get() = nodes.isEmpty() && rules.isEmpty() && nestedSubscriptions.isEmpty() && routingProfile == null
}

/** One entry point for every supported format. Order matters: whole-document formats first. */
object Importer {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; allowComments = true; allowTrailingComma = true }

    fun parse(text: String, nameHint: String? = null): ImportResult {
        val body = text.trim().removePrefix("﻿")
        if (body.isEmpty()) return ImportResult()
        HappRouting.extract(body)?.let { return ImportResult(routingProfile = it) }
        if (WgConfParser.looksLike(body)) return ImportResult(listOf(WgConfParser.parse(body, nameHint)))
        // JSON / JSONC (leading comments allowed)
        val noLeadingComments = body.replace(Regex("""^(?:\s*//[^\n]*\n|\s*/\*[\s\S]*?\*/)*"""), "").trimStart()
        if (noLeadingComments.startsWith("{") || noLeadingComments.startsWith("[")) parseJsonDocument(body)?.let { return it }
        if (ClashParser.looksLike(body)) return runCatching { ClashParser.parse(body) }.getOrDefault(ImportResult())
        // base64 blob of any of the above
        if (!body.contains("://") && !body.contains('\n')) {
            LinkParser.decodeBase64OrNull(body)?.takeIf { it.isNotBlank() && it != body }?.let { d ->
                val r = parse(d, nameHint)
                if (!r.isEmpty) return r
            }
        }
        return parseLines(body)
    }

    private fun parseJsonDocument(body: String): ImportResult? {
        val el = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return null
        // JSON array of share links
        if (el is JsonArray && el.isNotEmpty() && el.all { it is JsonPrimitive && it.isString }) {
            return parseLines(el.joinToString("\n") { it.jsonPrimitive.content })
        }
        val objs = if (el is JsonArray) el.mapNotNull { it as? JsonObject } else listOfNotNull(el as? JsonObject)
        if (objs.isEmpty()) return null
        // sing-box: outbounds with "type"
        if (objs.any { o -> o["outbounds"]?.let { it is JsonArray && it.any { x -> (x as? JsonObject)?.containsKey("type") == true } } == true }) {
            return ImportResult(objs.flatMap { SingBoxParser.parse(it) })
        }
        if (objs.any { "outbounds" in it }) return ImportResult(objs.flatMap { XrayJsonParser.parseConfig(it) })
        return null
    }

    private val poolLine = Regex("""^([\w.-]+|\[[0-9a-fA-F:]+]):(\d{1,5})(?::([^:\s]+):(\S+))?$""")
    private val poolAuthLine = Regex("""^([^:@\s]+):([^@\s]+)@([\w.-]+):(\d{1,5})$""")

    private fun parseLines(body: String): ImportResult {
        var result = ImportResult()
        body.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }.forEach { line ->
            val scheme = line.substringBefore("://", "").lowercase()
            result += when {
                AmneziaParser.looksLike(line) -> ImportResult(runCatching { AmneziaParser.parse(line) }.getOrDefault(emptyList()))
                HappRouting.extract(line) != null -> ImportResult(routingProfile = HappRouting.extract(line))
                // a plain web URL inside a subscription is a nested subscription
                (scheme == "http" || scheme == "https") && !line.contains('@') && line.substringAfter("://").contains('/') ->
                    ImportResult(nestedSubscriptions = listOf(line))
                scheme in LinkParser.supportedSchemes -> ImportResult(listOfNotNull(runCatching { LinkParser.parse(line) }.getOrNull()))
                else -> ImportResult(listOfNotNull(parsePoolLine(line)))
            }
        }
        return result
    }

    /** `host:port`, `host:port:user:pass` or `user:pass@host:port` — proxy pools sold as plain lists. */
    private fun parsePoolLine(line: String): ProxyNode? {
        poolLine.matchEntire(line)?.let { m ->
            val host = m.groupValues[1].trim('[', ']'); val port = m.groupValues[2].toInt()
            if (port !in 1..65535) return null
            return ProxyNode(name = "$host:$port", protocol = Protocol.SOCKS, address = host, port = port,
                username = m.groupValues[3], password = m.groupValues[4], rawLink = line)
        }
        poolAuthLine.matchEntire(line)?.let { m ->
            val port = m.groupValues[4].toInt()
            return ProxyNode(name = "${m.groupValues[3]}:$port", protocol = Protocol.SOCKS, address = m.groupValues[3], port = port,
                username = m.groupValues[1], password = m.groupValues[2], rawLink = line)
        }
        return null
    }
}

// ======================================================================== Clash / Mihomo

object ClashParser {
    fun looksLike(text: String) = Regex("""(?m)^(proxies|proxy-providers|rules)\s*:""").containsMatchIn(text)

    @Suppress("UNCHECKED_CAST")
    fun parse(text: String): ImportResult {
        val root = Yaml(SafeConstructor(LoaderOptions())).load<Any?>(text) as? Map<String, Any?> ?: return ImportResult()
        val proxies = (root["proxies"] as? List<*>).orEmpty().mapNotNull { (it as? Map<String, Any?>)?.let(::proxy) }
        val rules = (root["rules"] as? List<*>).orEmpty().mapNotNull { (it as? String)?.let(::rule) }
        return ImportResult(proxies, rules)
    }

    private fun Map<String, Any?>.s(k: String) = this[k]?.toString().orEmpty()
    private fun Map<String, Any?>.b(k: String) = this[k] == true || this[k]?.toString() == "true"
    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.m(k: String) = this[k] as? Map<String, Any?>

    private fun proxy(p: Map<String, Any?>): ProxyNode? {
        val type = p.s("type").lowercase()
        val base = ProxyNode(name = p.s("name"), protocol = Protocol.VLESS, address = p.s("server"), port = p.s("port").toIntOrNull() ?: return null)
        val tls = p.b("tls")
        val reality = p.m("reality-opts")
        val network = p.s("network").ifBlank { "tcp" }.let { if (it == "h2" || it == "http") "xhttp" else it }
        val ws = p.m("ws-opts"); val grpc = p.m("grpc-opts")
        @Suppress("UNCHECKED_CAST") val wsHeaders = ws?.get("headers") as? Map<String, Any?>
        fun transport(n: ProxyNode) = n.copy(
            network = network,
            security = if (reality != null) "reality" else if (tls || type == "trojan") "tls" else "none",
            sni = p.s("servername").ifBlank { p.s("sni") },
            fingerprint = p.s("client-fingerprint"),
            alpn = (p["alpn"] as? List<*>)?.joinToString(",").orEmpty(),
            publicKey = reality?.s("public-key").orEmpty(),
            shortId = reality?.s("short-id").orEmpty(),
            path = ws?.s("path").orEmpty(),
            host = wsHeaders?.get("Host")?.toString().orEmpty(),
            serviceName = grpc?.s("grpc-service-name").orEmpty(),
            allowInsecure = p.b("skip-cert-verify"),
        )
        return when (type) {
            "vless" -> transport(base.copy(protocol = Protocol.VLESS, uuid = p.s("uuid"), flow = p.s("flow")))
            "vmess" -> transport(base.copy(protocol = Protocol.VMESS, uuid = p.s("uuid"), alterId = p.s("alterId").toIntOrNull() ?: 0,
                vmessSecurity = p.s("cipher").ifBlank { "auto" }))
            "trojan" -> transport(base.copy(protocol = Protocol.TROJAN, password = p.s("password")))
            "ss" -> base.copy(protocol = Protocol.SHADOWSOCKS, method = p.s("cipher"), password = p.s("password"))
            "socks5" -> base.copy(protocol = Protocol.SOCKS, username = p.s("username"), password = p.s("password"))
            "http" -> base.copy(protocol = Protocol.HTTP, username = p.s("username"), password = p.s("password"), security = if (tls) "tls" else "none")
            "hysteria2" -> base.copy(protocol = Protocol.HYSTERIA2, password = p.s("password"), network = "hysteria", security = "tls",
                sni = p.s("sni"), obfs = p.s("obfs"), obfsPassword = p.s("obfs-password"), allowInsecure = p.b("skip-cert-verify"))
            "wireguard" -> base.copy(protocol = Protocol.WIREGUARD, network = "udp", wgPrivateKey = p.s("private-key"), wgPublicKey = p.s("public-key"),
                wgPreSharedKey = p.s("pre-shared-key"), wgAddresses = listOf(p.s("ip"), p.s("ipv6")).filter { it.isNotBlank() }.joinToString(", "),
                wgMtu = p.s("mtu").toIntOrNull() ?: 1420, wgReserved = (p["reserved"] as? List<*>)?.joinToString(",").orEmpty(),
                awg = p.m("amnezia-wg-option")?.mapNotNull { (k, v) -> k.replaceFirstChar { it.uppercase() }.takeIf { v != null }?.let { it to v.toString() } }?.toMap().orEmpty())
            else -> null
        }?.takeIf { it.address.isNotBlank() }?.let { it.copy(name = it.name.ifBlank { it.address }) }
    }

    /** `DOMAIN-SUFFIX,google.com,PROXY` → our rule. Policy groups other than DIRECT/REJECT mean "proxy". */
    private fun rule(line: String): RouteRule? {
        val parts = line.split(',').map { it.trim() }
        if (parts.size < 3) return null
        val (type, value, target) = parts
        val action = when (target.uppercase()) { "DIRECT" -> RouteAction.DIRECT; "REJECT", "REJECT-DROP", "BLOCK" -> RouteAction.BLOCK; else -> RouteAction.PROXY }
        return when (type.uppercase()) {
            "DOMAIN" -> RouteRule(kind = RuleKind.DOMAIN, value = "full:$value", action = action)
            "DOMAIN-SUFFIX" -> RouteRule(kind = RuleKind.DOMAIN, value = "domain:$value", action = action)
            "DOMAIN-KEYWORD" -> RouteRule(kind = RuleKind.DOMAIN, value = "keyword:$value", action = action)
            "DOMAIN-REGEX" -> RouteRule(kind = RuleKind.DOMAIN, value = "regexp:$value", action = action)
            "GEOSITE" -> RouteRule(kind = RuleKind.GEOSITE, value = value, action = action)
            "GEOIP" -> RouteRule(kind = RuleKind.GEOIP, value = value.lowercase(), action = action)
            "IP-CIDR", "IP-CIDR6" -> RouteRule(kind = RuleKind.IP, value = value, action = action)
            "DST-PORT" -> RouteRule(kind = RuleKind.PORT, value = value, action = action)
            // on Windows process rules work, unlike on Android
            "PROCESS-NAME" -> RouteRule(kind = RuleKind.PROCESS, value = value, action = action)
            else -> null
        }
    }
}

// ======================================================================== sing-box

object SingBoxParser {
    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.o(k: String) = this[k] as? JsonObject

    fun parse(cfg: JsonObject): List<ProxyNode> {
        val outs = (cfg["outbounds"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject } +
            (cfg["endpoints"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        return outs.mapNotNull { runCatching { outbound(it) }.getOrNull() }
    }

    private fun outbound(o: JsonObject): ProxyNode? {
        val type = o.s("type")
        val tls = o.o("tls")
        val reality = tls?.o("reality")
        val tr = o.o("transport")
        val base = ProxyNode(name = o.s("tag"), protocol = Protocol.VLESS, address = o.s("server"), port = o.s("server_port").toIntOrNull() ?: 443)
        fun stream(n: ProxyNode) = n.copy(
            security = when { reality?.get("enabled")?.jsonPrimitive?.booleanOrNull == true -> "reality"; tls?.get("enabled")?.jsonPrimitive?.booleanOrNull == true -> "tls"; else -> n.security },
            sni = tls?.s("server_name").orEmpty(),
            alpn = (tls?.get("alpn") as? JsonArray)?.joinToString(",") { it.jsonPrimitive.content }.orEmpty(),
            fingerprint = tls?.o("utls")?.s("fingerprint").orEmpty(),
            publicKey = reality?.s("public_key").orEmpty(), shortId = reality?.s("short_id").orEmpty(),
            network = when (tr?.s("type")) { "ws" -> "ws"; "grpc" -> "grpc"; "httpupgrade" -> "httpupgrade"; "http" -> "xhttp"; else -> n.network },
            path = tr?.s("path").orEmpty(), serviceName = tr?.s("service_name").orEmpty(),
            host = (tr?.o("headers")?.get("Host") as? JsonPrimitive)?.content ?: tr?.s("host").orEmpty(),
        )
        return when (type) {
            "vless" -> stream(base.copy(uuid = o.s("uuid"), flow = o.s("flow")))
            "vmess" -> stream(base.copy(protocol = Protocol.VMESS, uuid = o.s("uuid"), alterId = o.s("alter_id").toIntOrNull() ?: 0, vmessSecurity = o.s("security").ifBlank { "auto" }))
            "trojan" -> stream(base.copy(protocol = Protocol.TROJAN, password = o.s("password"), security = "tls"))
            "shadowsocks" -> base.copy(protocol = Protocol.SHADOWSOCKS, method = o.s("method"), password = o.s("password"))
            "socks" -> base.copy(protocol = Protocol.SOCKS, username = o.s("username"), password = o.s("password"))
            "http" -> stream(base.copy(protocol = Protocol.HTTP, username = o.s("username"), password = o.s("password")))
            "hysteria2" -> stream(base.copy(protocol = Protocol.HYSTERIA2, password = o.s("password"), network = "hysteria", security = "tls",
                obfs = o.o("obfs")?.s("type").orEmpty(), obfsPassword = o.o("obfs")?.s("password").orEmpty()))
            "wireguard" -> {
                val peer = (o["peers"] as? JsonArray)?.firstOrNull() as? JsonObject
                base.copy(protocol = Protocol.WIREGUARD, network = "udp",
                    address = peer?.s("address") ?: o.s("server"), port = (peer?.s("port") ?: o.s("server_port")).toIntOrNull() ?: 51820,
                    wgPrivateKey = o.s("private_key"), wgPublicKey = peer?.s("public_key") ?: o.s("peer_public_key"),
                    wgAddresses = (o["address"] as? JsonArray ?: o["local_address"] as? JsonArray)?.joinToString(", ") { it.jsonPrimitive.content }.orEmpty(),
                    wgMtu = o.s("mtu").toIntOrNull() ?: 1420)
            }
            else -> null
        }?.takeIf { it.address.isNotBlank() }?.let { it.copy(name = it.name.ifBlank { it.address }) }
    }
}

// ======================================================================== Happ-compatible routing profiles

/**
 * Routing profiles in the format panels push to Happ-compatible clients: `happ://routing/add/<base64 JSON>`
 * (also `onadd`), or the same in the `routing` response header. Fields: DirectSites/DirectIp/ProxySites/ProxyIp/
 * BlockSites/BlockIp, DomainStrategy, Geoipurl/Geositeurl, GlobalProxy.
 */
object HappRouting {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun extract(text: String): String? {
        val t = text.trim()
        val m = Regex("""^[a-z]+://routing/(?:add|onadd)/(\S+)$""", RegexOption.IGNORE_CASE).find(t) ?: return null
        return LinkParser.decodeBase64OrNull(m.groupValues[1])?.takeIf { it.trimStart().startsWith("{") }
    }

    /** Applies a profile on top of the current routing. Returns null if it is not a valid profile. */
    fun apply(profileJson: String, current: RoutingSettings): RoutingSettings? {
        val o = runCatching { json.parseToJsonElement(profileJson).jsonObject }.getOrNull() ?: return null
        fun list(k: String) = (o[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        fun siteRule(values: List<String>, a: RouteAction) = values.takeIf { it.isNotEmpty() }?.let {
            // geosite:x stays as-is, plain domains become domain: matches
            RouteRule(id = "panel-site-${a.name}", kind = RuleKind.DOMAIN, value = it.joinToString(", "), action = a)
        }
        fun ipRule(values: List<String>, a: RouteAction) = values.takeIf { it.isNotEmpty() }?.let {
            RouteRule(id = "panel-ip-${a.name}", kind = RuleKind.IP, value = it.joinToString(", "), action = a)
        }
        val panelRules = listOfNotNull(
            siteRule(list("BlockSites"), RouteAction.BLOCK), ipRule(list("BlockIp"), RouteAction.BLOCK),
            siteRule(list("DirectSites"), RouteAction.DIRECT), ipRule(list("DirectIp"), RouteAction.DIRECT),
            siteRule(list("ProxySites"), RouteAction.PROXY), ipRule(list("ProxyIp"), RouteAction.PROXY),
        )
        val global = (o["GlobalProxy"] as? JsonPrimitive)?.contentOrNull?.lowercase() == "true"
        return current.copy(
            mode = if (global && panelRules.isEmpty()) RoutingMode.GLOBAL else RoutingMode.RULE,
            domainStrategy = (o["DomainStrategy"] as? JsonPrimitive)?.contentOrNull?.takeIf { it in setOf("AsIs", "IPIfNonMatch", "IPOnDemand") } ?: current.domainStrategy,
            // panel rules replace previous panel rules, user rules stay after them
            rules = panelRules + current.rules.filterNot { it.id.startsWith("panel-") },
            // the panel decides what goes direct; our RU preset would fight it
            bypassRu = if (panelRules.isNotEmpty()) false else current.bypassRu,
        )
    }

    fun geoUrls(profileJson: String): Pair<String?, String?> {
        val o = runCatching { json.parseToJsonElement(profileJson).jsonObject }.getOrNull() ?: return null to null
        return (o["Geoipurl"] as? JsonPrimitive)?.contentOrNull?.ifBlank { null } to (o["Geositeurl"] as? JsonPrimitive)?.contentOrNull?.ifBlank { null }
    }
}
