package dev.uplinx.data

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class Protocol(private val ru: String) {
    VLESS("VLESS"), VMESS("VMess"), TROJAN("Trojan"), SHADOWSOCKS("Shadowsocks"),
    SOCKS("SOCKS"), HTTP("HTTP"), HYSTERIA2("Hysteria2"), WIREGUARD("WireGuard"),
    /** A full Xray client config (balancers, chains, custom routing) run as-is with our inbounds. */
    XRAY_CONFIG("Xray JSON");
    val title: String get() = dev.uplinx.L(ru)
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
    /** Full Xray JSON for [Protocol.XRAY_CONFIG] nodes. */
    val rawConfig: String = "",
    /** Outbound tags behind a balancer, for latency display; empty for plain nodes. */
    val balancerMembers: List<String> = emptyList(),
    // bookkeeping
    val subscriptionId: String? = null,
    val rawLink: String = "",
    val lastUsedAt: Long = 0,
) {
    /** Stable identity across subscription refreshes (used for hidden servers). */
    val identity: String get() = rawLink.ifBlank { "$protocol|$address|$port|$uuid$password|$name" }

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
    val pinned: Boolean = false,
    /** Cached logo from the profile-logo header (file in app data), or empty. */
    val logoFile: String = "",
    /** Extra links panels send (website, support, channels): label to URL. */
    val links: Map<String, String> = emptyMap(),
    /** Identities of servers the user hid; survive refreshes. */
    val hidden: Set<String> = emptySet(),
    /** Routing profile pushed by the panel (Happ-compatible `routing` header), applied on refresh if enabled. */
    val routingProfile: String = "",
    /** User-Agent that got real servers from this panel (auto-detected), empty = use the global one. */
    val userAgent: String = "",
    /** Used traffic over time, for the quota widget's chart (last 90 refreshes). */
    val usageHistory: List<UsagePoint> = emptyList(),
) {
    val expired: Boolean get() = expire > 0 && expire * 1000 < System.currentTimeMillis()
    val usedUp: Boolean get() = total > 0 && upload + download >= total
}

@Serializable
enum class RouteAction(private val ru: String) { PROXY("Прокси"), DIRECT("Напрямую"), BLOCK("Блок");
    val title: String get() = dev.uplinx.L(ru)
}

@Serializable
enum class RuleKind(private val ru: String) { DOMAIN("Домен"), IP("IP / CIDR"), GEOSITE("geosite"), GEOIP("geoip"), PROCESS("Процесс"), PORT("Порт");
    val title: String get() = dev.uplinx.L(ru)
}

@Serializable
data class RouteRule(
    val id: String = UUID.randomUUID().toString(),
    val kind: RuleKind,
    val value: String,
    val action: RouteAction,
    val enabled: Boolean = true,
)

