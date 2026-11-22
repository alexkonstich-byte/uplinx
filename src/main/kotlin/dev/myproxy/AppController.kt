package dev.myproxy

import dev.myproxy.core.CoreManager
import dev.myproxy.core.CoreStatus
import dev.myproxy.core.Ping
import dev.myproxy.core.SystemProxy
import dev.myproxy.core.WinUtil
import dev.myproxy.core.XrayConfig
import dev.myproxy.data.*
import dev.myproxy.parse.LinkParser
import dev.myproxy.parse.SubscriptionFetcher
import dev.myproxy.parse.WgConfParser
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.net.URLDecoder

data class UiMessage(val text: String, val action: String? = null, val onAction: (() -> Unit)? = null)

const val PING_TESTING = -2
const val PING_FAILED = -1

class AppController(private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(Storage.load())
    val state: StateFlow<AppState> = _state.asStateFlow()

    val core = CoreManager(scope)

    private val _pings = MutableStateFlow<Map<String, Int>>(emptyMap())
    val pings: StateFlow<Map<String, Int>> = _pings.asStateFlow()

    private val _updating = MutableStateFlow<Set<String>>(emptySet())
    val updatingSubs: StateFlow<Set<String>> = _updating.asStateFlow()

    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 8)
    val messages: SharedFlow<UiMessage> = _messages.asSharedFlow()

    /** Set when TUN needs admin rights: the UI shows a dialog offering to restart elevated. */
    val needAdmin = MutableStateFlow(false)
    /** Set when an AmneziaWG config needs the official AmneziaWG client, which is not installed. */
    val needAmnezia = MutableStateFlow(false)

    private val connectLock = Mutex()
    /** Config that the running core was started with; used to tell if a reconnect is needed. */
    private var runningSignature: String? = null

    val isConnected get() = core.status.value is CoreStatus.Running

    init {
        // crash recovery: a previous run may have left the system proxy pointing at a dead port
        if (SystemProxy.isEnabledByUs) runCatching { SystemProxy.restore() }
        scope.launch {
            _state.drop(1).debounce(400).collect { s -> withContext(Dispatchers.IO) { runCatching { Storage.save(s) } } }
        }
        scope.launch { autoUpdateLoop() }
        scope.launch {
            // the core died on its own: undo the system proxy so the user is not left offline
            core.status.collect { st -> if (st is CoreStatus.Failed && runningSignature != null) onCoreDied() }
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

    // ================================================================ connection

    fun toggle() {
        when (core.status.value) {
            is CoreStatus.Running -> disconnect()
            is CoreStatus.Stopped, is CoreStatus.Failed -> connect()
            else -> Unit
        }
    }

    fun connect() = scope.launch {
        connectLock.withLock { connectInternal() }
    }

    private suspend fun connectInternal() {
        val st = _state.value
        val node = selectedNode ?: run { notify("Сначала добавьте сервер или подписку"); return }
        val tun = st.settings.connectMode == ConnectMode.TUN
        if (node.needsAmneziaEngine) {
            // full AmneziaWG (padding / custom headers): only the official engine speaks it
            if (!core.amneziaInstalled) { needAmnezia.value = true; return }
            if (!WinUtil.isAdmin) { needAdmin.value = true; return }
            try {
                core.startAmnezia(WgConfParser.toConf(node))
                runningSignature = "awg:" + node.id
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                runningSignature = null
                notify("AmneziaWG: ${e.message?.lineSequence()?.first()}", "Логи") { _openLogs.tryEmit(Unit) }
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
            core.log("Подключено: ${node.name} (${st.settings.connectMode.title})")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runningSignature = null
            runCatching { SystemProxy.restore() }
            runCatching { core.stop() }
            val msg = e.message ?: e.toString()
            core.log("Ошибка подключения: $msg", 'E')
            notify("Не удалось подключиться: ${msg.lineSequence().first()}", "Логи") { _openLogs.tryEmit(Unit) }
        }
    }

    private val _openLogs = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val openLogs: SharedFlow<Unit> = _openLogs

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

    private fun onCoreDied() {
        runningSignature = null
        runCatching { SystemProxy.restore() }
        notify("Ядро остановилось, системный прокси отключён", "Логи") { _openLogs.tryEmit(Unit) }
    }

    /** Called from shutdown hook / tray exit. Never leaves the system proxy enabled. */
    fun shutdown() {
        runCatching { SystemProxy.restore() }
        core.stopBlocking()
        runCatching { Storage.save(_state.value) }
    }

    private fun suggestReconnect() {
        if (!isConnected) return
        val st = _state.value
        val node = selectedNode ?: return
        if (XrayConfig.build(node, st.settings, st.routing) != runningSignature) {
            notify("Настройки изменены", "Переподключить") { reconnect() }
        }
    }

    // ================================================================ settings / routing

    fun updateSettings(block: (AppSettings) -> AppSettings) {
        val before = _state.value.settings
        update { it.copy(settings = block(it.settings)) }
        val after = _state.value.settings
        if (before.copy(selectedNodeId = null, themeMode = after.themeMode, colorSource = after.colorSource, seedColor = after.seedColor,
                paletteStyle = after.paletteStyle, amoled = after.amoled, minimizeToTray = after.minimizeToTray,
                autoConnect = after.autoConnect, userAgent = after.userAgent) != after.copy(selectedNodeId = null)) {
            suggestReconnect()
        }
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
        if (isConnected) { notify("Сначала отключитесь"); return@launch }
        runCatching { core.installCore(force = force, geo = _state.value.settings.geoSource) }
            .onSuccess { notify("Ядро обновлено: ${core.version.value ?: "?"}") }
            .onFailure { notify("Ошибка обновления: ${it.message}") }
    }

    fun updateGeo() = scope.launch {
        runCatching { core.updateGeo(_state.value.settings.geoSource) }
            .onSuccess { notify("Гео-базы обновлены"); suggestReconnect() }
            .onFailure { notify("Ошибка: ${it.message}") }
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
        notify("«${node.name}» удалён", "Отменить") { _state.value = before }
    }

    fun importText(text: String): Int {
        val t = text.trim()
        if (t.isEmpty()) return 0
        // deep links: myproxy://add/<anything>, happ-like
        Regex("""^[a-z0-9]+://(add|import|sub)/(.+)$""", RegexOption.IGNORE_CASE).find(t)?.let { m ->
            if (!t.startsWith("vless") && !t.startsWith("vmess")) return importText(URLDecoder.decode(m.groupValues[2], Charsets.UTF_8))
        }
        if ((t.startsWith("http://") || t.startsWith("https://")) && !t.contains('\n')) {
            addSubscription("Новая подписка", t)
            return 1
        }
        val nodes = LinkParser.parseMany(t)
        if (nodes.isNotEmpty()) {
            update { s -> s.copy(nodes = s.nodes + nodes) }
            if (_state.value.settings.selectedNodeId == null) update { it.copy(settings = it.settings.copy(selectedNodeId = nodes.first().id)) }
            notify("Добавлено серверов: ${nodes.size}")
        }
        return nodes.size
    }

    /** Drag & drop / "Open file": .conf, .json, .txt with links, Amnezia .vpn keys. */
    fun importFiles(files: List<File>) {
        var total = 0
        files.forEach { f ->
            val text = runCatching { f.readText() }.getOrNull() ?: return@forEach
            val nodes = runCatching { LinkParser.parseMany(text, f.nameWithoutExtension) }.getOrDefault(emptyList())
            if (nodes.isNotEmpty()) {
                update { s -> s.copy(nodes = s.nodes + nodes) }
                total += nodes.size
            } else notify("${f.name}: не распознан формат")
        }
        if (total > 0) {
            if (_state.value.settings.selectedNodeId == null) update { it.copy(settings = it.settings.copy(selectedNodeId = it.nodes.firstOrNull()?.id)) }
            notify("Импортировано серверов: $total")
        }
    }

    fun importFromClipboard() {
        val text = runCatching {
            Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as String
        }.getOrNull()
        if (text.isNullOrBlank() || importText(text) == 0) notify("В буфере обмена нет ссылок или подписки")
    }

    fun copyToClipboard(text: String, label: String = "Скопировано") {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        notify(label)
    }

    // ================================================================ ping

    private val pingDispatcher = Dispatchers.IO.limitedParallelism(16)

    fun pingAll(ids: Collection<String>? = null) {
        val tunRunning = isConnected && _state.value.settings.connectMode == ConnectMode.TUN
        if (tunRunning) { notify("В режиме TUN TCP-пинг неточен — проверяется только текущий сервер"); pingCurrentReal(); return }
        val nodes = _state.value.nodes.filter { ids == null || it.id in ids }
        _pings.update { p -> p + nodes.associate { it.id to PING_TESTING } }
        nodes.forEach { n ->
            scope.launch(pingDispatcher) {
                val ms = Ping.tcp(n)
                _pings.update { it + (n.id to ms) }
            }
        }
    }

    fun pingCurrentReal() {
        val node = selectedNode ?: return
        if (!isConnected) { pingAll(listOf(node.id)); return }
        _pings.update { it + (node.id to PING_TESTING) }
        scope.launch(Dispatchers.IO) {
            val s = _state.value.settings
            _pings.update { it + (node.id to Ping.throughProxy(s.httpPort, s.pingUrl)) }
        }
    }

    fun sortByPing() {
        val p = _pings.value
        update { s -> s.copy(nodes = s.nodes.sortedBy { n -> p[n.id]?.takeIf { it >= 0 } ?: Int.MAX_VALUE }) }
    }

    // ================================================================ subscriptions

    fun addSubscription(name: String, url: String) {
        val sub = Subscription(name = name.ifBlank { "Новая подписка" }, url = url.trim())
        update { it.copy(subscriptions = it.subscriptions + sub) }
        updateSubscription(sub.id)
    }

    fun editSubscription(sub: Subscription) {
        update { s -> s.copy(subscriptions = s.subscriptions.map { if (it.id == sub.id) sub else it }) }
    }

    fun deleteSubscription(sub: Subscription) {
        val before = _state.value
        update { s -> s.copy(subscriptions = s.subscriptions - sub, nodes = s.nodes.filterNot { it.subscriptionId == sub.id }) }
        notify("Подписка «${sub.name}» удалена", "Отменить") { _state.value = before }
    }

    fun updateSubscription(id: String) = scope.launch(Dispatchers.IO) {
        val sub = _state.value.subscriptions.firstOrNull { it.id == id } ?: return@launch
        if (id in _updating.value) return@launch
        _updating.update { it + id }
        try {
            val res = SubscriptionFetcher.fetch(sub, _state.value.settings.userAgent)
            val selected = selectedNode
            update { s ->
                val old = s.nodes.filter { it.subscriptionId == id }
                // keep ids stable for servers that did not change, so selection and pings survive updates
                val merged = res.nodes.map { n -> old.firstOrNull { it.rawLink == n.rawLink }?.let { n.copy(id = it.id) } ?: n }
                val firstIdx = s.nodes.indexOfFirst { it.subscriptionId == id }.let { if (it < 0) s.nodes.size else it }
                val rest = s.nodes.filterNot { it.subscriptionId == id }.toMutableList()
                rest.addAll(firstIdx.coerceAtMost(rest.size), merged)
                var settings = s.settings
                if (selected != null && selected.subscriptionId == id && merged.none { it.id == selected.id }) {
                    // the selected server disappeared: fall back to a same-named one, or the first of this subscription
                    settings = settings.copy(selectedNodeId = (merged.firstOrNull { it.name == selected.name } ?: merged.first()).id)
                } else if (settings.selectedNodeId == null) settings = settings.copy(selectedNodeId = merged.first().id)
                s.copy(nodes = rest, subscriptions = s.subscriptions.map { if (it.id == id) res.subscription else it }, settings = settings)
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

    fun relaunchAsAdmin(exit: () -> Unit) {
        needAdmin.value = false
        if (WinUtil.relaunchAsAdmin("--connect")) { shutdown(); exit() }
        else notify("Запуск от администратора отменён")
    }
}
