package dev.uplinx.core

import dev.uplinx.data.AppSettings
import dev.uplinx.data.ConnectMode
import dev.uplinx.data.GeoSource
import dev.uplinx.data.Protocol
import dev.uplinx.data.ProxyNode
import dev.uplinx.data.RouteAction
import dev.uplinx.data.RoutingMode
import dev.uplinx.data.RoutingSettings
import dev.uplinx.data.RuleKind
import kotlinx.serialization.json.*

/** Turns the app state into an Xray JSON config. Pure: no IO, so it is unit-testable. */
object XrayConfig {
    const val TUN_NAME = "Uplinx"
    private val prettyJson = Json { prettyPrint = true }

    fun build(node: ProxyNode, s: AppSettings, r: RoutingSettings): String =
        prettyJson.encodeToString(JsonObject.serializer(), buildObject(node, s, r))

    fun buildObject(node: ProxyNode, s: AppSettings, r: RoutingSettings): JsonObject =
        if (node.protocol == Protocol.XRAY_CONFIG) buildRaw(node, s) else buildNormal(node, s, r)

    /**
     * A full user/panel config: its outbounds, balancers, observatory and routing are kept; inbounds are ours
     * (local SOCKS/HTTP, TUN) and the stats API is added so the speed chart works.
     */
    private fun buildRaw(node: ProxyNode, s: AppSettings): JsonObject {
        val raw = Json.parseToJsonElement(node.rawConfig).jsonObject
        val tun = s.connectMode == ConnectMode.TUN
        val outbounds = (raw["outbounds"] as? JsonArray).orEmpty().map { o ->
            val obj = o.jsonObject
            if (!tun || obj["protocol"]?.jsonPrimitive?.contentOrNull in setOf("freedom", "blackhole", "dns")) obj
            else JsonObject(obj + ("streamSettings" to JsonObject(((obj["streamSettings"] as? JsonObject) ?: JsonObject(emptyMap())) + ("sockopt" to sockopt()))))
        }.toMutableList()
        val tags = outbounds.mapNotNull { it["tag"]?.jsonPrimitive?.contentOrNull }.toSet()
        if ("dns-out" !in tags) outbounds += buildJsonObject { put("tag", "dns-out"); put("protocol", "dns") }
        val routing = (raw["routing"] as? JsonObject) ?: JsonObject(emptyMap())
        val rules = buildJsonArray {
            if (tun) add(rule("dns-out") { putJsonArray("inboundTag") { add("tun") }; put("port", "53") })
            (routing["rules"] as? JsonArray)?.forEach { add(it) }
        }
        return JsonObject(raw.filterKeys { it !in setOf("inbounds", "api", "stats", "policy", "remarks", "log") } + buildJsonObject {
            putJsonObject("log") { put("loglevel", s.logLevel) }
            putJsonObject("api") { put("tag", "api"); put("listen", "127.0.0.1:${s.apiPort}"); putJsonArray("services") { add("StatsService") } }
            putJsonObject("stats") {}
            putJsonObject("policy") { putJsonObject("system") { put("statsOutboundUplink", true); put("statsOutboundDownlink", true) } }
            putJsonArray("inbounds") {
                if (tun) add(tunInbound(s))
                add(localInbound("socks-in", "socks", s.socksPort, s))
                add(localInbound("http-in", "http", s.httpPort, s))
            }
            put("outbounds", JsonArray(outbounds))
            put("routing", JsonObject(routing + ("rules" to rules)))
        })
    }

