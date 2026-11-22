package dev.myproxy.data

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class Protocol(val title: String) {
    VLESS("VLESS"), VMESS("VMess"), TROJAN("Trojan"), SHADOWSOCKS("Shadowsocks"),
    SOCKS("SOCKS"), HTTP("HTTP"), HYSTERIA2("Hysteria2"), WIREGUARD("WireGuard")
}

/** One server. Flat on purpose: every share-link format maps into these fields. */
@Serializable
data class ProxyNode(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val protocol: Protocol,
    val address: String,
    val port: Int,
    // credentials
    val uuid: String = "",          // vless/vmess id
    val password: String = "",      // trojan/ss/hy2/socks/http password
    val username: String = "",      // socks/http user
    val method: String = "",        // ss cipher
    val flow: String = "",          // vless flow (xtls-rprx-vision)
    val encryption: String = "none",
    val alterId: Int = 0,
    val vmessSecurity: String = "auto",
    // transport
    val network: String = "tcp",    // tcp, ws, grpc, httpupgrade, xhttp, kcp, h2
    val headerType: String = "none",
    val host: String = "",
    val path: String = "",
    val serviceName: String = "",
    val grpcMode: String = "gun",
    val xhttpMode: String = "auto",
    val xhttpExtra: String = "",
    // security
    val security: String = "none",  // none, tls, reality
    val sni: String = "",
    val alpn: String = "",
    val fingerprint: String = "",
    val publicKey: String = "",
    val shortId: String = "",
    val spiderX: String = "",
    val allowInsecure: Boolean = false,   // legacy flag from links; Xray removed it, kept only to warn
    val pinnedPeerCertSha256: String = "", // pcs
    val verifyPeerCertByName: String = "", // vcn
    val echConfigList: String = "",        // ech
    // hysteria2
    val obfs: String = "",
    val obfsPassword: String = "",
    val upMbps: Int = 0,
    val downMbps: Int = 0,
    // wireguard
    val wgPrivateKey: String = "",
    val wgPublicKey: String = "",
    val wgPreSharedKey: String = "",
    val wgAddresses: String = "",
    val wgReserved: String = "",
    val wgMtu: Int = 1420,
    val wgDns: String = "",
    val wgAllowedIps: String = "0.0.0.0/0, ::/0",
    val wgKeepalive: Int = 0,
    /** AmneziaWG obfuscation keys exactly as in the .conf: Jc, Jmin, Jmax, S1..S4, H1..H4, I1..I5 */
    val awg: Map<String, String> = emptyMap(),
    /** Original wg-quick text for AmneziaWG configs, handed to the AmneziaWG engine unchanged. */
    val wgConfText: String = "",
    // bookkeeping
    val subscriptionId: String? = null,
    val rawLink: String = "",
) {
    val endpoint get() = if (address.contains(':')) "[$address]:$port" else "$address:$port"
    /** True when the config uses AWG features Xray cannot reproduce (padding, custom headers, I-packets). */
    val needsAmneziaEngine: Boolean
        get() = protocol == Protocol.WIREGUARD && awg.any { (k, v) ->
            val key = k.uppercase()
            when {
                key.startsWith("S") -> v.trim() != "0" && v.isNotBlank()
                key.startsWith("H") -> v.trim() != key.removePrefix("H")
                key.startsWith("I") -> v.isNotBlank()
                else -> false
            }
        }

    val transportLabel: String
        get() = if (protocol == Protocol.WIREGUARD) (if (awg.isNotEmpty()) "AmneziaWG" else "WireGuard") else buildString {
            append(network.uppercase())
            if (security != "none" && security.isNotBlank()) append(" · ").append(security.uppercase())
        }
}

@Serializable
data class Subscription(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    val upload: Long = 0,
    val download: Long = 0,
    val total: Long = 0,
    val expire: Long = 0,           // unix seconds, 0 = never
    val updatedAt: Long = 0,        // unix millis
    val autoUpdateHours: Int = 12,
    val supportUrl: String = "",
    val webPageUrl: String = "",
    val announce: String = "",
    val lastError: String = "",
)

@Serializable
enum class RouteAction(val title: String) { PROXY("Прокси"), DIRECT("Напрямую"), BLOCK("Блок") }

@Serializable
enum class RuleKind(val title: String) { DOMAIN("Домен"), IP("IP / CIDR"), GEOSITE("geosite"), GEOIP("geoip"), PROCESS("Процесс"), PORT("Порт") }

@Serializable
data class RouteRule(
    val id: String = UUID.randomUUID().toString(),
    val kind: RuleKind,
    val value: String,
    val action: RouteAction,
    val enabled: Boolean = true,
)

@Serializable
enum class RoutingMode(val title: String, val subtitle: String) {
    RULE("По правилам", "Пресеты и свои правила"),
    GLOBAL("Глобальный", "Весь трафик через прокси"),
    DIRECT("Напрямую", "Ядро работает, трафик идёт мимо"),
}

@Serializable
data class RoutingSettings(
    val mode: RoutingMode = RoutingMode.RULE,
    val bypassLan: Boolean = true,
    val bypassRu: Boolean = true,
    val blockAds: Boolean = true,
    val proxyBlockedRu: Boolean = false,  // runetfreedom geosite:ru-blocked only
    val domainStrategy: String = "IPIfNonMatch",
    val rules: List<RouteRule> = emptyList(),
)

@Serializable
enum class ConnectMode(val title: String) { SYSTEM_PROXY("Системный прокси"), TUN("TUN (весь трафик)") }

@Serializable
enum class ThemeMode(val title: String) { SYSTEM("Системная"), LIGHT("Светлая"), DARK("Тёмная") }

@Serializable
enum class ColorSource(val title: String) { WINDOWS_ACCENT("Акцент Windows"), CUSTOM("Свой цвет") }

@Serializable
enum class GeoSource(val title: String) { XRAY("Стандартные (v2fly)"), RUNETFREEDOM("runetfreedom (РФ)") }

@Serializable
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val colorSource: ColorSource = ColorSource.WINDOWS_ACCENT,
    val seedColor: Long = 0xFF6750A4,
    val paletteStyle: String = "TonalSpot",
    val amoled: Boolean = false,
    val connectMode: ConnectMode = ConnectMode.SYSTEM_PROXY,
    val socksPort: Int = 10808,
    val httpPort: Int = 10809,
    val apiPort: Int = 10813,
    val allowLan: Boolean = false,
    val sniffing: Boolean = true,
    val remoteDns: String = "https://1.1.1.1/dns-query",
    val directDns: String = "77.88.8.8",
    val mux: Boolean = false,
    val logLevel: String = "warning",
    val tunStrictDns: Boolean = true,
    val minimizeToTray: Boolean = true,
    val autoConnect: Boolean = false,
    val geoSource: GeoSource = GeoSource.XRAY,
    val userAgent: String = "v2rayN/7.13",
    val pingUrl: String = "https://www.gstatic.com/generate_204",
    val selectedNodeId: String? = null,
)

@Serializable
data class AppState(
    val settings: AppSettings = AppSettings(),
    val routing: RoutingSettings = RoutingSettings(),
    val subscriptions: List<Subscription> = emptyList(),
    val nodes: List<ProxyNode> = emptyList(),
)
