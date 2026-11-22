package dev.uplinx

import dev.uplinx.core.*
import dev.uplinx.data.*
import dev.uplinx.parse.HappRouting
import dev.uplinx.parse.ImportResult
import dev.uplinx.parse.Importer
import dev.uplinx.parse.SubscriptionFetcher
import dev.uplinx.parse.WgConfParser
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.io.File
import java.net.URLDecoder

data class UiMessage(val text: String, val action: String? = null, val onAction: (() -> Unit)? = null)

/** A deep-link / second-instance import waiting for the user's OK. */
data class PendingImport(val text: String, val summary: String)

data class IpInfo(val ip: String, val country: String, val countryCode: String, val city: String, val isp: String)

const val PING_TESTING = -2
const val PING_FAILED = -1
/** TCP handshakes are faked by another VPN's TUN adapter: the number would be meaningless. */
const val PING_UNRELIABLE = -3

class AppController(private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(Storage.load())
    val state: StateFlow<AppState> = _state.asStateFlow()

    val core = CoreManager(scope)

    /** Last known delay per node id (ms, or [PING_FAILED]). Kept while a new probe runs, so the UI can strike it through. */
    private val _pings = MutableStateFlow<Map<String, Int>>(emptyMap())
    val pings: StateFlow<Map<String, Int>> = _pings.asStateFlow()
    private val _testing = MutableStateFlow<Set<String>>(emptySet())
    val testing: StateFlow<Set<String>> = _testing.asStateFlow()

    private val _updating = MutableStateFlow<Set<String>>(emptySet())
    val updatingSubs: StateFlow<Set<String>> = _updating.asStateFlow()

    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 8)
    val messages: SharedFlow<UiMessage> = _messages.asSharedFlow()

    /** The last connection failure in plain words; the UI offers its details. */
    val lastError = MutableStateFlow<FriendlyError?>(null)

    val needAdmin = MutableStateFlow(false)
    val needAmnezia = MutableStateFlow(false)
    val pendingImport = MutableStateFlow<PendingImport?>(null)
    val update = MutableStateFlow<UpdateInfo?>(null)
    val updateProgress = MutableStateFlow<Float?>(null)
    val miniWindow = MutableStateFlow(false)
    val whatsNew = MutableStateFlow<UpdateInfo?>(null)
    val showUpdateDetails = MutableStateFlow(false)

    private val connectLock = Mutex()
    private var runningSignature: String? = null
    private var pingJob: Job? = null

    val isConnected get() = core.status.value is CoreStatus.Running

    init {
        I18n.apply(_state.value.settings.language)
        SubscriptionFetcher.hwid = _state.value.settings.hwid
        if (SystemProxy.isEnabledByUs) runCatching { SystemProxy.restore() }
        scope.launch {
            _state.drop(1).debounce(400).collect { s -> withContext(Dispatchers.IO) { runCatching { Storage.save(s) } } }
        }
        scope.launch { autoUpdateLoop() }
        scope.launch {
            core.status.collect { st -> if (st is CoreStatus.Failed && runningSignature != null) onCoreDied(st.message) }
        }
        // while connected: ping the current server through the tunnel every 20 s (ping chart widget)
        scope.launch(Dispatchers.IO) {
            core.status.map { it is CoreStatus.Running }.distinctUntilChanged().collectLatest { running ->
                if (!running) return@collectLatest
                if (!backgroundNetwork) return@collectLatest
                pingHistory.value = emptyList()
                while (true) {
                    val ms = measureThroughTunnel(_state.value.settings.pingUrl)
                    pingHistory.update { (it + ms).takeLast(60) }
                    selectedNode?.let { n -> if (ms >= 0) _pings.update { p -> p + (n.id to ms) } }
                    delay(20_000)
                }
            }
        }
        // site check widget: on connect, then every minute while connected
        scope.launch(Dispatchers.IO) {
            core.status.map { it is CoreStatus.Running }.distinctUntilChanged().collectLatest { running ->
                if (!running) return@collectLatest
                delay(2_000)
                while (backgroundNetwork) {
                    if (_state.value.settings.homeWidgets.any { it.type == WidgetType.SITES }) checkSites().join()
                    delay(60_000)
                }
            }
        }
        // the IP widget follows connect / disconnect
        scope.launch {
            core.status.map { it is CoreStatus.Running }.distinctUntilChanged().collect {
                if (_state.value.settings.homeWidgets.any { w -> w.type == WidgetType.IP }) { delay(1500); refreshIp() }
            }
        }
        // all-time traffic: accumulate in memory, flush to state every 30 s and on disconnect
        scope.launch {
            var pendingUp = 0L; var pendingDown = 0L; var last = System.currentTimeMillis()
            core.traffic.collect { t ->
                pendingUp += t.up; pendingDown += t.down
                if ((System.currentTimeMillis() - last > 30_000 || core.status.value !is CoreStatus.Running) && (pendingUp + pendingDown) > 0) {
                    val u = pendingUp; val d = pendingDown; pendingUp = 0; pendingDown = 0; last = System.currentTimeMillis()
                    val day = java.time.LocalDate.now().toString()
                    update {
                        val st = it.settings
                        // keep ~120 days of daily totals for the usage widget
                        fun Map<String, Long>.add(v: Long) = (this + (day to (this[day] ?: 0L) + v)).entries.sortedBy { e -> e.key }.takeLast(120).associate { e -> e.key to e.value }
                        it.copy(settings = st.copy(allTimeUp = st.allTimeUp + u, allTimeDown = st.allTimeDown + d,
                            usageUp = st.usageUp.add(u), usageDown = st.usageDown.add(d)))
                    }
                }
            }
        }
        // updates from GitHub Releases: shortly after start, then every 6 hours
        scope.launch(Dispatchers.IO) {
            delay(8_000)
            while (true) {
                if (_state.value.settings.checkUpdates) runCatching { Updater.check() }.onSuccess { u ->
                    if (u != null && update.value?.version != u.version && _state.value.settings.notifyUpdates)
                        toast(L("Доступна версия %1", u.version), L("Откройте Uplinx, чтобы обновиться"))
                    update.value = u
                }
                delay(6 * 3_600_000L)
            }
        }
        // "What's new" once after an update
        scope.launch(Dispatchers.IO) {
            val seen = _state.value.settings.lastSeenVersion
            val now = BuildInfo.version
            if (now != "dev" && seen != now) {
                if (seen.isNotBlank()) Updater.notesFor(now)?.let { whatsNew.value = it }
                update { it.copy(settings = it.settings.copy(lastSeenVersion = now)) }
            }
        }
    }

    /** Windows notifications (shown by the tray icon). */
    data class Toast(val title: String, val text: String, val error: Boolean)
    private val _toasts = MutableSharedFlow<Toast>(extraBufferCapacity = 8)
    val toasts: SharedFlow<Toast> = _toasts.asSharedFlow()
    fun toast(title: String, text: String, error: Boolean = false) { _toasts.tryEmit(Toast(title, text, error)) }

    /** Once a day: warn about subscriptions that end within 3 days or ran out of traffic. */
    private fun checkSubscriptionWarnings() {
        val st = _state.value
        val today = java.time.LocalDate.now().toString()
        if (!st.settings.notifySubscription || st.settings.subWarnedDay == today) return
        val soon = st.subscriptions.filter { s ->
            val days = if (s.expire > 0) (s.expire * 1000 - System.currentTimeMillis()) / 86_400_000 else Long.MAX_VALUE
            days in 0..3 || s.usedUp || s.expired
        }
        if (soon.isEmpty()) return
        soon.forEach { s ->
            val days = (s.expire * 1000 - System.currentTimeMillis()) / 86_400_000
            toast(s.name, when {
                s.expired -> L("Подписка истекла")
                s.usedUp -> L("Трафик исчерпан")
                else -> L("Подписка закончится через %1 дн.", days.coerceAtLeast(0))
            }, error = s.expired || s.usedUp)
        }
        update { it.copy(settings = it.settings.copy(subWarnedDay = today)) }
    }

    fun notify(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
        _messages.tryEmit(UiMessage(text, action, onAction))
    }

    private fun update(block: (AppState) -> AppState) = _state.update(block)

    // ================================================================ selection

    val selectedNode: ProxyNode?
        get() = _state.value.let { s -> s.nodes.firstOrNull { it.id == s.settings.selectedNodeId } ?: s.nodes.firstOrNull() }

    fun select(node: ProxyNode) {
        val changed = _state.value.settings.selectedNodeId != node.id
        update { it.copy(settings = it.settings.copy(selectedNodeId = node.id)) }
        if (changed && isConnected) reconnect()
    }

    private fun touchLastUsed(id: String) = update { s ->
        s.copy(nodes = s.nodes.map { if (it.id == id) it.copy(lastUsedAt = System.currentTimeMillis()) else it })
    }

    // ================================================================ connection

    fun toggle() {
        when (core.status.value) {
            is CoreStatus.Running -> disconnect()
            is CoreStatus.Stopped, is CoreStatus.Failed -> connect()
            else -> Unit
        }
    }

    fun connect() = scope.launch { connectLock.withLock { connectInternal() } }

    private suspend fun connectInternal() {
        val st = _state.value
        val node = selectedNode ?: run { notify(L("Сначала добавьте сервер или подписку")); return }
        val sub = st.subscriptions.firstOrNull { it.id == node.subscriptionId }
        if (sub?.expired == true) notify(L("Подписка «%1» истекла — сервер может не работать", sub.name))
        lastError.value = null
        val tun = st.settings.connectMode == ConnectMode.TUN
        if (node.needsAmneziaEngine) {
            if (!core.amneziaInstalled) { needAmnezia.value = true; return }
            if (!WinUtil.isAdmin) { needAdmin.value = true; return }
            try {
                core.startAmnezia(WgConfParser.toConf(node))
                runningSignature = "awg:" + node.id
                touchLastUsed(node.id)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                runningSignature = null
                fail(e.message ?: e.toString())
            }
            return
        }
        if (tun && !WinUtil.isAdmin) { needAdmin.value = true; return }
        try {
            core.installCore(geo = st.settings.geoSource)
            if (tun) core.ensureWintun()
            val config = XrayConfig.build(node, st.settings, st.routing)
            core.start(config, st.settings.apiPort)
            runningSignature = config
            if (!tun) SystemProxy.enable(st.settings.httpPort, st.settings.socksPort)
            touchLastUsed(node.id)
            core.log(L("Подключено: %1 (%2)", node.name, st.settings.connectMode.title))
            if (st.settings.notifyConnection) toast(L("Подключено"), "${node.name} · ${st.settings.connectMode.title}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runningSignature = null
            runCatching { SystemProxy.restore() }
            runCatching { core.stop() }
            fail(e.message ?: e.toString())
        }
    }

    private fun fail(raw: String) {
        val fe = Errors.explain(raw)
        lastError.value = fe
        core.log(L("Ошибка: %1\n%2", fe.title, fe.details), 'E')
        notify("${fe.title}. ${fe.hint}", L("Подробнее")) { lastError.value = fe; showErrorDetails.value = true }
        if (_state.value.settings.notifyErrors) toast(fe.title, fe.hint, error = true)
    }

    val showErrorDetails = MutableStateFlow(false)

    private val _openLogs = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val openLogs: SharedFlow<Unit> = _openLogs
    fun openLogs() { _openLogs.tryEmit(Unit) }

    fun disconnect() = scope.launch {
        connectLock.withLock {
            val wasRunning = isConnected
            runningSignature = null
            runCatching { SystemProxy.restore() }
            core.stop()
            if (wasRunning && _state.value.settings.notifyConnection) toast(L("Отключено"), L("Трафик идёт напрямую"))
        }
    }

    fun reconnect() = scope.launch {
        connectLock.withLock {
            runCatching { SystemProxy.restore() }
            core.stop()
            connectInternal()
        }
    }

    private fun onCoreDied(message: String) {
        runningSignature = null
        runCatching { SystemProxy.restore() }
        val fe = Errors.explain(message)
        lastError.value = fe
        notify(L("Ядро остановилось: %1. Системный прокси отключён", fe.title), L("Подробнее")) { showErrorDetails.value = true }
        if (_state.value.settings.notifyErrors) toast(L("Соединение потеряно"), L("Ядро остановилось: %1. Системный прокси отключён", fe.title), error = true)
    }

    fun shutdown() {
        runCatching { SystemProxy.restore() }
        core.stopBlocking()
        runCatching { Storage.save(_state.value) }
        // a background-downloaded update installs when the app quits
        updateReady.value?.let { p -> updateReady.value = null; runCatching { Updater.apply(p) } }
    }

    private fun suggestReconnect() {
        if (!isConnected) return
        val st = _state.value
        val node = selectedNode ?: return
        if (!node.needsAmneziaEngine && XrayConfig.build(node, st.settings, st.routing) != runningSignature) {
            notify(L("Настройки изменены"), L("Переподключить")) { reconnect() }
        }
    }

    // ================================================================ settings / routing

    fun updateSettings(block: (AppSettings) -> AppSettings) {
        val before = _state.value.settings
        update { it.copy(settings = block(it.settings)) }
        val after = _state.value.settings
        val coreFields = { a: AppSettings -> listOf(a.connectMode, a.socksPort, a.httpPort, a.apiPort, a.allowLan, a.sniffing, a.remoteDns, a.directDns, a.mux, a.logLevel, a.tunStrictDns, a.geoSource, a.ipv6) }
        if (coreFields(before) != coreFields(after)) suggestReconnect()
    }

    fun updateRouting(block: (RoutingSettings) -> RoutingSettings) {
        update { it.copy(routing = block(it.routing)) }
        suggestReconnect()
    }

    fun setConnectMode(mode: ConnectMode) {
        if (mode == _state.value.settings.connectMode) return
        update { it.copy(settings = it.settings.copy(connectMode = mode)) }
        if (isConnected) reconnect()
    }

    fun setRoutingMode(mode: RoutingMode) {
        if (mode == _state.value.routing.mode) return
        update { it.copy(routing = it.routing.copy(mode = mode)) }
        if (isConnected) reconnect()
    }

    fun updateCore(force: Boolean = true) = scope.launch {
        if (isConnected) { notify(L("Сначала отключитесь")); return@launch }
        runCatching { core.installCore(force = force, geo = _state.value.settings.geoSource) }
            .onSuccess { notify(L("Ядро обновлено: %1", core.version.value ?: "?")) }
            .onFailure { notify(L("Ошибка обновления: %1", Errors.explain(it.message.orEmpty()).title)) }
    }

    fun updateGeo() = scope.launch {
        runCatching { core.updateGeo(_state.value.settings.geoSource) }
            .onSuccess { notify(L("Гео-базы обновлены")); suggestReconnect() }
            .onFailure { notify(L("Ошибка: %1", it.message)) }
    }

    // ================================================================ nodes

    fun upsertNode(node: ProxyNode) {
        update { s ->
            val exists = s.nodes.any { it.id == node.id }
            s.copy(nodes = if (exists) s.nodes.map { if (it.id == node.id) node else it } else s.nodes + node)
        }
        if (isConnected && selectedNode?.id == node.id) suggestReconnect()
    }

    fun deleteNode(node: ProxyNode) {
        val before = _state.value
        update { s -> s.copy(nodes = s.nodes - node) }
        notify(L("«%1» удалён", node.name), L("Отменить")) { _state.value = before }
    }

    fun setHidden(node: ProxyNode, hidden: Boolean) {
        update { s -> s.copy(hidden = if (hidden) s.hidden + node.identity else s.hidden - node.identity) }
        if (hidden) notify(L("«%1» скрыт", node.name), L("Отменить")) { setHidden(node, false) }
    }

    // ================================================================ import

    /** Imports anything; returns how many things were added. */
    fun importText(text: String, nameHint: String? = null): Int {
        val t = text.trim()
        if (t.isEmpty()) return 0
        // deep links: uplinx://add/<anything> (and the former myproxy://), also the common <client>://add/ form
        Regex("""^[a-z0-9]+://(add|import|sub|install-config)/(.+)$""", RegexOption.IGNORE_CASE).find(t)?.let { m ->
            if (!t.startsWith("vless") && !t.startsWith("vmess")) return importText(URLDecoder.decode(m.groupValues[2], Charsets.UTF_8), nameHint)
        }
        if ((t.startsWith("http://") || t.startsWith("https://")) && !t.contains('\n') && !t.contains('@')) {
            addSubscription("", t)
            return 1
        }
        return applyImport(Importer.parse(t, nameHint))
    }

    private fun applyImport(r: ImportResult): Int {
        var count = 0
        if (r.nodes.isNotEmpty()) {
            update { s -> s.copy(nodes = s.nodes + r.nodes) }
            if (_state.value.settings.selectedNodeId == null) update { it.copy(settings = it.settings.copy(selectedNodeId = r.nodes.first().id)) }
            count += r.nodes.size
        }
        r.nestedSubscriptions.forEach { addSubscription("", it); count++ }
        if (r.rules.isNotEmpty()) {
            updateRouting { it.copy(rules = it.rules + r.rules) }
            count += r.rules.size
        }
        r.routingProfile?.let { p ->
            val before = _state.value.routing
            HappRouting.apply(p, before)?.let { nr ->
                updateRouting { nr }
                notify(L("Профиль маршрутизации применён"), L("Отменить")) { updateRouting { before } }
                count++
            }
        }
        if (count > 0) {
            val parts = listOfNotNull(
                r.nodes.size.takeIf { it > 0 }?.let { L("серверов: %1", it) },
                r.nestedSubscriptions.size.takeIf { it > 0 }?.let { L("подписок: %1", it) },
                r.rules.size.takeIf { it > 0 }?.let { L("правил: %1", it) },
            )
            if (parts.isNotEmpty()) notify(L("Добавлено — ") + parts.joinToString(", "))
        }
        return count
    }

    /** Imports coming from outside the window (deep links, second instance) wait for confirmation. */
    fun requestImport(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val inner = Regex("""^[a-z0-9]+://(?:add|import|sub|install-config)/(.+)$""", RegexOption.IGNORE_CASE).find(t)
            ?.let { URLDecoder.decode(it.groupValues[1], Charsets.UTF_8) } ?: t
        val summary = when {
            inner.startsWith("http") && !inner.contains('@') -> L("Подписка: %1", inner)
            else -> Importer.parse(inner).let { r -> L("Серверов: %1", r.nodes.size) + if (r.routingProfile != null) L(", профиль маршрутизации") else "" }
        }
        pendingImport.value = PendingImport(inner, summary)
    }

    fun importFiles(files: List<File>) {
        var total = 0
        files.forEach { f ->
            if (QrScanner.isImage(f)) {
                val codes = QrScanner.fromFile(f)
                if (codes.isEmpty()) notify(L("%1: QR-код не найден", f.name)) else codes.forEach { total += importText(it) }
                return@forEach
            }
            val text = runCatching { f.readText() }.getOrNull() ?: return@forEach
            val n = importText(text, f.nameWithoutExtension)
            if (n == 0) notify(L("%1: не распознан формат", f.name))
            total += n
        }
    }

    fun importFromClipboard() {
        val text = runCatching { Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as String }.getOrNull()
        if (!text.isNullOrBlank() && importText(text) > 0) return
        // maybe there is a screenshot of a QR code in the clipboard
        val codes = QrScanner.fromClipboard()
        if (codes.isNotEmpty()) { codes.forEach { importText(it) }; return }
        notify(L("В буфере обмена нет ссылок, конфигов или QR-кода"))
    }

    fun scanScreenQr() = scope.launch(Dispatchers.IO) {
        val codes = QrScanner.fromScreen()
        withContext(Dispatchers.Default) {
            if (codes.isEmpty()) notify(L("На экране не найдено QR-кодов. Откройте QR и попробуйте снова"))
            else codes.forEach { importText(it) }
        }
    }

    fun copyToClipboard(text: String, label: String = L("Скопировано")) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        notify(label)
    }

    // ================================================================ ping

    private val tcpDispatcher = Dispatchers.IO.limitedParallelism(25)

    /** Real delay through Xray when possible, TCP handshake otherwise. Previously reachable servers go first. */
    fun pingAll(ids: Collection<String>? = null) {
        val st = _state.value
        val nodes = st.nodes.filter { (ids == null || it.id in ids) && (ids != null || st.settings.showHidden || it.identity !in st.hidden) }
        if (nodes.isEmpty()) return
        val prev = _pings.value
        val ordered = nodes.sortedBy { n -> prev[n.id]?.let { if (it >= 0) it else Int.MAX_VALUE - 1 } ?: (Int.MAX_VALUE - 2) }
        _testing.update { it + ordered.map { n -> n.id } }
        pingJob = scope.launch(Dispatchers.IO) {
            // a real URL test needs the core: fetch it (just files, nothing is started or routed)
            if (!core.hasCore) runCatching { core.installCore(geo = st.settings.geoSource) }
                .onFailure { notify(L("Не удалось скачать ядро для точного пинга: %1", it.message ?: "")) }
            val useCore = core.hasCore
            val viaCore = if (useCore) ordered.filterNot { it.needsAmneziaEngine } else emptyList()
            val viaTcp = ordered - viaCore.toSet()
            val best = mutableMapOf<String, Int>()
            val pendingMembers = mutableMapOf<String, Int>()
            launch {
                val targets = viaCore.flatMap { n -> LatencyTester.targets(n, st.settings).also { pendingMembers[n.id] = it.size } }
                viaCore.filter { (pendingMembers[it.id] ?: 0) == 0 }.forEach { finish(it.id, -1) }
                LatencyTester.probe(targets, st.settings.pingUrl) { key, ms ->
                    val id = key.substringBefore('#')
                    synchronized(best) {
                        // balancer: show the best reachable member
                        val cur = best[id]
                        best[id] = if (cur == null || cur < 0 || (ms in 0 until cur)) ms else cur
                        pendingMembers[id] = (pendingMembers[id] ?: 1) - 1
                        if (key.contains('#')) _pings.update { it + (id to best[id]!!) }
                        if (pendingMembers[id]!! <= 0) finish(id, best[id]!!)
                    }
                }
            }
            if (viaTcp.isNotEmpty()) {
                // another VPN in TUN mode answers every handshake itself: don't show fake 1 ms
                val faked = Ping.handshakesFaked()
                if (faked) notify(L("Другой VPN в режиме TUN подделывает TCP-соединения — простой пинг недостоверен. Отключите его или используйте пинг через ядро"))
                viaTcp.forEach { n -> launch(tcpDispatcher) { finish(n.id, if (faked) PING_UNRELIABLE else Ping.tcp(n)) } }
            }
        }
    }

    private fun finish(id: String, ms: Int) {
        _pings.update { it + (id to ms) }
        _testing.update { it - id }
    }

    fun pingNode(id: String) = pingAll(listOf(id))

    fun pingCurrentReal() {
        val node = selectedNode ?: return
        if (!isConnected) { pingNode(node.id); return }
        _testing.update { it + node.id }
        scope.launch(Dispatchers.IO) {
            val s = _state.value.settings
            finish(node.id, Ping.throughProxy(s.httpPort, s.pingUrl))
        }
    }

    // ================================================================ public IP (dashboard widget)

    val ipInfo = MutableStateFlow<IpInfo?>(null)
    /** Off for offscreen screenshots: no IP lookups, tunnel pings or site checks, so nothing real ends up in docs. */
    var backgroundNetwork = true
    val ipLoading = MutableStateFlow(false)

    /** What the internet sees: through the local proxy when connected in proxy mode, directly otherwise. */
    fun refreshIp() = scope.launch(Dispatchers.IO) {
        if (ipLoading.value || !backgroundNetwork) return@launch
        ipLoading.value = true
        try {
            val s = _state.value.settings
            val b = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(8))
            if (isConnected && s.connectMode == ConnectMode.SYSTEM_PROXY) b.proxy(java.net.ProxySelector.of(java.net.InetSocketAddress("127.0.0.1", s.httpPort)))
            val req = java.net.http.HttpRequest.newBuilder(java.net.URI("https://ipwho.is/")).timeout(java.time.Duration.ofSeconds(10))
                .header("User-Agent", "Uplinx").GET().build()
            val body = b.build().send(req, java.net.http.HttpResponse.BodyHandlers.ofString()).body()
            val o = kotlinx.serialization.json.Json.parseToJsonElement(body).let { it as kotlinx.serialization.json.JsonObject }
            fun str(vararg path: String): String {
                var e: kotlinx.serialization.json.JsonElement? = o
                path.forEach { k -> e = (e as? kotlinx.serialization.json.JsonObject)?.get(k) }
                return (e as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
            }
            ipInfo.value = IpInfo(str("ip"), str("country"), str("country_code"), str("city"), str("connection", "isp"))
        } catch (e: CancellationException) { throw e } catch (_: Exception) {
            ipInfo.value = null
        } finally { ipLoading.value = false }
    }

    // ================================================================ ping chart & site checks

    /** Recent round-trips through the tunnel to the probe URL, oldest first; -1 = failed. */
    val pingHistory = MutableStateFlow<List<Int>>(emptyList())

    data class SiteResult(val ms: Int, val code: Int, val checkedAt: Long)
    val siteResults = MutableStateFlow<Map<String, SiteResult>>(emptyMap())
    val sitesChecking = MutableStateFlow(false)

    /** A client that goes where the user's traffic goes: via the local proxy in proxy mode, plain in TUN or when off. */
    private fun tunnelClient(): java.net.http.HttpClient {
        val s = _state.value.settings
        val b = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(6)).followRedirects(java.net.http.HttpClient.Redirect.NEVER)
        if (isConnected && s.connectMode == ConnectMode.SYSTEM_PROXY) b.proxy(java.net.ProxySelector.of(java.net.InetSocketAddress("127.0.0.1", s.httpPort)))
        return b.build()
    }

    private fun timedGet(client: java.net.http.HttpClient, url: String): Pair<Int, Int> = runCatching {
        val req = java.net.http.HttpRequest.newBuilder(java.net.URI(url)).timeout(java.time.Duration.ofSeconds(8))
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Uplinx").GET().build()
        val t0 = System.nanoTime()
        val r = client.send(req, java.net.http.HttpResponse.BodyHandlers.discarding())
        ((System.nanoTime() - t0) / 1_000_000).toInt().coerceAtLeast(1) to r.statusCode()
    }.getOrDefault(-1 to 0)

    private fun measureThroughTunnel(url: String): Int {
        val c = tunnelClient()
        timedGet(c, url) // warm-up: TLS handshake
        return timedGet(c, url).first
    }

    fun checkSites() = scope.launch(Dispatchers.IO) {
        if (sitesChecking.value) return@launch
        sitesChecking.value = true
        try {
            val client = tunnelClient()
            _state.value.settings.sites.map { site ->
                async {
                    val url = if (site.contains("://")) site else "https://$site"
                    val (ms, code) = timedGet(client, url)
                    siteResults.update { it + (site to SiteResult(ms, code, System.currentTimeMillis())) }
                }
            }.awaitAll()
        } finally { sitesChecking.value = false }
    }

    // ================================================================ speed test

    val speedProgress = MutableStateFlow<SpeedProgress?>(null)
    val speedTestingNode = MutableStateFlow<String?>(null)

    /** Download / upload / ping of [node] through a throwaway core — works with or without a connection. */
    fun runSpeedTest(node: ProxyNode? = selectedNode) = scope.launch(Dispatchers.IO) {
        val n = node ?: run { notify(L("Сначала добавьте сервер или подписку")); return@launch }
        if (speedTestingNode.value != null) return@launch
        speedTestingNode.value = n.id
        try {
            if (!core.hasCore) core.installCore(geo = _state.value.settings.geoSource)
            val r = SpeedTester.run(n, _state.value.settings) { speedProgress.value = it }
            update { it.copy(settings = it.settings.copy(speedResults = (it.settings.speedResults + r).takeLast(30))) }
            if (r.pingMs >= 0) _pings.update { it + (n.id to r.pingMs) }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            speedProgress.value = null
            notify(L("Тест скорости не удался: %1", Errors.explain(e.message ?: "").title))
        } finally { speedTestingNode.value = null }
    }

    fun setSites(list: List<String>) = update { it.copy(settings = it.settings.copy(sites = list.map { s -> s.trim() }.filter { s -> s.isNotEmpty() }.distinct())) }

    fun updateWidgets(list: List<HomeWidget>) = update { it.copy(settings = it.settings.copy(homeWidgets = list)) }

    /** Atomic edit of the layout from its *current* value — never from a list captured earlier by the UI. */
    fun editWidgets(block: (List<HomeWidget>) -> List<HomeWidget>) = update { it.copy(settings = it.settings.copy(homeWidgets = block(it.settings.homeWidgets))) }

    // ================================================================ subscriptions

    fun addSubscription(name: String, url: String) {
        val clean = url.trim()
        if (_state.value.subscriptions.any { it.url == clean }) { notify(L("Эта подписка уже добавлена")); return }
        val sub = Subscription(name = name.ifBlank { L("Новая подписка") }, url = clean)
        update { it.copy(subscriptions = it.subscriptions + sub) }
        updateSubscription(sub.id)
    }

    fun editSubscription(sub: Subscription) {
        update { s -> s.copy(subscriptions = s.subscriptions.map { if (it.id == sub.id) sub else it }) }
    }

    fun togglePin(sub: Subscription) = editSubscription(sub.copy(pinned = !sub.pinned))

    fun deleteSubscription(sub: Subscription) {
        val before = _state.value
        update { s -> s.copy(subscriptions = s.subscriptions - sub, nodes = s.nodes.filterNot { it.subscriptionId == sub.id }) }
        notify(L("Подписка «%1» удалена", sub.name), L("Отменить")) { _state.value = before }
    }

    fun updateSubscription(id: String) = scope.launch(Dispatchers.IO) {
        val sub = _state.value.subscriptions.firstOrNull { it.id == id } ?: return@launch
        if (id in _updating.value) return@launch
        _updating.update { it + id }
        try {
            val res = SubscriptionFetcher.fetch(sub, _state.value.settings.effectiveUserAgent)
            if (res.subscription.userAgent.isNotBlank() && res.subscription.userAgent != sub.userAgent) {
                notify(L("«%1» не принимал текущий клиент — подобрана маскировка: %2", res.subscription.name, res.subscription.userAgent.substringBefore('/')))
            }
            val selected = selectedNode
            update { s ->
                val old = s.nodes.filter { it.subscriptionId == id }
                val merged = res.nodes.map { n -> old.firstOrNull { it.identity == n.identity }?.let { n.copy(id = it.id, lastUsedAt = it.lastUsedAt) } ?: n }
                val firstIdx = s.nodes.indexOfFirst { it.subscriptionId == id }.let { if (it < 0) s.nodes.size else it }
                val rest = s.nodes.filterNot { it.subscriptionId == id }.toMutableList()
                rest.addAll(firstIdx.coerceAtMost(rest.size), merged)
                var settings = s.settings
                if (selected != null && selected.subscriptionId == id && merged.none { it.id == selected.id }) {
                    settings = settings.copy(selectedNodeId = (merged.firstOrNull { it.name == selected.name } ?: merged.first()).id)
                } else if (settings.selectedNodeId == null) settings = settings.copy(selectedNodeId = merged.first().id)
                val withHistory = res.subscription.copy(usageHistory = (sub.usageHistory + UsagePoint(System.currentTimeMillis(), res.subscription.upload + res.subscription.download)).takeLast(90))
                s.copy(nodes = rest, subscriptions = s.subscriptions.map { if (it.id == id) withHistory else it }, settings = settings)
            }
            // panel-pushed routing: Happ-compatible profile header or rules inside a Clash/Mihomo subscription
            if (_state.value.settings.applyPanelRouting) {
                res.subscription.routingProfile.takeIf { it.isNotBlank() && it != sub.routingProfile }?.let { p ->
                    HappRouting.apply(p, _state.value.routing)?.let { nr -> updateRouting { nr }; notify(L("«%1»: применён профиль маршрутизации от сервиса", res.subscription.name)) }
                }
                if (res.rules.isNotEmpty()) updateRouting { r ->
                    r.copy(rules = res.rules.mapIndexed { i, rule -> rule.copy(id = "panel-$id-$i") } + r.rules.filterNot { it.id.startsWith("panel-$id-") })
                }
            }
            if (selected?.subscriptionId == id && isConnected) suggestReconnect()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            update { s -> s.copy(subscriptions = s.subscriptions.map { if (it.id == id) it.copy(lastError = msg) else it }) }
            notify("«${sub.name}»: $msg")
        } finally {
            _updating.update { it - id }
        }
    }

    fun updateAllSubscriptions() = _state.value.subscriptions.forEach { updateSubscription(it.id) }

    private suspend fun autoUpdateLoop() {
        delay(5_000)
        while (true) {
            checkSubscriptionWarnings()
            val now = System.currentTimeMillis()
            _state.value.subscriptions
                .filter { it.autoUpdateHours > 0 && now - it.updatedAt > it.autoUpdateHours * 3_600_000L }
                .forEach { updateSubscription(it.id) }
            delay(10 * 60_000)
        }
    }

    // ================================================================ routing rules

    fun upsertRule(rule: RouteRule) = updateRouting { r ->
        r.copy(rules = if (r.rules.any { it.id == rule.id }) r.rules.map { if (it.id == rule.id) rule else it } else r.rules + rule)
    }

    fun deleteRule(rule: RouteRule) = updateRouting { r -> r.copy(rules = r.rules - rule) }

    fun moveRule(rule: RouteRule, delta: Int) = updateRouting { r ->
        val i = r.rules.indexOf(rule); val j = i + delta
        if (i < 0 || j !in r.rules.indices) r else r.copy(rules = r.rules.toMutableList().apply { add(j, removeAt(i)) })
    }

    // ================================================================ misc

    fun relaunchAsAdmin(exit: () -> Unit) {
        needAdmin.value = false
        if (WinUtil.relaunchAsAdmin("--connect")) { shutdown(); exit() }
        else notify(L("Запуск от администратора отменён"))
    }

    fun checkForUpdates(manual: Boolean = true) = scope.launch(Dispatchers.IO) {
        runCatching { Updater.check() }
            .onSuccess { u -> update.value = u; if (manual && u == null) notify(L("У вас последняя версия %1", BuildInfo.version)) }
            .onFailure { if (manual) notify(L("Не удалось проверить обновления: %1", it.message)) }
    }

    /** A downloaded update waiting to be applied (on restart or when the app quits). */
    val updateReady = MutableStateFlow<Updater.Prepared?>(null)
    private var updateJob: Job? = null

    private fun ensureDownload(): Job? {
        val u = update.value ?: return null
        if (updateReady.value?.version == u.version) return null
        updateJob?.takeIf { it.isActive }?.let { return it }
        if (u.assetUrl == null && u.zipUrl == null) { WinUtil.openUrl(u.url); return null }
        return scope.launch(Dispatchers.IO) {
            runCatching {
                updateProgress.value = 0f
                Updater.prepare(u) { updateProgress.value = it }
            }.onSuccess { p ->
                updateProgress.value = null
                updateReady.value = p
            }.onFailure {
                updateProgress.value = null
                notify(L("Ошибка загрузки обновления: %1", it.message))
            }
        }.also { updateJob = it }
    }

    /** Download (if needed), then restart into the new version. */
    fun installUpdate(exit: () -> Unit) = scope.launch(Dispatchers.IO) {
        ensureDownload()?.join()
        val p = updateReady.value ?: return@launch
        runCatching { Updater.apply(p) }.onSuccess { updateReady.value = null; shutdown(); exit() }
            .onFailure { notify(L("Ошибка загрузки обновления: %1", it.message)) }
    }

    /** Keep working; the update downloads quietly and installs on restart or when Uplinx quits. */
    fun updateInBackground(exit: () -> Unit) {
        showUpdateDetails.value = false
        val job = ensureDownload()
        scope.launch {
            job?.join()
            val p = updateReady.value ?: return@launch
            toast(L("Обновление %1 готово", p.version), L("Установится при перезапуске Uplinx"))
            notify(L("Обновление %1 готово", p.version), L("Перезапустить")) { installUpdate(exit) }
        }
    }
}