    private fun buildNormal(node: ProxyNode, s: AppSettings, r: RoutingSettings): JsonObject = buildJsonObject {
        val tun = s.connectMode == ConnectMode.TUN
        putJsonObject("log") { put("loglevel", s.logLevel) }
        putJsonObject("api") {
            put("tag", "api")
            put("listen", "127.0.0.1:${s.apiPort}")
            putJsonArray("services") { add("StatsService") }
        }
        putJsonObject("stats") {}
        putJsonObject("policy") {
            putJsonObject("system") {
                put("statsOutboundUplink", true)
                put("statsOutboundDownlink", true)
            }
        }
        put("dns", dns(s, r))
        putJsonArray("inbounds") {
            if (tun) add(tunInbound(s))
            add(localInbound("socks-in", "socks", s.socksPort, s))
            add(localInbound("http-in", "http", s.httpPort, s))
        }
        putJsonArray("outbounds") {
            add(outbound(node, s))
            addJsonObject { put("tag", "direct"); put("protocol", "freedom"); putJsonObject("settings") { put("domainStrategy", "UseIP") } }
            addJsonObject { put("tag", "block"); put("protocol", "blackhole") }
            addJsonObject { put("tag", "dns-out"); put("protocol", "dns") }
        }
        put("routing", routing(s, r))
    }

    // ---------------------------------------------------------------- inbounds

    private fun sniffing(s: AppSettings) = buildJsonObject {
        put("enabled", s.sniffing)
        putJsonArray("destOverride") { add("http"); add("tls"); add("quic") }
        put("routeOnly", true)
    }

    private fun localInbound(tag: String, protocol: String, port: Int, s: AppSettings) = buildJsonObject {
        put("tag", tag)
        put("protocol", protocol)
        put("listen", if (s.allowLan) "0.0.0.0" else "127.0.0.1")
        put("port", port)
        putJsonObject("settings") {
            if (protocol == "socks") { put("auth", "noauth"); put("udp", true) }
        }
        put("sniffing", sniffing(s))
    }

    private fun tunInbound(s: AppSettings) = buildJsonObject {
        put("tag", "tun")
        put("protocol", "tun")
        put("port", 0)
        putJsonObject("settings") {
            put("name", TUN_NAME)
            put("mtu", 1500)
            putJsonArray("gateway") { add("172.19.0.1/30") }
            // the DNS the adapter announces; it lies inside the routed range, so queries enter the TUN
            putJsonArray("dns") { add("1.1.1.1") }
            putJsonArray("autoSystemRoutingTable") { add("0.0.0.0/0"); if (s.ipv6) add("::/0") }
            if (s.tunStrictDns) putJsonArray("autoSystemWfpBlockLeak") { add("dns"); add("misconfigtun") }
        }
        put("sniffing", sniffing(s))
    }

    // ---------------------------------------------------------------- outbound

