package dev.uplinx.parse

import dev.uplinx.L

import dev.uplinx.data.Protocol
import dev.uplinx.data.ProxyNode
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Inflater

/** wg-quick / AmneziaWG `.conf` files. */
object WgConfParser {
    private val AWG_KEYS = Regex("^(Jc|Jmin|Jmax|S[1-4]|H[1-4]|I[1-5])$", RegexOption.IGNORE_CASE)

    fun looksLike(text: String) = text.contains("[Interface]", ignoreCase = true) && text.contains("[Peer]", ignoreCase = true)

    fun parse(text: String, nameHint: String? = null): ProxyNode {
        val iface = mutableMapOf<String, String>()
        val peer = mutableMapOf<String, String>()
        val awg = linkedMapOf<String, String>()
        var section = ""
        text.lineSequence().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.forEach { line ->
            if (line.startsWith("[")) { section = line.trim('[', ']').lowercase(); return@forEach }
            val k = line.substringBefore('=').trim()
            val v = line.substringAfter('=', "").trim()
            when (section) {
                "interface" -> if (AWG_KEYS.matches(k)) awg[k.replaceFirstChar { it.uppercase() }] = v else iface[k.lowercase()] = v
                "peer" -> peer.putIfAbsent(k.lowercase(), v) // first peer wins
            }
        }
        val endpoint = peer["endpoint"] ?: error(L("В конфиге нет Endpoint"))
        val host = if (endpoint.startsWith("[")) endpoint.substringAfter('[').substringBefore(']') else endpoint.substringBeforeLast(':')
        val port = endpoint.substringAfterLast(':').toIntOrNull() ?: 51820
        return ProxyNode(
            name = nameHint?.takeIf { it.isNotBlank() } ?: host,
            protocol = Protocol.WIREGUARD,
            address = host, port = port, network = "udp",
            wgPrivateKey = iface["privatekey"].orEmpty(),
            wgAddresses = iface["address"].orEmpty(),
            wgDns = iface["dns"].orEmpty(),
            wgMtu = iface["mtu"]?.toIntOrNull() ?: 1420,
            wgPublicKey = peer["publickey"].orEmpty(),
            wgPreSharedKey = peer["presharedkey"].orEmpty(),
            wgAllowedIps = peer["allowedips"] ?: "0.0.0.0/0, ::/0",
            wgKeepalive = peer["persistentkeepalive"]?.toIntOrNull() ?: 0,
            awg = awg,
            wgConfText = if (awg.isNotEmpty()) text.trim() else "",
        )
    }

    /** Rebuilds a wg-quick config (used for AmneziaWG engine and "export .conf"). */
    fun toConf(n: ProxyNode): String = n.wgConfText.ifBlank {
        buildString {
            appendLine("[Interface]")
            appendLine("PrivateKey = ${n.wgPrivateKey}")
            appendLine("Address = ${n.wgAddresses}")
            if (n.wgDns.isNotBlank()) appendLine("DNS = ${n.wgDns}")
            appendLine("MTU = ${n.wgMtu}")
            n.awg.forEach { (k, v) -> appendLine("$k = $v") }
            appendLine()
            appendLine("[Peer]")
            appendLine("PublicKey = ${n.wgPublicKey}")
            if (n.wgPreSharedKey.isNotBlank()) appendLine("PresharedKey = ${n.wgPreSharedKey}")
            appendLine("AllowedIPs = ${n.wgAllowedIps}")
            appendLine("Endpoint = ${n.endpoint}")
            if (n.wgKeepalive > 0) appendLine("PersistentKeepalive = ${n.wgKeepalive}")
        }
    }
}

