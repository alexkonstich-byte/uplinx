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

const val PING_TESTING = -2
const val PING_FAILED = -1

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
        // all-time traffic: accumulate in memory, flush to state every 30 s and on disconnect
        scope.launch {
            var pendingUp = 0L; var pendingDown = 0L; var last = System.currentTimeMillis()
            core.traffic.collect { t ->
                pendingUp += t.up; pendingDown += t.down
                if ((System.currentTimeMillis() - last > 30_000 || core.status.value !is CoreStatus.Running) && (pendingUp + pendingDown) > 0) {
                    val u = pendingUp; val d = pendingDown; pendingUp = 0; pendingDown = 0; last = System.currentTimeMillis()
                    update { it.copy(settings = it.settings.copy(allTimeUp = it.settings.allTimeUp + u, allTimeDown = it.settings.allTimeDown + d)) }
                }
            }
        }
        // updates from GitHub Releases: shortly after start, then every 6 hours
        scope.launch(Dispatchers.IO) {
            delay(8_000)
            while (true) {
                if (_state.value.settings.checkUpdates) runCatching { Updater.check() }.onSuccess { update.value = it }
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
    }

    val showErrorDetails = MutableStateFlow(false)

    private val _openLogs = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val openLogs: SharedFlow<Unit> = _openLogs
    fun openLogs() { _openLogs.tryEmit(Unit) }

    fun disconnect() = scope.launch {
        connectLock.withLock {
            runningSignature = null
            runCatching { SystemProxy.restore() }
            core.stop()
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
    }

    fun shutdown() {
        runCatching { SystemProxy.restore() }
        core.stopBlocking()
        runCatching { Storage.save(_state.value) }
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
            viaTcp.forEach { n -> launch(tcpDispatcher) { finish(n.id, Ping.tcp(n)) } }
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
                s.copy(nodes = rest, subscriptions = s.subscriptions.map { if (it.id == id) res.subscription else it }, settings = settings)
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

    fun installUpdate(exit: () -> Unit) = scope.launch(Dispatchers.IO) {
        val u = update.value ?: return@launch
        if (u.assetUrl == null) { WinUtil.openUrl(u.url); return@launch }
        runCatching {
            updateProgress.value = 0f
            Updater.downloadAndRun(u) { updateProgress.value = it }
        }.onSuccess { shutdown(); exit() }
            .onFailure { updateProgress.value = null; notify(L("Ошибка загрузки обновления: %1", it.message)) }
    }
}