    fun outbound(n: ProxyNode, s: AppSettings) = buildJsonObject {
        put("tag", "proxy")
        when (n.protocol) {
            Protocol.VLESS -> {
                put("protocol", "vless")
                putJsonObject("settings") {
                    putJsonArray("vnext") {
                        addJsonObject {
                            put("address", n.address); put("port", n.port)
                            putJsonArray("users") {
                                addJsonObject {
                                    put("id", n.uuid)
                                    put("encryption", n.encryption.ifBlank { "none" })
                                    if (n.flow.isNotBlank()) put("flow", n.flow)
                                }
                            }
                        }
                    }
                }
            }
            Protocol.VMESS -> {
                put("protocol", "vmess")
                putJsonObject("settings") {
                    putJsonArray("vnext") {
                        addJsonObject {
                            put("address", n.address); put("port", n.port)
                            putJsonArray("users") {
                                addJsonObject { put("id", n.uuid); put("alterId", n.alterId); put("security", n.vmessSecurity.ifBlank { "auto" }) }
                            }
                        }
                    }
                }
            }
            Protocol.TROJAN -> {
                put("protocol", "trojan")
                putJsonObject("settings") {
                    putJsonArray("servers") { addJsonObject { put("address", n.address); put("port", n.port); put("password", n.password) } }
                }
            }
            Protocol.SHADOWSOCKS -> {
                put("protocol", "shadowsocks")
                putJsonObject("settings") {
                    putJsonArray("servers") {
                        addJsonObject { put("address", n.address); put("port", n.port); put("method", n.method); put("password", n.password) }
                    }
                }
            }
            Protocol.SOCKS, Protocol.HTTP -> {
                put("protocol", if (n.protocol == Protocol.SOCKS) "socks" else "http")
                putJsonObject("settings") {
                    putJsonArray("servers") {
                        addJsonObject {
                            put("address", n.address); put("port", n.port)
                            if (n.username.isNotEmpty()) putJsonArray("users") { addJsonObject { put("user", n.username); put("pass", n.password) } }
                        }
                    }
                }
            }
            Protocol.HYSTERIA2 -> {
                put("protocol", "hysteria")
                putJsonObject("settings") { put("version", 2); put("address", n.address); put("port", n.port) }
            }
            Protocol.XRAY_CONFIG -> error("XRAY_CONFIG is built by buildRaw")
            Protocol.WIREGUARD -> {
                put("protocol", "wireguard")
                putJsonObject("settings") {
                    put("secretKey", n.wgPrivateKey)
                    putJsonArray("address") { n.wgAddresses.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { add(it) } }
                    putJsonArray("peers") {
                        addJsonObject {
                            put("publicKey", n.wgPublicKey)
                            if (n.wgPreSharedKey.isNotBlank()) put("preSharedKey", n.wgPreSharedKey)
                            put("endpoint", n.endpoint)
                            putJsonArray("allowedIPs") { add("0.0.0.0/0"); add("::/0") }
                        }
                    }
                    put("mtu", n.wgMtu)
                    val reserved = n.wgReserved.split(',').mapNotNull { it.trim().toIntOrNull() }
                    if (reserved.size == 3) putJsonArray("reserved") { reserved.forEach { add(it) } }
                    put("noKernelTun", true)
                }
            }
        }
        if (n.protocol != Protocol.WIREGUARD) put("streamSettings", stream(n, s))
        else {
            val jc = n.awg["Jc"]?.toIntOrNull() ?: 0
            if (jc > 0 || s.connectMode == ConnectMode.TUN) putJsonObject("streamSettings") {
                if (jc > 0) {
                    // AmneziaWG junk packets (Jc × random Jmin..Jmax bytes) before the handshake
                    val jmin = n.awg["Jmin"]?.toIntOrNull() ?: 40
                    val jmax = (n.awg["Jmax"]?.toIntOrNull() ?: 70).coerceAtLeast(jmin)
                    putJsonObject("finalmask") {
                        putJsonArray("udp") {
                            addJsonObject {
                                put("type", "noise")
                                putJsonObject("settings") {
                                    putJsonArray("noise") {
                                        repeat(jc.coerceAtMost(128)) { addJsonObject { put("rand", "$jmin-$jmax") } }
                                    }
                                }
                            }
                        }
                    }
                }
                if (s.connectMode == ConnectMode.TUN) put("sockopt", sockopt())
            }
        }
        if (s.mux && n.protocol in setOf(Protocol.VLESS, Protocol.VMESS, Protocol.TROJAN) && n.flow.isBlank() && n.network != "xhttp") {
            putJsonObject("mux") { put("enabled", true); put("concurrency", 8) }
        }
    }

    private fun sockopt() = buildJsonObject {
        // resolve the server name through Xray's DNS rather than the OS, which would loop into the TUN
        put("domainStrategy", "UseIP")
    }

