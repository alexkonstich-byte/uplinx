package dev.myproxy

import dev.myproxy.core.Errors
import dev.myproxy.core.LatencyTester
import dev.myproxy.core.Updater
import dev.myproxy.core.XrayConfig
import dev.myproxy.data.*
import dev.myproxy.parse.*
import java.util.Base64
import kotlin.test.*

class ImportFormatsTest {
    @Test fun clashYamlWithRules() {
        val yaml = """
            proxies:
              - name: "🇩🇪 DE"
                type: vless
                server: de.example
                port: 443
                uuid: 11111111-2222-3333-4444-555555555555
                network: tcp
                tls: true
                servername: www.microsoft.com
                client-fingerprint: chrome
                flow: xtls-rprx-vision
                reality-opts:
                  public-key: PUB
                  short-id: ab
              - {name: ss1, type: ss, server: 1.2.3.4, port: 8388, cipher: aes-256-gcm, password: pw}
              - {name: hy, type: hysteria2, server: hy.example, port: 443, password: secret, sni: hy.example}
              - {name: tr, type: trojan, server: tr.example, port: 443, password: p, network: ws, ws-opts: {path: /ws, headers: {Host: cdn.example}}}
            rules:
              - DOMAIN-SUFFIX,gosuslugi.ru,DIRECT
              - PROCESS-NAME,Telegram.exe,Proxy
              - GEOIP,RU,DIRECT
              - MATCH,Proxy
        """.trimIndent()
        val r = Importer.parse(yaml)
        assertEquals(4, r.nodes.size)
        val de = r.nodes[0]
        assertEquals("reality", de.security); assertEquals("PUB", de.publicKey); assertEquals("🇩🇪 DE", de.name)
        assertEquals("/ws", r.nodes[3].path); assertEquals("cdn.example", r.nodes[3].host)
        assertEquals(listOf(RuleKind.DOMAIN, RuleKind.PROCESS, RuleKind.GEOIP), r.rules.map { it.kind })
        assertEquals(RouteAction.PROXY, r.rules[1].action)
    }

    @Test fun singBoxOutbounds() {
        val sb = """{"outbounds":[
          {"type":"vless","tag":"vl","server":"a.example","server_port":443,"uuid":"u","flow":"xtls-rprx-vision",
           "tls":{"enabled":true,"server_name":"sni.example","utls":{"enabled":true,"fingerprint":"chrome"},"reality":{"enabled":true,"public_key":"PK","short_id":"01"}}},
          {"type":"hysteria2","tag":"hy","server":"h.example","server_port":8443,"password":"pw","obfs":{"type":"salamander","password":"ob"},"tls":{"enabled":true,"server_name":"h.example"}},
          {"type":"direct","tag":"direct"}]}"""
        val nodes = LinkParser.parseMany(sb)
        assertEquals(2, nodes.size)
        assertEquals("reality", nodes[0].security); assertEquals("PK", nodes[0].publicKey)
        assertEquals(Protocol.HYSTERIA2, nodes[1].protocol); assertEquals("ob", nodes[1].obfsPassword)
    }

    @Test fun jsoncAndLinkArray() {
        val jsonc = """
            // comment
            {
              "outbounds": [ /* inline */ {"protocol":"trojan","tag":"t","settings":{"servers":[{"address":"t.example","port":443,"password":"p"}]}}, ],
            }
        """.trimIndent()
        assertEquals(1, LinkParser.parseMany(jsonc).size)
        assertEquals(2, LinkParser.parseMany("""["vless://a@h1:443#a","trojan://b@h2:443#b"]""").size)
    }

    @Test fun proxyPoolsAndNested() {
        val r = Importer.parse("1.2.3.4:1080\n5.6.7.8:3128:user:pass\nu:p@9.9.9.9:1080\nhttps://panel.example/sub/xyz")
        assertEquals(3, r.nodes.size)
        assertEquals("user", r.nodes[1].username)
        assertEquals(listOf("https://panel.example/sub/xyz"), r.nestedSubscriptions)
    }

    @Test fun happRoutingProfile() {
        val profile = """{"Name":"RU","GlobalProxy":"true","DirectSites":["geosite:category-ru","domain:yandex.ru"],"DirectIp":["geoip:ru"],"BlockSites":["geosite:category-ads-all"],"DomainStrategy":"IPIfNonMatch"}"""
        val link = "happ://routing/add/" + Base64.getEncoder().encodeToString(profile.toByteArray())
        val r = Importer.parse(link)
        assertNotNull(r.routingProfile)
        val applied = HappRouting.apply(r.routingProfile!!, RoutingSettings(rules = listOf(RouteRule(kind = RuleKind.DOMAIN, value = "mine.example", action = RouteAction.PROXY))))!!
        assertEquals(RoutingMode.RULE, applied.mode)
        assertEquals(listOf(RouteAction.BLOCK, RouteAction.DIRECT, RouteAction.DIRECT, RouteAction.PROXY), applied.rules.map { it.action })
        assertEquals("mine.example", applied.rules.last().value) // user rules kept
    }

    private val balancerConfig = """{
      "remarks": "Auto 🇪🇺",
      "outbounds": [
        {"tag":"proxy-nl","protocol":"vless","settings":{"vnext":[{"address":"nl.example","port":443,"users":[{"id":"11111111-2222-3333-4444-555555555555","encryption":"none"}]}]},
         "streamSettings":{"network":"tcp","security":"tls","tlsSettings":{"serverName":"nl.example"}}},
        {"tag":"proxy-de","protocol":"trojan","settings":{"servers":[{"address":"de.example","port":443,"password":"p"}]},
         "streamSettings":{"network":"tcp","security":"tls","tlsSettings":{"serverName":"de.example"}}},
        {"tag":"direct","protocol":"freedom"}
      ],
      "observatory": {"subjectSelector":["proxy-"],"probeURL":"https://www.gstatic.com/generate_204","probeInterval":"1m"},
      "routing": {"balancers":[{"tag":"auto","selector":["proxy-"],"strategy":{"type":"leastPing"}}],
                  "rules":[{"type":"field","network":"tcp,udp","balancerTag":"auto"}]}
    }"""

    @Test fun xrayConfigWithBalancerKeptWhole() {
        val n = LinkParser.parseMany(balancerConfig).single()
        assertEquals(Protocol.XRAY_CONFIG, n.protocol)
        assertEquals(listOf("proxy-nl", "proxy-de"), n.balancerMembers)
        assertEquals("Auto 🇪🇺", n.name)
        val built = XrayConfig.build(n, AppSettings(), RoutingSettings())
        assertTrue("balancers" in built && "socks-in" in built && "StatsService" in built)
        java.io.File("build/xray-configs-extra").mkdirs()
        java.io.File("build/xray-configs-extra/raw-balancer.json").writeText(built)
        // latency probe targets: one per balancer member
        assertEquals(2, LatencyTester.targets(n, AppSettings()).size)
    }

    @Test fun friendlyErrors() {
        I18n.lang = Lang.RU
        assertEquals("Port already in use", run { I18n.lang = Lang.EN; Errors.explain("bind: address already in use").title }.also { I18n.lang = Lang.RU })
        assertEquals("Порт уже занят", Errors.explain("listen tcp 127.0.0.1:10808: bind: Only one usage of each socket address").title)
        assertEquals("Сервер не отвечает", Errors.explain("dial tcp: i/o timeout").title)
    }

    @Test fun versionCompare() {
        assertTrue(Updater.newer("v1.2.0", "1.1.9")); assertFalse(Updater.newer("1.1.0", "1.1.0")); assertTrue(Updater.newer("1.10.0", "1.9.3"))
    }
}