@Serializable
enum class RoutingMode(private val ru: String, private val ruSubtitle: String) {
    RULE("По правилам", "Пресеты и свои правила"),
    GLOBAL("Глобальный", "Весь трафик через прокси"),
    DIRECT("Напрямую", "Ядро работает, трафик идёт мимо");
    val title: String get() = dev.uplinx.L(ru)
    val subtitle: String get() = dev.uplinx.L(ruSubtitle)
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
enum class ConnectMode(private val ru: String) { SYSTEM_PROXY("Системный прокси"), TUN("TUN (весь трафик)");
    val title: String get() = dev.uplinx.L(ru)
}

@Serializable
enum class ThemeMode(private val ru: String) { SYSTEM("Системная"), LIGHT("Светлая"), DARK("Тёмная");
    val title: String get() = dev.uplinx.L(ru)
}

@Serializable
enum class ColorSource(private val ru: String) { WINDOWS_ACCENT("Акцент Windows"), CUSTOM("Свой цвет");
    val title: String get() = dev.uplinx.L(ru)
}

@Serializable
enum class ClientSpoof(private val ru: String, val ua: String?) {
    NONE("Без маскировки", null),
    HAPP("Happ", "Happ/3.1.0"),
    INCY("INCY", "INCY/2.4.0");
    val title: String get() = dev.uplinx.L(ru)
}

@Serializable
enum class ServerSort(private val ru: String) { MANUAL("Как в подписке"), NAME("По имени"), PING("По пингу"), LAST_USED("Недавние");
    val title: String get() = dev.uplinx.L(ru)
}

@Serializable
enum class GeoSource(private val ru: String) { XRAY("Стандартные (v2fly)"), RUNETFREEDOM("runetfreedom (РФ)");
    val title: String get() = dev.uplinx.L(ru)
}

/** Home dashboard widgets. Sizes are in grid cells (12 columns). */
@Serializable
enum class WidgetType(val defW: Int, val defH: Int, val minW: Int, val minH: Int, val maxW: Int, val maxH: Int) {
    CONNECT(6, 6, 3, 3, 12, 9),
    SERVER(5, 2, 3, 2, 12, 2),
    MODES(6, 2, 4, 2, 12, 3),
    SPEED(5, 3, 3, 2, 12, 5),
    PING(2, 2, 2, 2, 4, 3),
    IP(3, 2, 3, 2, 6, 3),
    QUOTA(5, 2, 3, 2, 12, 3),
    SERVERS(4, 5, 3, 3, 8, 10),
    SESSION(3, 2, 2, 2, 6, 3),
    SITES(4, 4, 3, 3, 12, 10),
    USAGE(5, 3, 3, 2, 12, 6),
}

@Serializable
data class HomeWidget(
    val id: String = UUID.randomUUID().toString(),
    val type: WidgetType,
    val x: Int, val y: Int, val w: Int, val h: Int,
    /** Per-widget options, e.g. "chart" -> "off", "sort" -> "PING". */
    val opts: Map<String, String> = emptyMap(),
)

/** One sample of a subscription's used traffic, recorded on every refresh. */
@Serializable
data class UsagePoint(val t: Long, val used: Long)

fun defaultHomeWidgets() = listOf(
    HomeWidget(type = WidgetType.CONNECT, x = 0, y = 0, w = 7, h = 6),
    HomeWidget(type = WidgetType.SERVER, x = 7, y = 0, w = 5, h = 2),
    HomeWidget(type = WidgetType.SPEED, x = 7, y = 2, w = 5, h = 3),
    HomeWidget(type = WidgetType.PING, x = 7, y = 5, w = 2, h = 2),
    HomeWidget(type = WidgetType.IP, x = 9, y = 5, w = 3, h = 2),
    HomeWidget(type = WidgetType.MODES, x = 0, y = 6, w = 7, h = 2),
    HomeWidget(type = WidgetType.QUOTA, x = 7, y = 7, w = 5, h = 2),
)

@Serializable
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val colorSource: ColorSource = ColorSource.WINDOWS_ACCENT,
    val seedColor: Long = 0xFF1F8BFF,
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
    val clientSpoof: ClientSpoof = ClientSpoof.NONE,
    /** "system", "ru" or "en". English by default. */
    val language: String = "en",
    /** Sites the "site check" widget probes through the VPN. */
    val sites: List<String> = listOf("youtube.com", "instagram.com", "chatgpt.com", "x.com", "discord.com"),
    /** Traffic per day ("yyyy-MM-dd" → bytes) for the usage widget. */
    val usageDown: Map<String, Long> = emptyMap(),
    val usageUp: Map<String, Long> = emptyMap(),
    /** Home screen layout; editable by dragging widgets in edit mode. */
    val homeWidgets: List<HomeWidget> = defaultHomeWidgets(),
    /** Version whose "What's new" was already shown. */
    val lastSeenVersion: String = "",
    /** Random per-install device id sent as x-hwid. */
    val hwid: String = java.util.UUID.randomUUID().toString().replace("-", "").take(16),
    val serverSort: ServerSort = ServerSort.MANUAL,
    val maskAddresses: Boolean = false,
    val ipv6: Boolean = true,
    val showHidden: Boolean = false,
    val applyPanelRouting: Boolean = true,
    val checkUpdates: Boolean = true,
    val allTimeUp: Long = 0,
    val allTimeDown: Long = 0,
) {
    /** UA actually sent to panels: a spoofed client wins over the free-form setting. */
    val effectiveUserAgent: String get() = clientSpoof.ua ?: userAgent
}

@Serializable
data class AppState(
    val settings: AppSettings = AppSettings(),
    val routing: RoutingSettings = RoutingSettings(),
    val subscriptions: List<Subscription> = emptyList(),
    val nodes: List<ProxyNode> = emptyList(),
    /** Identities ([ProxyNode.identity]) of hidden servers; kept across subscription refreshes. */
    val hidden: Set<String> = emptySet(),
)