    private fun splitList(v: String) = v.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private fun stream(n: ProxyNode, s: AppSettings) = buildJsonObject {
        val network = when (n.network) {
            "h2", "http" -> "xhttp"
            "hysteria" -> "hysteria"
            "" -> "tcp"
            else -> n.network
        }
        put("network", if (n.protocol == Protocol.HYSTERIA2) "hysteria" else network)
        val security = if (n.protocol == Protocol.HYSTERIA2) "tls" else n.security.ifBlank { "none" }
        put("security", security)

        when (security) {
            "tls" -> putJsonObject("tlsSettings") {
                val sni = n.sni.ifBlank { n.host.ifBlank { n.address } }
                put("serverName", sni)
                val alpn = splitList(n.alpn).ifEmpty { if (n.protocol == Protocol.HYSTERIA2) listOf("h3") else emptyList() }
                if (alpn.isNotEmpty()) putJsonArray("alpn") { alpn.forEach { add(it) } }
                if (n.fingerprint.isNotBlank()) put("fingerprint", n.fingerprint)
                if (n.pinnedPeerCertSha256.isNotBlank()) put("pinnedPeerCertSha256", n.pinnedPeerCertSha256)
                if (n.verifyPeerCertByName.isNotBlank()) put("verifyPeerCertByName", n.verifyPeerCertByName)
                if (n.echConfigList.isNotBlank()) put("echConfigList", n.echConfigList)
            }
            "reality" -> putJsonObject("realitySettings") {
                put("serverName", n.sni)
                put("fingerprint", n.fingerprint.ifBlank { "chrome" })
                put("publicKey", n.publicKey)
                put("shortId", n.shortId)
                if (n.spiderX.isNotBlank()) put("spiderX", n.spiderX)
            }
        }

        when (if (n.protocol == Protocol.HYSTERIA2) "hysteria" else network) {
            "tcp" -> if (n.headerType == "http") putJsonObject("rawSettings") {
                putJsonObject("header") {
                    put("type", "http")
                    putJsonObject("request") {
                        putJsonArray("path") { add(n.path.ifBlank { "/" }) }
                        if (n.host.isNotBlank()) putJsonObject("headers") { putJsonArray("Host") { splitList(n.host).forEach { add(it) } } }
                    }
                }
            }
            "ws" -> putJsonObject("wsSettings") { put("path", n.path.ifBlank { "/" }); if (n.host.isNotBlank()) put("host", n.host) }
            "httpupgrade" -> putJsonObject("httpupgradeSettings") { put("path", n.path.ifBlank { "/" }); if (n.host.isNotBlank()) put("host", n.host) }
            "grpc" -> putJsonObject("grpcSettings") {
                put("serviceName", n.serviceName)
                put("multiMode", n.grpcMode == "multi")
                if (n.host.isNotBlank()) put("authority", n.host)
            }
            "xhttp" -> putJsonObject("xhttpSettings") {
                put("path", n.path.ifBlank { "/" })
                if (n.host.isNotBlank()) put("host", n.host)
                put("mode", n.xhttpMode.ifBlank { "auto" })
                if (n.xhttpExtra.isNotBlank()) runCatching { Json.parseToJsonElement(n.xhttpExtra) }.getOrNull()?.let { put("extra", it) }
            }
            "kcp" -> putJsonObject("kcpSettings") {
                putJsonObject("header") { put("type", n.headerType.ifBlank { "none" }) }
                if (n.path.isNotBlank()) put("seed", n.path)
            }
            "hysteria" -> putJsonObject("hysteriaSettings") { put("version", 2); put("auth", n.password) }
        }

        if (n.protocol == Protocol.HYSTERIA2 && n.obfs == "salamander" && n.obfsPassword.isNotBlank()) {
            putJsonObject("finalmask") {
                putJsonArray("udp") {
                    addJsonObject { put("type", "salamander"); putJsonObject("settings") { put("password", n.obfsPassword) } }
                }
            }
        }
        if (s.connectMode == ConnectMode.TUN) put("sockopt", sockopt())
    }

    // ---------------------------------------------------------------- dns

