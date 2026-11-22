package dev.uplinx

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import dev.uplinx.core.CoreStatus
import dev.uplinx.core.Traffic
import dev.uplinx.data.*
import dev.uplinx.ui.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * Renders every screen offscreen with demo data. No window, no core, no network changes.
 * Run: gradlew screenshots -Duplinx.home=<temp dir> -Dshots.out=<dir>
 */
object Screenshots {
    @JvmStatic fun main(args: Array<String>) {
        val out = File(System.getProperty("shots.out") ?: "build/screens").apply { mkdirs() }
        val now = System.currentTimeMillis()
        val sub = Subscription(name = "Nebula VPN", url = "https://example.com/sub/abc", upload = 3_400_000_000, download = 41_200_000_000,
            total = 100_000_000_000, expire = now / 1000 + 86400 * 23, updatedAt = now - 12 * 60_000, announce = "New servers in Finland 🇫🇮")
        val nodes = listOf(
            ProxyNode(name = "🇳🇱 Netherlands · Amsterdam", lastUsedAt = 1, protocol = Protocol.VLESS, address = "nl1.nebula.example", port = 443, security = "reality", subscriptionId = sub.id),
            ProxyNode(name = "🇩🇪 Germany · Frankfurt", protocol = Protocol.VLESS, address = "de1.nebula.example", port = 443, network = "xhttp", security = "tls", subscriptionId = sub.id),
            ProxyNode(name = "🇫🇮 Finland · Helsinki", protocol = Protocol.TROJAN, address = "fi1.nebula.example", port = 443, network = "grpc", security = "tls", subscriptionId = sub.id),
            ProxyNode(name = "🇺🇸 USA · New York", protocol = Protocol.HYSTERIA2, address = "us1.nebula.example", port = 8443, security = "tls", subscriptionId = sub.id),
            ProxyNode(name = "🇰🇿 Kazakhstan", protocol = Protocol.SHADOWSOCKS, address = "kz.nebula.example", port = 8388, subscriptionId = sub.id),
            ProxyNode(name = "Home AmneziaWG", protocol = Protocol.WIREGUARD, address = "203.0.113.5", port = 51820, awg = mapOf("Jc" to "4", "S1" to "52", "H1" to "1234567")),
            ProxyNode(name = "My VPS", protocol = Protocol.VMESS, address = "1.2.3.4", port = 8443, network = "ws", security = "tls"),
        )
        val state = AppState(
            settings = AppSettings(selectedNodeId = nodes[0].id, colorSource = ColorSource.CUSTOM, seedColor = (System.getProperty("shots.seed") ?: "FF1F8BFF").toLong(16),
                themeMode = if (System.getProperty("shots.dark") == "1") ThemeMode.DARK else ThemeMode.LIGHT, language = System.getProperty("shots.lang") ?: "ru"),
            routing = RoutingSettings(rules = listOf(
                RouteRule(kind = RuleKind.PROCESS, value = "telegram.exe, discord.exe", action = RouteAction.PROXY),
                RouteRule(kind = RuleKind.DOMAIN, value = "gosuslugi.ru, sberbank.ru", action = RouteAction.DIRECT),
                RouteRule(kind = RuleKind.PORT, value = "6881-6889", action = RouteAction.BLOCK),
            )),
            subscriptions = listOf(sub), nodes = nodes,
        )
        Storage.save(state)
        val app = AppController(CoroutineScope(SupervisorJob() + Dispatchers.Default))
        app.backgroundNetwork = false // documentation address only (RFC 5737), never the real one
        app.ipInfo.value = IpInfo("203.0.113.42", "Netherlands", "NL", "Amsterdam", "Nebula Hosting B.V.")
        listOf(48, 63, -1, 210, 95, 31, 540).zip(nodes).forEach { (p, n) -> setPing(app, n.id, p) }
        val hist = (0 until 60).map { i -> (40_000L + (i * 7919 % 50) * 3000) to (900_000L + ((i * 104729) % 37) * 120_000 + if (i > 40) 2_400_000 else 0) }
        app.core.previewState(CoreStatus.Running(now - 3_725_000), Traffic(up = 182_000, down = 4_350_000, totalUp = 96_000_000, totalDown = 1_840_000_000, history = hist))
        repeat(12) { app.core.log(listOf("[Info] proxy/vless/outbound: tunneling request to tcp:www.youtube.com:443 via nl1.nebula.example:443",
            "[Info] app/dispatcher: sniffed domain: api.telegram.org", "[Warning] transport/internet: failed to dial, retrying").let { l -> l[it % 3] }, if (it % 3 == 2) 'W' else 'I') }

        Screen.entries.forEach { screen ->
            ImageComposeScene(1700, 1060, Density(1.3f)) {
                val s by app.state.collectAsState()
                UplinxTheme(s.settings) { AppRoot(app, onExit = {}, initial = screen) }
            }.use { scene ->
                var t = 0L
                repeat(40) { scene.render(t); t += 50_000_000 } // let entry animations settle (2 s)
                val img = scene.render(t)
                File(out, "${screen.ordinal}-${screen.name.lowercase()}.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            }
        }
        // Home in widget edit mode
        dev.uplinx.ui.HomePreview.startInEdit = true
        ImageComposeScene(1700, 1060, Density(1.3f)) {
            val s by app.state.collectAsState()
            UplinxTheme(s.settings) { AppRoot(app, onExit = {}, initial = Screen.HOME) }
        }.use { scene ->
            var t = 0L
            repeat(40) { scene.render(t); t += 50_000_000 }
            File(out, "0b-home-edit.png").writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
        dev.uplinx.ui.HomePreview.startInEdit = false

        // showcase of every data widget, with demo data
        val today = java.time.LocalDate.now()
        app.updateSettings { st -> st.copy(
            usageDown = (0..13).associate { today.minusDays(it.toLong()).toString() to (800_000_000L + (it * 7919L % 13) * 260_000_000L) },
            usageUp = (0..13).associate { today.minusDays(it.toLong()).toString() to (60_000_000L + (it * 104729L % 7) * 30_000_000L) },
        ) }
        app.state.value.subscriptions.firstOrNull()?.let { sub ->
            val start = now - 7L * 86_400_000
            app.editSubscription(sub.copy(usageHistory = (0..12).map { i -> UsagePoint(start + i * 14L * 3_600_000, (i * i * 260_000_000L) + i * 900_000_000L) }))
        }
        app.pingHistory.value = listOf(52, 49, 61, 48, 47, 55, 120, 64, 51, 48, 46, 50, 49, 58, 47, 45, 48, 53, 49, 48)
        app.siteResults.value = mapOf(
            "youtube.com" to AppController.SiteResult(142, 200, now), "instagram.com" to AppController.SiteResult(188, 200, now),
            "chatgpt.com" to AppController.SiteResult(231, 200, now), "x.com" to AppController.SiteResult(-1, 0, now),
            "discord.com" to AppController.SiteResult(97, 200, now),
        )
        app.updateWidgets(listOf(
            HomeWidget(type = WidgetType.CONNECT, x = 0, y = 0, w = 4, h = 5),
            HomeWidget(type = WidgetType.SERVERS, x = 4, y = 0, w = 4, h = 9, opts = mapOf("sort" to "PING")),
            HomeWidget(type = WidgetType.PING, x = 8, y = 0, w = 4, h = 3),
            HomeWidget(type = WidgetType.QUOTA, x = 8, y = 3, w = 4, h = 3, opts = mapOf("chart" to "on")),
            HomeWidget(type = WidgetType.USAGE, x = 0, y = 5, w = 4, h = 4),
            HomeWidget(type = WidgetType.SITES, x = 8, y = 6, w = 4, h = 5),
        ))
        Thread.sleep(600) // let the debounced state settle
        ImageComposeScene(1700, 1160, Density(1.3f)) {
            val s by app.state.collectAsState()
            UplinxTheme(s.settings) { AppRoot(app, onExit = {}, initial = Screen.HOME) }
        }.use { scene ->
            var t = 0L
            repeat(40) { scene.render(t); t += 50_000_000 }
            File(out, "0c-home-widgets.png").writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
        println("screens written to ${out.absolutePath}")
        System.exit(0)
    }

    private fun setPing(app: AppController, id: String, ms: Int) {
        val f = AppController::class.java.getDeclaredField("_pings").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val flow = f.get(app) as kotlinx.coroutines.flow.MutableStateFlow<Map<String, Int>>
        flow.value = flow.value + (id to ms)
    }
}
