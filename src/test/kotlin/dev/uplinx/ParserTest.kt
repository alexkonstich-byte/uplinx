package dev.uplinx

import dev.uplinx.core.XrayConfig
import dev.uplinx.data.*
import dev.uplinx.parse.*
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import kotlin.test.*

class ParserTest {
    @Test fun vlessReality() {
        val n = LinkParser.parse("vless://11111111-2222-3333-4444-555555555555@example.com:443?type=tcp&security=reality&sni=www.microsoft.com&fp=chrome&pbk=PUBKEY&sid=ab12&flow=xtls-rprx-vision#My%20Server%20%F0%9F%87%B3%F0%9F%87%B1")!!
        assertEquals(Protocol.VLESS, n.protocol)
        assertEquals("example.com", n.address); assertEquals(443, n.port)
        assertEquals("reality", n.security); assertEquals("PUBKEY", n.publicKey); assertEquals("xtls-rprx-vision", n.flow)
        assertEquals("My Server 🇳🇱", n.name)
        // round trip
        val again = LinkParser.parse(LinkExporter.toLink(n))!!
        assertEquals(n.copy(id = again.id, rawLink = again.rawLink), again)
    }

    @Test fun vmessBase64() {
        val js = """{"v":"2","ps":"vm","add":"1.2.3.4","port":"8443","id":"uuid","aid":"0","net":"ws","host":"h.com","path":"/ws","tls":"tls","sni":"h.com"}"""
        val n = LinkParser.parse("vmess://" + Base64.getEncoder().encodeToString(js.toByteArray()))!!
        assertEquals("ws", n.network); assertEquals("/ws", n.path); assertEquals("tls", n.security); assertEquals(8443, n.port)
    }

    @Test fun shadowsocksForms() {
        val sip = LinkParser.parse("ss://" + Base64.getUrlEncoder().withoutPadding().encodeToString("aes-256-gcm:pa+ss".toByteArray()) + "@[2001:db8::1]:8388#ss1")!!
        assertEquals("2001:db8::1", sip.address); assertEquals("pa+ss", sip.password); assertEquals("aes-256-gcm", sip.method)
        val legacy = LinkParser.parse("ss://" + Base64.getEncoder().encodeToString("chacha20-ietf-poly1305:pw@5.6.7.8:443".toByteArray()) + "#old")!!
        assertEquals("5.6.7.8", legacy.address); assertEquals("pw", legacy.password)
    }

    @Test fun hysteria2AndTrojan() {
        val h = LinkParser.parse("hy2://secret@hy.example:8443?sni=hy.example&obfs=salamander&obfs-password=ob#hy")!!
        assertEquals(Protocol.HYSTERIA2, h.protocol); assertEquals("ob", h.obfsPassword)
        val t = LinkParser.parse("trojan://pw@t.example:443?type=grpc&serviceName=svc#tr")!!
        assertEquals("tls", t.security); assertEquals("svc", t.serviceName)
    }

    @Test fun base64Subscription() {
        val body = Base64.getEncoder().encodeToString("vless://a@h1:443#a\ntrojan://b@h2:443#b\n".toByteArray())
        assertEquals(2, LinkParser.parseMany(body).size)
    }

    private val awgConf = """
        [Interface]
        PrivateKey = cHJpdmF0ZWtleXByaXZhdGVrZXlwcml2YXRla2V5MDA=
        Address = 10.8.1.2/32
        DNS = 1.1.1.1, 1.0.0.1
        Jc = 4
        Jmin = 10
        Jmax = 50
        S1 = 52
        S2 = 87
        H1 = 1234567
        H2 = 2345678
        H3 = 3456789
        H4 = 4567890

        [Peer]
        PublicKey = cHVibGlja2V5cHVibGlja2V5cHVibGlja2V5cHViMDA=
        PresharedKey = cHNrcHNrcHNrcHNrcHNrcHNrcHNrcHNrcHNrcHNrMDA=
        AllowedIPs = 0.0.0.0/0, ::/0
        Endpoint = 203.0.113.5:51820
        PersistentKeepalive = 25
    """.trimIndent()

    @Test fun amneziaWgConf() {
        val n = LinkParser.parseMany(awgConf, "home").single()
        assertEquals(Protocol.WIREGUARD, n.protocol); assertEquals("203.0.113.5", n.address); assertEquals(51820, n.port)
        assertEquals("4", n.awg["Jc"]); assertTrue(n.needsAmneziaEngine)
        val plain = WgConfParser.parse(awgConf.lines().filterNot { it.matches(Regex("^(S|H)\\d.*")) }.joinToString("\n"))
        assertFalse(plain.needsAmneziaEngine) // only junk packets: Xray can do it
    }