    private fun dns(s: AppSettings, r: RoutingSettings) = buildJsonObject {
        put("tag", "dns-internal")
        putJsonArray("servers") {
            add(s.remoteDns)
            if (r.mode == RoutingMode.RULE && (r.bypassRu || r.proxyBlockedRu)) addJsonObject {
                put("address", s.directDns)
                putJsonArray("domains") {
                    if (s.geoSource == GeoSource.RUNETFREEDOM && r.proxyBlockedRu) add("geosite:ru-available-only-inside")
                    else add("geosite:category-ru")
                }
                put("skipFallback", true)
            }
        }
        put("queryStrategy", if (s.ipv6) "UseIP" else "UseIPv4")
    }

    // ---------------------------------------------------------------- routing

    private fun rule(outbound: String, block: JsonObjectBuilder.() -> Unit) = buildJsonObject {
        put("type", "field"); block(); put("outboundTag", outbound)
    }

    private fun tagFor(a: RouteAction) = when (a) { RouteAction.PROXY -> "proxy"; RouteAction.DIRECT -> "direct"; RouteAction.BLOCK -> "block" }

    fun routing(s: AppSettings, r: RoutingSettings) = buildJsonObject {
        put("domainStrategy", r.domainStrategy)
        putJsonArray("rules") {
            if (s.connectMode == ConnectMode.TUN) {
                add(rule("dns-out") { putJsonArray("inboundTag") { add("tun") }; put("port", "53") })
            }
            // the directDns resolver itself must never go through the proxy
            if (r.mode == RoutingMode.RULE && r.bypassRu) {
                add(rule("direct") { putJsonArray("ip") { add(s.directDns.substringAfter("://").substringBefore('/').substringBefore(':')) } })
            }
            when (r.mode) {
                RoutingMode.DIRECT -> add(rule("direct") { put("network", "tcp,udp") })
                RoutingMode.GLOBAL -> {
                    if (r.bypassLan) add(rule("direct") { putJsonArray("ip") { add("geoip:private") } })
                    add(rule("proxy") { put("network", "tcp,udp") })
                }
                RoutingMode.RULE -> {
                    if (r.blockAds) add(rule("block") { putJsonArray("domain") { add("geosite:category-ads-all") } })
                    // user rules win over presets, in list order
                    r.rules.filter { it.enabled && it.value.isNotBlank() }.forEach { ur ->
                        val values = splitList(ur.value)
                        add(rule(tagFor(ur.action)) {
                            when (ur.kind) {
                                RuleKind.DOMAIN -> putJsonArray("domain") { values.forEach { add(if (':' in it) it else "domain:$it") } }
                                RuleKind.GEOSITE -> putJsonArray("domain") { values.forEach { add("geosite:${it.removePrefix("geosite:")}") } }
                                RuleKind.IP -> putJsonArray("ip") { values.forEach { add(it) } }
                                RuleKind.GEOIP -> putJsonArray("ip") { values.forEach { add("geoip:${it.removePrefix("geoip:")}") } }
                                RuleKind.PROCESS -> putJsonArray("process") { values.forEach { add(it) } }
                                RuleKind.PORT -> put("port", values.joinToString(","))
                            }
                        })
                    }
                    if (r.bypassLan) add(rule("direct") { putJsonArray("ip") { add("geoip:private") } })
                    if (r.proxyBlockedRu && s.geoSource == GeoSource.RUNETFREEDOM) {
                        // "only blocked sites through proxy" mode
                        add(rule("proxy") { putJsonArray("domain") { add("geosite:ru-blocked") } })
                        add(rule("proxy") { putJsonArray("ip") { add("geoip:ru-blocked") } })
                        add(rule("direct") { put("network", "tcp,udp") })
                    } else if (r.bypassRu) {
                        add(rule("direct") {
                            putJsonArray("domain") {
                                add("geosite:category-ru"); add("regexp:\\.ru$"); add("regexp:\\.su$"); add("regexp:\\.xn--p1ai$")
                            }
                        })
                        add(rule("direct") { putJsonArray("ip") { add("geoip:ru") } })
                    }
                    add(rule("proxy") { put("network", "tcp,udp") })
                }
            }
        }
    }
}