/** Full Xray JSON client configs (single object or array) — e.g. what Remnawave serves to Happ. */
object XrayJsonParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun looksLike(text: String): Boolean {
        val t = text.trimStart()
        return (t.startsWith("{") || t.startsWith("[")) && t.contains("outbounds")
    }

    fun parse(text: String): List<ProxyNode> {
        val root = json.parseToJsonElement(text)
        val configs = if (root is JsonArray) root.map { it.jsonObject } else listOf(root.jsonObject)
        return configs.flatMap { parseConfig(it) }
    }

    private val notProxy = setOf("freedom", "blackhole", "dns", "loopback", "dokodemo-door", "tunnel")

    /**
     * A config with balancers or several proxy outbounds wired by routing is kept whole (one XRAY_CONFIG node)
     * so nothing is lost; a simple one-outbound config becomes an ordinary editable server.
     */
    fun parseConfig(cfg: JsonObject): List<ProxyNode> {
        val remarks = cfg["remarks"]?.jsonPrimitive?.contentOrNull
        val outs = cfg["outbounds"]?.jsonArray?.mapNotNull { it as? JsonObject }.orEmpty()
        val proxyOuts = outs.filter { it.str("protocol") !in notProxy }
        val balancers = cfg.obj("routing")?.arr("balancers")?.mapNotNull { it as? JsonObject }.orEmpty()
        val chained = proxyOuts.any { it.obj("proxySettings") != null || it.obj("streamSettings")?.obj("sockopt")?.str("dialerProxy")?.isNotBlank() == true }
        if (balancers.isNotEmpty() || chained) {
            val first = proxyOuts.firstNotNullOfOrNull { runCatching { outbound(it) }.getOrNull() }
            val selectors = balancers.flatMap { b -> b.arr("selector")?.map { it.jsonPrimitive.content }.orEmpty() }
            val members = proxyOuts.map { it.str("tag") }.filter { t -> t.isNotBlank() && selectors.any { t.startsWith(it) } }
            return listOf(ProxyNode(
                name = remarks ?: first?.name ?: "Xray config",
                protocol = Protocol.XRAY_CONFIG,
                address = first?.address ?: "", port = first?.port ?: 0,
                network = if (balancers.isNotEmpty()) "balancer" else "chain",
                rawConfig = cfg.toString(),
                balancerMembers = members,
            ))
        }
        val proxies = proxyOuts.mapNotNull { runCatching { outbound(it) }.getOrNull() }
        return proxies.mapIndexed { i, n ->
            n.copy(name = when {
                remarks != null && proxies.size == 1 -> remarks
                remarks != null -> "$remarks · ${i + 1}"
                else -> n.name
            })
        }
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.obj(k: String) = this[k] as? JsonObject
    private fun JsonObject.arr(k: String) = this[k] as? JsonArray
    private fun JsonElement?.firstObj() = (this as? JsonArray)?.firstOrNull() as? JsonObject

    fun outbound(o: JsonObject): ProxyNode? {
        val proto = o.str("protocol")
        val st = o.obj("settings") ?: JsonObject(emptyMap())
        val tag = o.str("tag")
        var node: ProxyNode = when (proto) {
            "vless", "vmess" -> {
                // both the classic vnext form and the newer flat form
                val srv = st.arr("vnext").firstObj() ?: st
                val user = srv.arr("users").firstObj() ?: srv
                ProxyNode(
                    name = tag, protocol = if (proto == "vless") Protocol.VLESS else Protocol.VMESS,
                    address = srv.str("address"), port = srv.str("port").toIntOrNull() ?: 443,
                    uuid = user.str("id"), flow = user.str("flow"),
                    encryption = user.str("encryption").ifBlank { "none" },
                    vmessSecurity = user.str("security").ifBlank { "auto" },
                    alterId = user.str("alterId").toIntOrNull() ?: 0,
                )
            }
            "trojan", "shadowsocks", "socks", "http" -> {
                val srv = st.arr("servers").firstObj() ?: st
                val user = srv.arr("users").firstObj()
                ProxyNode(
                    name = tag,
                    protocol = when (proto) { "trojan" -> Protocol.TROJAN; "shadowsocks" -> Protocol.SHADOWSOCKS; "socks" -> Protocol.SOCKS; else -> Protocol.HTTP },
                    address = srv.str("address"), port = srv.str("port").toIntOrNull() ?: 443,
                    password = srv.str("password").ifBlank { user?.str("pass").orEmpty() },
                    username = user?.str("user").orEmpty(),
                    method = srv.str("method"),
                )
            }
            "hysteria" -> ProxyNode(name = tag, protocol = Protocol.HYSTERIA2, address = st.str("address"),
                port = st.str("port").toIntOrNull() ?: 443, network = "hysteria", security = "tls")
            "wireguard" -> {
                val peer = st.arr("peers").firstObj() ?: return null
                val ep = peer.str("endpoint")
                ProxyNode(name = tag, protocol = Protocol.WIREGUARD,
                    address = ep.substringBeforeLast(':').trim('[', ']'), port = ep.substringAfterLast(':').toIntOrNull() ?: 51820,
                    network = "udp", wgPrivateKey = st.str("secretKey"),
                    wgAddresses = st.arr("address")?.joinToString(", ") { it.jsonPrimitive.content }.orEmpty(),
                    wgPublicKey = peer.str("publicKey"), wgPreSharedKey = peer.str("preSharedKey"),
                    wgMtu = st.str("mtu").toIntOrNull() ?: 1420,
                    wgReserved = st.arr("reserved")?.joinToString(",") { it.jsonPrimitive.content }.orEmpty())
            }
            else -> return null
        }
        if (node.address.isBlank()) return null
        val ss = o.obj("streamSettings")
        if (ss != null) {
            val net = ss.str("network").ifBlank { "tcp" }.let { if (it == "raw") "tcp" else if (it == "splithttp") "xhttp" else it }
            val sec = ss.str("security").ifBlank { "none" }
            val tls = ss.obj("tlsSettings")
            val rl = ss.obj("realitySettings")
            val ws = ss.obj("wsSettings"); val hu = ss.obj("httpupgradeSettings"); val gr = ss.obj("grpcSettings")
            val xh = ss.obj("xhttpSettings") ?: ss.obj("splithttpSettings")
            val raw = ss.obj("rawSettings") ?: ss.obj("tcpSettings")
            val rawHeader = raw?.obj("header")
            val req = rawHeader?.obj("request")
            node = node.copy(
                network = if (node.protocol == Protocol.HYSTERIA2) "hysteria" else net,
                security = sec,
                sni = tls?.str("serverName") ?: rl?.str("serverName") ?: "",
                fingerprint = tls?.str("fingerprint") ?: rl?.str("fingerprint") ?: "",
                alpn = tls?.arr("alpn")?.joinToString(",") { it.jsonPrimitive.content }.orEmpty(),
                pinnedPeerCertSha256 = tls?.str("pinnedPeerCertSha256").orEmpty(),
                verifyPeerCertByName = tls?.str("verifyPeerCertByName").orEmpty(),
                echConfigList = tls?.str("echConfigList").orEmpty(),
                publicKey = rl?.str("publicKey") ?: rl?.str("password") ?: "",
                shortId = rl?.str("shortId").orEmpty(),
                spiderX = rl?.str("spiderX").orEmpty(),
                path = ws?.str("path") ?: hu?.str("path") ?: xh?.str("path") ?: req?.arr("path")?.firstOrNull()?.jsonPrimitive?.content ?: "",
                host = ws?.str("host") ?: hu?.str("host") ?: xh?.str("host") ?: gr?.str("authority")
                    ?: req?.obj("headers")?.arr("Host")?.joinToString(",") { it.jsonPrimitive.content } ?: "",
                headerType = rawHeader?.str("type")?.ifBlank { null } ?: "none",
                serviceName = gr?.str("serviceName").orEmpty(),
                grpcMode = if (gr?.get("multiMode")?.jsonPrimitive?.booleanOrNull == true) "multi" else "gun",
                xhttpMode = xh?.str("mode")?.ifBlank { null } ?: "auto",
                xhttpExtra = xh?.get("extra")?.toString().orEmpty(),
            )
            ss.obj("hysteriaSettings")?.let { node = node.copy(password = it.str("auth")) }
            ss.obj("finalmask")?.arr("udp")?.mapNotNull { it as? JsonObject }?.firstOrNull { it.str("type") == "salamander" }?.let {
                node = node.copy(obfs = "salamander", obfsPassword = it.obj("settings")?.str("password").orEmpty())
            }
        }
        return node.copy(name = node.name.ifBlank { node.address }, rawLink = "")
    }
}