    private fun qCompress(s: String): String {
        val raw = s.toByteArray()
        val d = Deflater(); d.setInput(raw); d.finish()
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf((raw.size ushr 24).toByte(), (raw.size ushr 16).toByte(), (raw.size ushr 8).toByte(), raw.size.toByte()))
        val buf = ByteArray(4096); while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        return "vpn://" + Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
    }

    @Test fun amneziaVpnKey() {
        val lastConfig = kotlinx.serialization.json.JsonPrimitive(
            kotlinx.serialization.json.buildJsonObject { put("config", kotlinx.serialization.json.JsonPrimitive(awgConf.replace("DNS = 1.1.1.1, 1.0.0.1", "DNS = \$PRIMARY_DNS"))) }.toString()
        ).toString()
        val xrayCfg = kotlinx.serialization.json.JsonPrimitive("""{"outbounds":[{"protocol":"vless","settings":{"vnext":[{"address":"x.example","port":443,"users":[{"id":"u","flow":"xtls-rprx-vision","encryption":"none"}]}]},"streamSettings":{"network":"tcp","security":"reality","realitySettings":{"serverName":"sni.example","publicKey":"PK","shortId":"01","fingerprint":"chrome"}}},{"protocol":"freedom","tag":"direct"}]}""").toString()
        val json = """{"containers":[{"container":"amnezia-awg","awg":{"last_config":$lastConfig}},{"container":"amnezia-xray","xray":{"last_config":$xrayCfg}}],"defaultContainer":"amnezia-awg","description":"My Amnezia","dns1":"9.9.9.9","hostName":"203.0.113.5"}"""
        val nodes = LinkParser.parseMany(qCompress(json))
        assertEquals(2, nodes.size)
        assertEquals("9.9.9.9", nodes[0].wgDns); assertTrue(nodes[0].needsAmneziaEngine)
        assertEquals(Protocol.VLESS, nodes[1].protocol); assertEquals("PK", nodes[1].publicKey); assertEquals("My Amnezia · XRAY", nodes[1].name)
    }

    @Test fun xrayJsonArraySubscription() {
        val body = """[{"remarks":"🇩🇪 DE","outbounds":[{"tag":"proxy","protocol":"vless","settings":{"vnext":[{"address":"de.example","port":443,"users":[{"id":"id1","encryption":"none"}]}]},"streamSettings":{"network":"xhttp","security":"tls","tlsSettings":{"serverName":"de.example","alpn":["h2"]},"xhttpSettings":{"path":"/x","mode":"auto"}}}]},
            {"remarks":"🇫🇮 FI","outbounds":[{"tag":"proxy","protocol":"trojan","settings":{"servers":[{"address":"fi.example","port":443,"password":"p"}]}}]}]"""
        val nodes = LinkParser.parseMany(body)
        assertEquals(listOf("🇩🇪 DE", "🇫🇮 FI"), nodes.map { it.name })
        assertEquals("xhttp", nodes[0].network); assertEquals("/x", nodes[0].path)
    }

    @Test fun userInfoHeader() {
        assertEquals(listOf(1L, 2L, 3L, 4L), SubscriptionFetcher.parseUserInfo("upload=1; download=2; total=3; expire=4"))
        assertEquals("Hello", SubscriptionFetcher.decodeHeader("base64:SGVsbG8="))
    }

    /** Writes sample configs (system-proxy mode only) for validation with `xray run -test`. */
    @Test fun dumpSampleConfigs() {
        val dir = java.io.File("build/xray-configs").apply { deleteRecursively(); mkdirs() }
        val links = mapOf(
            "vless-reality" to "vless://11111111-2222-3333-4444-555555555555@example.com:443?type=tcp&security=reality&sni=www.microsoft.com&fp=chrome&pbk=SbVKOEMjK0sIlbwg4akyBg5mL5KZwwB-ed4eEE7YnRc&sid=ab12&flow=xtls-rprx-vision#a",
            "vless-xhttp" to "vless://11111111-2222-3333-4444-555555555555@example.com:443?type=xhttp&security=tls&sni=example.com&path=%2Fx&mode=auto&alpn=h2#b",
            "vmess-ws" to "vmess://" + Base64.getEncoder().encodeToString("""{"v":"2","ps":"vm","add":"1.2.3.4","port":"443","id":"11111111-2222-3333-4444-555555555555","aid":"0","net":"ws","host":"h.com","path":"/ws","tls":"tls"}""".toByteArray()),
            "trojan-grpc" to "trojan://pw@t.example:443?type=grpc&serviceName=svc&sni=t.example#tr",
            "ss" to "ss://" + Base64.getUrlEncoder().withoutPadding().encodeToString("aes-256-gcm:pass".toByteArray()) + "@5.6.7.8:8388#ss",
            "hy2" to "hy2://secret@hy.example:8443?sni=hy.example&obfs=salamander&obfs-password=ob#hy",
        )
        val nodes = links.mapValues { LinkParser.parse(it.value)!! } +
            ("awg-junk" to WgConfParser.parse(awgConf.lines().filterNot { it.matches(Regex("^(S|H)\\d.*")) }.joinToString("\n")))
        val routings = listOf(
            "rule" to RoutingSettings(rules = listOf(
                RouteRule(kind = RuleKind.DOMAIN, value = "example.org", action = RouteAction.DIRECT),
                RouteRule(kind = RuleKind.PROCESS, value = "telegram.exe", action = RouteAction.PROXY),
                RouteRule(kind = RuleKind.PORT, value = "6881-6889", action = RouteAction.BLOCK),
            )),
            "global" to RoutingSettings(mode = RoutingMode.GLOBAL),
            "direct" to RoutingSettings(mode = RoutingMode.DIRECT),
        )
        nodes.forEach { (name, n) ->
            routings.forEach { (rn, r) -> java.io.File(dir, "$name-$rn.json").writeText(XrayConfig.build(n, AppSettings(mux = true), r)) }
        }
    }

    @Test fun configNeverContainsRemovedAllowInsecure() {
        val n = LinkParser.parse("vless://u@h:443?security=tls&allowInsecure=1&sni=h#x")!!
        val cfg = XrayConfig.build(n, AppSettings(), RoutingSettings())
        assertFalse(cfg.contains("allowInsecure"))
    }
}