/** Amnezia VPN share keys: `vpn://` + base64url(qCompress(json)). */
object AmneziaParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun looksLike(text: String) = text.trimStart().startsWith("vpn://", ignoreCase = true)

    fun decode(link: String): String {
        val b64 = link.trim().substringAfter("://").replace("\n", "")
        val bytes = Base64.getUrlDecoder().decode(b64.replace('+', '-').replace('/', '_').trimEnd('=').let {
            it.padEnd((it.length + 3) / 4 * 4, '=')
        })
        if (bytes.isNotEmpty() && bytes[0] == '{'.code.toByte()) return String(bytes, Charsets.UTF_8)
        // qCompress: 4-byte big-endian uncompressed size, then a zlib stream
        val inflater = Inflater()
        inflater.setInput(bytes, 4, bytes.size - 4)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            out.write(buf, 0, n)
        }
        inflater.end()
        return out.toString(Charsets.UTF_8)
    }

    fun parse(link: String): List<ProxyNode> {
        val root = json.parseToJsonElement(decode(link)).jsonObject
        val serverName = (root["description"] as? JsonPrimitive)?.contentOrNull?.ifBlank { null }
            ?: (root["name"] as? JsonPrimitive)?.contentOrNull
            ?: (root["hostName"] as? JsonPrimitive)?.contentOrNull ?: "Amnezia"
        val dns1 = (root["dns1"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val dns2 = (root["dns2"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val containers = (root["containers"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        val result = mutableListOf<ProxyNode>()
        for (c in containers) {
            val type = (c["container"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val key = type.removePrefix("amnezia-")
            val proto = (c[key] ?: c.values.firstOrNull { it is JsonObject && "last_config" in it.jsonObject }) as? JsonObject ?: continue
            val last = (proto["last_config"] as? JsonPrimitive)?.contentOrNull ?: continue
            val label = "$serverName · ${key.uppercase()}"
            when {
                key in setOf("awg", "wireguard") || key.startsWith("awg") -> {
                    val conf = runCatching { json.parseToJsonElement(last).jsonObject["config"]?.jsonPrimitive?.content }.getOrNull() ?: last
                    val text = conf.replace("\$PRIMARY_DNS", dns1.ifBlank { "1.1.1.1" }).replace("\$SECONDARY_DNS", dns2.ifBlank { "1.0.0.1" })
                    if (WgConfParser.looksLike(text)) result += WgConfParser.parse(text, label)
                }
                key == "xray" || key == "ssxray" || key.contains("xray") -> {
                    result += XrayJsonParser.parse(last).map { it.copy(name = label) }
                }
                // OpenVPN / Cloak / IKEv2 containers have no equivalent here and are skipped
            }
        }
        if (result.isEmpty()) error(L("В ключе Amnezia нет поддерживаемых протоколов (AWG, WireGuard, Xray)"))
        return result
    }
}
