package dev.dshn.app.runtime

import dev.dshn.app.acp.AcpConfigOption
import dev.dshn.app.acp.PermissionOption
import dev.dshn.app.acp.SurfaceHistoryFold
import dev.dshn.app.data.BannerAction
import dev.dshn.app.data.SettingsStore
import dev.dshn.app.data.StatusBannerBus
import dev.dshn.app.data.resolveIsZhLocale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.UUID

/**
 * Shared coordinator managing the ACP runtime connection, sessions, streaming transcript,
 * pending permission/question queues, API key sync, and runtime logs.
 */
class RuntimeSessionCoordinator(
    private val runtimeController: RuntimeController,
    private val settingsStore: SettingsStore,
    private val environmentCoordinator: EnvironmentCoordinator,
    private val statusBannerBus: StatusBannerBus,
    private val scope: CoroutineScope
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; isLenient = true }

    val runtimeStatus: StateFlow<RuntimeUiStatus> = runtimeController.status

    private val _messages = MutableStateFlow<List<UiChatMessage>>(emptyList())
    val messages: StateFlow<List<UiChatMessage>> = _messages.asStateFlow()

    private val _activeSessionId = MutableStateFlow<String?>(null)
    val activeSessionId: StateFlow<String?> = _activeSessionId.asStateFlow()

    private val _activeSessionReadOnly = MutableStateFlow(false)
    val activeSessionReadOnly: StateFlow<Boolean> = _activeSessionReadOnly.asStateFlow()

    private val _sessionAttached = MutableStateFlow(false)
    val sessionAttached: StateFlow<Boolean> = _sessionAttached.asStateFlow()

    private val _activeCwd = MutableStateFlow(settingsStore.current().defaultCwd.ifBlank { "/root" })
    val activeCwd: StateFlow<String> = _activeCwd.asStateFlow()

    private val _streaming = MutableStateFlow(false)
    val streaming: StateFlow<Boolean> = _streaming.asStateFlow()

    private val _sessionBusy = MutableStateFlow(false)
    val sessionBusy: StateFlow<Boolean> = _sessionBusy.asStateFlow()

    private val _configOptions = MutableStateFlow<List<AcpConfigOption>>(emptyList())
    val configOptions: StateFlow<List<AcpConfigOption>> = _configOptions.asStateFlow()

    private val _usageUsed = MutableStateFlow(0L)
    val usageUsed: StateFlow<Long> = _usageUsed.asStateFlow()

    private val _usageSize = MutableStateFlow(0L)
    val usageSize: StateFlow<Long> = _usageSize.asStateFlow()

    private val _slashCommands = MutableStateFlow<List<SlashCommandDescriptor>>(emptyList())
    val slashCommands: StateFlow<List<SlashCommandDescriptor>> = _slashCommands.asStateFlow()

    private val _pendingPermission = MutableStateFlow<PendingAcpPermissionUi?>(null)
    val pendingPermission: StateFlow<PendingAcpPermissionUi?> = _pendingPermission.asStateFlow()

    private val _pendingQuestion = MutableStateFlow<UserQuestionRequest?>(null)
    val pendingQuestion: StateFlow<UserQuestionRequest?> = _pendingQuestion.asStateFlow()

    private val _sessionsAwaitingInput = MutableStateFlow<Set<String>>(emptySet())
    val sessionsAwaitingInput: StateFlow<Set<String>> = _sessionsAwaitingInput.asStateFlow()

    private val _interruptedTurnBanner = MutableStateFlow<String?>(null)
    val interruptedTurnBanner: StateFlow<String?> = _interruptedTurnBanner.asStateFlow()

    private val _sessions = MutableStateFlow<List<SessionSummaryItem>>(emptyList())
    val sessions: StateFlow<List<SessionSummaryItem>> = _sessions.asStateFlow()

    private val _sessionsLoading = MutableStateFlow(false)
    val sessionsLoading: StateFlow<Boolean> = _sessionsLoading.asStateFlow()

    private val _sessionsError = MutableStateFlow<String?>(null)
    val sessionsError: StateFlow<String?> = _sessionsError.asStateFlow()

    private val _runtimeLogs = MutableStateFlow<List<String>>(emptyList())
    val runtimeLogs: StateFlow<List<String>> = _runtimeLogs.asStateFlow()

    private val _apiKeyMasked = MutableStateFlow<String?>(null)
    val apiKeyMasked: StateFlow<String?> = _apiKeyMasked.asStateFlow()

    private val sessionOpMutex = Mutex()
    private val streamingSessions = LinkedHashSet<String>()
    private val resumedMidStream = HashSet<String>()
    private var sessionsRefreshGen = 0
    private val pendingPermissionsBySession = LinkedHashMap<String, ArrayDeque<PendingAcpPermissionUi>>()
    private val pendingQuestionsBySession = LinkedHashMap<String, ArrayDeque<UserQuestionRequest>>()
    private var attachedRestartCount: Int = -1
    private var currentStreamSessionId: String? = null
    private var saveApiKeyJob: Job? = null

    private var lastSubmittedPromptText: String? = null
    private var currentAssistantMsgId: String? = null
    private val streamTextBuf = StringBuilder()
    private val streamThinkBuf = StringBuilder()
    private val streamTools = mutableListOf<UiToolCall>()
    private var streamFlushJob: Job? = null
    private var streamFlushDirty: Boolean = false
    private var streamFirstChunkEmitted: Boolean = false
    private var fastChatActive: Boolean = false
    private var fastChatHasLocalTurns: Boolean = false

    val nativeFastChatAvailable: Boolean get() = DshnNativeCore.isAvailable
    val nativeEngineInfo: String get() = DshnNativeCore.engineInfo()

    init {
        collectRuntimeStatus()
        collectRuntimeEvents()
        collectEnvironmentReattachRequests()
    }

    private fun isZh(): Boolean = resolveIsZhLocale(settingsStore.current().localeMode)
    private fun t(zh: String, en: String): String = if (isZh()) zh else en

    private fun banner(text: String, action: BannerAction? = null) {
        statusBannerBus.show(text, action)
    }

    fun isRuntimeUsable(): Boolean {
        val st = runtimeStatus.value.state
        return st == RuntimeLifecycleState.HEALTHY || st == RuntimeLifecycleState.DEGRADED
    }

    fun resolveSessionDisplayLabel(sessionId: String?): String? {
        if (sessionId.isNullOrBlank()) return null
        val match = _sessions.value.firstOrNull { it.id == sessionId }
        val title = match?.title?.takeIf { it.isNotBlank() }
            ?: if (sessionId == _activeSessionId.value) {
                _messages.value.firstOrNull { it.role == "user" }?.text?.lineSequence()?.firstOrNull()?.take(32)
            } else null
        val cwd = match?.cwd?.takeIf { it.isNotBlank() }
            ?: if (sessionId == _activeSessionId.value) _activeCwd.value else null
        return buildString {
            append(title ?: sessionId.take(12))
            if (!cwd.isNullOrBlank()) append(" · ").append(cwd)
        }
    }

    // ------------------------------------------------------------------------------------------
    // API key persistence & mask
    // ------------------------------------------------------------------------------------------

    fun refreshApiKeyMask() {
        scope.launch(Dispatchers.IO) { refreshApiKeyMaskInternal() }
    }

    fun refreshApiKeyMaskSync() {
        refreshApiKeyMaskInternal()
    }

    private fun refreshApiKeyMaskInternal() {
        val key = runCatching { settingsStore.getApiKey() }.getOrNull()
        _apiKeyMasked.value = key?.let { k ->
            val tail = k.takeLast(4)
            val head = if (k.startsWith("sk-")) "sk-" else k.take(2)
            "$head••••••••$tail"
        }
    }

    fun saveApiKey(
        key: String,
        autoStartRuntime: Boolean = true,
        onResult: (Boolean) -> Unit = {}
    ) {
        val job = scope.launch(Dispatchers.IO) {
            val saved = runCatching { settingsStore.setApiKey(key) }
            if (saved.isFailure) {
                val err = saved.exceptionOrNull()
                val msg = err?.message ?: err?.javaClass?.simpleName ?: "Unknown error"
                withContext(Dispatchers.Main) {
                    banner(t("API Key 保存失败：$msg", "Failed to save API key: $msg"))
                    onResult(false)
                }
                return@launch
            }
            runCatching { runtimeController.reloadSettings() }
            refreshApiKeyMaskInternal()
            withContext(Dispatchers.Main) {
                banner(if (key.isBlank()) t("API Key 已清除", "API key cleared") else t("API Key 已加密保存", "API key saved (encrypted)"))
                onResult(true)
                if (autoStartRuntime && key.isNotBlank() &&
                    settingsStore.current().onboardingCompleted &&
                    environmentCoordinator.isRootfsReady() &&
                    runtimeStatus.value.state == RuntimeLifecycleState.STOPPED
                ) {
                    startRuntimeAndAttach()
                }
            }
        }
        saveApiKeyJob = job
    }

    suspend fun awaitPendingApiKeySave() {
        saveApiKeyJob?.join()
    }

    // ------------------------------------------------------------------------------------------
    // Runtime events & status
    // ------------------------------------------------------------------------------------------

    private fun collectEnvironmentReattachRequests() {
        scope.launch {
            environmentCoordinator.runtimeReattachRequests.collect {
                startRuntimeAndAttach()
            }
        }
    }

    private fun syncActiveStreamingFlag() {
        val activeSid = _activeSessionId.value
        _streaming.value = fastChatActive || (activeSid != null && activeSid in streamingSessions)
    }

    private fun syncPendingDialogs() {
        val activeSid = _activeSessionId.value
        _pendingPermission.value = activeSid?.let { pendingPermissionsBySession[it]?.firstOrNull() }
        _pendingQuestion.value = activeSid?.let { pendingQuestionsBySession[it]?.firstOrNull() }
            ?: pendingQuestionsBySession[""]?.firstOrNull()
        val awaiting = LinkedHashSet<String>()
        for ((sid, q) in pendingPermissionsBySession) {
            if (sid.isNotEmpty() && q.isNotEmpty()) awaiting.add(sid)
        }
        for ((sid, q) in pendingQuestionsBySession) {
            if (sid.isNotEmpty() && q.isNotEmpty()) awaiting.add(sid)
        }
        _sessionsAwaitingInput.value = awaiting
    }

    private fun detachCurrentStreamBuffers() {
        streamFlushJob?.cancel()
        streamFlushJob = null
        streamFlushDirty = false
        streamFirstChunkEmitted = false
        currentStreamSessionId = null
        currentAssistantMsgId = null
        streamTextBuf.clear()
        streamThinkBuf.clear()
        streamTools.clear()
    }

    private fun clearPendingForSession(sessionId: String, cancelOnRuntime: Boolean) {
        val perms = pendingPermissionsBySession.remove(sessionId)
        val quests = pendingQuestionsBySession.remove(sessionId)
        syncPendingDialogs()
        if (cancelOnRuntime && (perms != null || quests != null)) {
            scope.launch {
                perms?.forEach { p ->
                    runCatching { runtimeController.replyPermission(p.requestId, "", cancelled = true) }
                }
                quests?.forEach { q ->
                    runCatching { runtimeController.replyQuestion(q.requestId, emptyList(), cancelled = true) }
                }
            }
        }
    }

    private fun clearInFlightRuntimeStateOnStop() {
        streamingSessions.clear()
        resumedMidStream.clear()
        if (_sessions.value.any { it.live }) {
            _sessions.value = _sessions.value.map { if (it.live) it.copy(live = false) else it }
        }
        finalizeStreamingMessage()
        pendingPermissionsBySession.clear()
        pendingQuestionsBySession.clear()
        syncActiveStreamingFlag()
        syncPendingDialogs()
    }

    private fun collectRuntimeStatus() {
        scope.launch {
            var lastState: RuntimeLifecycleState? = null
            runtimeController.status.collect { st ->
                val isUnusable = st.state == RuntimeLifecycleState.STOPPED ||
                    st.state == RuntimeLifecycleState.STOPPING ||
                    st.state == RuntimeLifecycleState.STARTING ||
                    st.state == RuntimeLifecycleState.ERROR
                val restartAdvanced = attachedRestartCount >= 0 && st.restartCount > attachedRestartCount

                if (isUnusable || restartAdvanced) {
                    if (_sessionAttached.value) {
                        _sessionAttached.value = false
                    }
                    if (st.state == RuntimeLifecycleState.STOPPED || st.state == RuntimeLifecycleState.ERROR) {
                        clearInFlightRuntimeStateOnStop()
                    }
                }

                val nowUsable = st.state == RuntimeLifecycleState.HEALTHY || st.state == RuntimeLifecycleState.DEGRADED
                val wasUnusable = lastState != null &&
                    lastState != RuntimeLifecycleState.HEALTHY &&
                    lastState != RuntimeLifecycleState.DEGRADED
                if (nowUsable && (wasUnusable || restartAdvanced) &&
                    !_sessionAttached.value &&
                    !_sessionBusy.value &&
                    !_activeSessionReadOnly.value &&
                    _activeSessionId.value != null
                ) {
                    reattachActiveSessionAfterRestart()
                }
                lastState = st.state
            }
        }
    }

    private fun reattachActiveSessionAfterRestart() {
        scope.launch {
            sessionOpMutex.withLock {
                val sid = _activeSessionId.value ?: return@withLock
                if (_sessionAttached.value || _activeSessionReadOnly.value || !isRuntimeUsable()) return@withLock
                _sessionBusy.value = true
                try {
                    val resumed = runCatching { resumeSessionInternal(sid, _activeCwd.value) }.isSuccess
                    if (!resumed) {
                        runCatching { createSessionInternal(_activeCwd.value) }
                            .onSuccess {
                                banner(
                                    t(
                                        "运行环境重启后无法恢复原对话，已为你新建对话",
                                        "Could not resume previous chat after restart; started a new chat"
                                    )
                                )
                            }
                            .onFailure { err ->
                                banner(
                                    t("重连对话失败：${err.message}", "Failed to reattach chat: ${err.message}"),
                                    BannerAction.RESTART_RUNTIME
                                )
                            }
                    }
                    refreshSessions()
                } finally {
                    _sessionBusy.value = false
                }
            }
        }
    }

    private fun collectRuntimeEvents() {
        scope.launch {
            runtimeController.events.collect { ev ->
                when (ev) {
                    is RuntimeCallbackEvent.SessionUpdateJson -> handleSessionUpdateJson(ev.sessionId, ev.jsonUpdate)
                    is RuntimeCallbackEvent.PermissionRequested -> {
                        val opts = runCatching {
                            json.parseToJsonElement(ev.optionsJson).jsonArray.map {
                                val o = it.jsonObject
                                PermissionOption(
                                    optionId = o["optionId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                                    name = o["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                                    kind = o["kind"]?.jsonPrimitive?.contentOrNull
                                )
                            }
                        }.getOrDefault(emptyList())
                        val item = PendingAcpPermissionUi(
                            requestId = ev.requestId,
                            sessionId = ev.sessionId,
                            toolCallId = ev.toolCallId,
                            title = ev.title,
                            options = opts
                        )
                        pendingPermissionsBySession.getOrPut(ev.sessionId) { ArrayDeque() }.addLast(item)
                        syncPendingDialogs()
                        if (ev.sessionId != _activeSessionId.value) {
                            val label = resolveSessionDisplayLabel(ev.sessionId)
                            banner(
                                if (label != null) t("后台对话「$label」正在等待操作确认", "Background chat \"$label\" is waiting for approval")
                                else t("另一个对话正在等待操作确认", "Another chat is waiting for approval")
                            )
                        }
                    }
                    is RuntimeCallbackEvent.QuestionRequested -> {
                        val q = runCatching {
                            json.decodeFromString<UserQuestionRequest>(ev.questionRequestJson)
                        }.getOrNull()
                        if (q != null) {
                            val key = q.sessionId.orEmpty()
                            pendingQuestionsBySession.getOrPut(key) { ArrayDeque() }.addLast(q)
                            syncPendingDialogs()
                            if (key.isNotEmpty() && key != _activeSessionId.value) {
                                val label = resolveSessionDisplayLabel(key)
                                banner(
                                    if (label != null) t("后台对话「$label」有待回答的问题", "Background chat \"$label\" has a question")
                                    else t("另一个对话有待回答的问题", "Another chat has a question waiting")
                                )
                            }
                        }
                    }
                    is RuntimeCallbackEvent.QuestionCancelled -> {
                        val it = pendingQuestionsBySession.entries.iterator()
                        while (it.hasNext()) {
                            val entry = it.next()
                            entry.value.removeAll { q -> q.requestId == ev.requestId }
                            if (entry.value.isEmpty()) it.remove()
                        }
                        syncPendingDialogs()
                    }
                    is RuntimeCallbackEvent.PromptCompleted -> {
                        streamingSessions.remove(ev.sessionId)
                        clearPendingForSession(ev.sessionId, cancelOnRuntime = false)
                        if (ev.sessionId == currentStreamSessionId) {
                            finalizeStreamingMessage()
                        }
                        syncActiveStreamingFlag()
                        val reloadHistory = resumedMidStream.remove(ev.sessionId) && ev.sessionId == _activeSessionId.value
                        if (ev.sessionId == _activeSessionId.value) {
                            var note: String? = null
                            var noteIsError = false
                            if (!ev.errorMessage.isNullOrBlank()) {
                                val msg = if (ev.errorMessage.startsWith("[CONTENT_REFUSED]")) {
                                    t(
                                        "内容安全拒绝：${ev.errorMessage.removePrefix("[CONTENT_REFUSED]").trim()}",
                                        "Content safety refusal: ${ev.errorMessage.removePrefix("[CONTENT_REFUSED]").trim()}"
                                    )
                                } else {
                                    t("本轮出错：${ev.errorMessage}", "Prompt error: ${ev.errorMessage}")
                                }
                                _interruptedTurnBanner.value = msg
                                note = msg
                                noteIsError = true
                            } else if (ev.stopReason == "cancelled") {
                                note = t("已停止生成", "Generation stopped")
                            }
                            if (reloadHistory) {
                                val sid = ev.sessionId
                                scope.launch {
                                    sessionOpMutex.withLock {
                                        if (sid == _activeSessionId.value && sid !in streamingSessions) {
                                            runCatching { loadSurfaceHistoryIntoChat(sid) }
                                            note?.let { appendSystemMessage(it, isError = noteIsError) }
                                        }
                                    }
                                }
                            } else {
                                note?.let { appendSystemMessage(it, isError = noteIsError) }
                            }
                        }
                        refreshSessions()
                    }
                    is RuntimeCallbackEvent.InterruptedTurnUnknown -> {
                        streamingSessions.remove(ev.sessionId)
                        _sessionAttached.value = false
                        pendingPermissionsBySession.clear()
                        pendingQuestionsBySession.clear()
                        if (ev.sessionId == currentStreamSessionId) {
                            finalizeStreamingMessage()
                        }
                        syncActiveStreamingFlag()
                        syncPendingDialogs()
                        if (ev.sessionId == _activeSessionId.value) {
                            val text = localizeInterruptedMessage(ev.message)
                            _interruptedTurnBanner.value = text
                            appendSystemMessage(text, isUnknownOutcome = true)
                        }
                    }
                    is RuntimeCallbackEvent.StderrLine -> {
                        val cur = _runtimeLogs.value
                        _runtimeLogs.value = (cur + ev.line).takeLast(200)
                    }
                }
            }
        }
    }

    private fun localizeInterruptedMessage(raw: String): String {
        if (!raw.startsWith("TOOL_OUTCOME_UNKNOWN")) return raw
        val reason = Regex("""\((.*)\)""").find(raw)?.groupValues?.getOrNull(1).orEmpty()
        return t(
            "运行环境在这一轮执行中途退出" + (if (reason.isNotBlank()) "（$reason）" else "") +
                "，正在执行的工具结果未知。请先检查工作目录，再决定是否重试。",
            raw.removePrefix("TOOL_OUTCOME_UNKNOWN:").trim()
        )
    }

    // ------------------------------------------------------------------------------------------
    // Runtime lifecycle
    // ------------------------------------------------------------------------------------------

    private fun ensurePrerequisites(): Boolean {
        if (!settingsStore.hasApiKey.value) {
            banner(t("请先设置 DeepSeek API Key", "Set your DeepSeek API key first"), BannerAction.OPEN_MODEL_SETTINGS)
            return false
        }
        if (environmentCoordinator.isInstallBusy()) {
            environmentCoordinator.showInstallingBanner()
            return false
        }
        if (!environmentCoordinator.isRootfsReady()) {
            banner(t("运行环境尚未准备好", "The runtime environment is not ready yet"), BannerAction.OPEN_ENVIRONMENT)
            return false
        }
        return true
    }

    fun startRuntimeAfterSetup() {
        scope.launch {
            awaitPendingApiKeySave()
            if (settingsStore.hasApiKey.value &&
                environmentCoordinator.isRootfsReady() &&
                !isRuntimeUsable() &&
                runtimeStatus.value.state != RuntimeLifecycleState.STARTING
            ) {
                startRuntimeAndAttach()
            }
        }
    }

    fun startRuntimeAndAttach() {
        if (!ensurePrerequisites()) return
        scope.launch {
            sessionOpMutex.withLock {
                if (isRuntimeUsable() && _sessionAttached.value && _activeSessionId.value != null && !_activeSessionReadOnly.value) {
                    return@withLock
                }
                _sessionBusy.value = true
                try {
                    runCatching {
                        runtimeController.startRuntime()
                        waitForHealthyRuntime()
                        refreshSessions()
                        val savedSid = settingsStore.current().selectedSessionId
                        val preserveFastChat = fastChatActive || fastChatHasLocalTurns
                        if (!savedSid.isNullOrBlank() && !preserveFastChat) {
                            val resumed = runCatching { resumeSessionInternal(savedSid, _activeCwd.value) }.isSuccess
                            if (!resumed) createSessionInternal(defaultCwd(), preserveVisibleMessages = false)
                        } else {
                            createSessionInternal(defaultCwd(), preserveVisibleMessages = preserveFastChat)
                        }
                    }.onFailure {
                        banner(t("运行环境启动失败：${it.message}", "Runtime startup failed: ${it.message}"), BannerAction.OPEN_LOGS)
                    }
                } finally {
                    _sessionBusy.value = false
                }
            }
        }
    }

    fun restartRuntime() {
        if (environmentCoordinator.isInstallBusy()) {
            environmentCoordinator.showInstallingBanner()
            return
        }
        scope.launch {
            sessionOpMutex.withLock {
                if (environmentCoordinator.isInstallBusy()) {
                    environmentCoordinator.showInstallingBanner()
                    return@withLock
                }
                _sessionBusy.value = true
                _sessionAttached.value = false
                banner(t("正在重启运行环境…", "Restarting runtime…"))
                try {
                    runCatching {
                        runtimeController.restartRuntime()
                        waitForHealthyRuntime()
                        val sid = _activeSessionId.value
                        var fellBackToNew = false
                        if (sid != null && !_activeSessionReadOnly.value) {
                            val resumeRes = runCatching { resumeSessionInternal(sid, _activeCwd.value) }
                            if (resumeRes.isFailure) {
                                createSessionInternal(_activeCwd.value)
                                fellBackToNew = true
                            }
                        } else if (sid == null) {
                            createSessionInternal(defaultCwd())
                        }
                        refreshSessions()
                        banner(
                            if (fellBackToNew) t("运行环境已重启，原对话无法恢复，已为你新建对话", "Runtime restarted; previous chat could not be resumed, started a new chat")
                            else t("运行环境已重启", "Runtime restarted")
                        )
                    }.onFailure {
                        banner(t("重启失败：${it.message}", "Restart failed: ${it.message}"), BannerAction.OPEN_LOGS)
                    }
                } finally {
                    _sessionBusy.value = false
                }
            }
        }
    }

    fun stopRuntime() {
        scope.launch {
            sessionOpMutex.withLock {
                runCatching { runtimeController.stopRuntime() }
                    .onFailure { banner(t("停止失败：${it.message}", "Stop failed: ${it.message}")) }
            }
        }
    }

    private suspend fun waitForHealthyRuntime() {
        val finalStatus = runCatching {
            withTimeout(35_000L) {
                runtimeStatus.first { st ->
                    st.state == RuntimeLifecycleState.HEALTHY ||
                        st.state == RuntimeLifecycleState.DEGRADED ||
                        st.state == RuntimeLifecycleState.ERROR
                }
            }
        }.getOrElse {
            error(t("等待 DSH 初始化超时", "Timed out waiting for DSH ACP initialization"))
        }
        if (finalStatus.state == RuntimeLifecycleState.ERROR) {
            error(finalStatus.detail)
        }
    }

    private fun defaultCwd(): String = settingsStore.current().defaultCwd.trim().ifEmpty { "/root" }

    // ------------------------------------------------------------------------------------------
    // Sessions
    // ------------------------------------------------------------------------------------------

    fun startNewChat(cwd: String? = null) {
        if (_sessionBusy.value) return
        val target = cwd?.trim()?.ifEmpty { null } ?: defaultCwd()
        val reusable = _activeSessionId.value != null &&
            _sessionAttached.value &&
            !_activeSessionReadOnly.value &&
            _messages.value.none { it.role == "user" } &&
            target == _activeCwd.value &&
            !_streaming.value
        if (reusable) return
        createNewSession(target)
    }

    fun createNewSession(cwd: String = defaultCwd()) {
        if (_sessionBusy.value) return
        if (!ensurePrerequisites()) return
        scope.launch {
            sessionOpMutex.withLock {
                _sessionBusy.value = true
                try {
                    runCatching {
                        if (!isRuntimeUsable()) {
                            runtimeController.startRuntime()
                            waitForHealthyRuntime()
                        }
                        createSessionInternal(cwd)
                        refreshSessions()
                    }.onFailure {
                        banner(t("新建对话失败：${it.message}", "New chat failed: ${it.message}"), BannerAction.OPEN_LOGS)
                    }
                } finally {
                    _sessionBusy.value = false
                }
            }
        }
    }

    private suspend fun createSessionInternal(cwd: String, preserveVisibleMessages: Boolean = false) {
        val res = runtimeController.newSession(cwd)
        val sid = res["sessionId"]?.jsonPrimitive?.contentOrNull.orEmpty()
        check(sid.isNotBlank()) { "Empty sessionId from session/new" }
        val cfg = runCatching {
            res["configOptions"]?.let { el ->
                json.decodeFromJsonElement(ListSerializer(AcpConfigOption.serializer()), el)
            }
        }.getOrNull().orEmpty()
        if (!preserveVisibleMessages) {
            fastChatHasLocalTurns = false
            detachCurrentStreamBuffers()
            _messages.value = emptyList()
            _usageUsed.value = 0L
            _usageSize.value = 0L
            lastSubmittedPromptText = null
        }
        _activeSessionId.value = sid
        _activeCwd.value = cwd
        _activeSessionReadOnly.value = false
        _sessionAttached.value = true
        attachedRestartCount = runtimeStatus.value.restartCount
        _interruptedTurnBanner.value = null
        _configOptions.value = cfg
        syncActiveStreamingFlag()
        syncPendingDialogs()
        settingsStore.update { it.copy(selectedSessionId = sid) }
        refreshSlashCommands(sid)
    }

    fun switchToSession(item: SessionSummaryItem) {
        if (item.id == _activeSessionId.value) return
        if (_sessionBusy.value) return
        scope.launch {
            sessionOpMutex.withLock {
                if (item.corrupt || item.readOnly) {
                    detachCurrentStreamBuffers()
                    _activeSessionId.value = item.id
                    _activeCwd.value = item.cwd ?: "/root"
                    _activeSessionReadOnly.value = true
                    _sessionAttached.value = false
                    _interruptedTurnBanner.value = null
                    _configOptions.value = emptyList()
                    _usageUsed.value = 0L
                    _usageSize.value = 0L
                    _slashCommands.value = emptyList()
                    _messages.value = listOf(
                        UiChatMessage(
                            id = "corrupt-${item.id}",
                            role = "system",
                            text = t(
                                "这个对话的记录文件无法解析（${item.error ?: item.formatStatus}）。仅提供只读查看，磁盘上的原文件保持不变。",
                                "This session's log could not be parsed (${item.error ?: item.formatStatus}). It is shown read-only; files on disk are left untouched."
                            ),
                            isError = true
                        )
                    )
                    syncActiveStreamingFlag()
                    syncPendingDialogs()
                    return@withLock
                }

                _sessionBusy.value = true
                try {
                    runCatching {
                        if (!isRuntimeUsable()) {
                            runtimeController.startRuntime()
                            waitForHealthyRuntime()
                        }
                        resumeSessionInternal(item.id, item.cwd ?: "/root")
                        if (!item.error.isNullOrBlank()) {
                            banner(t("对话警告：${item.error}", "Session warning: ${item.error}"))
                        }
                    }.onFailure {
                        banner(t("切换对话失败：${it.message}", "Failed to open session: ${it.message}"))
                    }
                } finally {
                    _sessionBusy.value = false
                }
            }
        }
    }

    private suspend fun resumeSessionInternal(sessionId: String, cwd: String) {
        val res = runtimeController.resumeSession(sessionId, cwd)
        val sid = res["sessionId"]?.jsonPrimitive?.contentOrNull ?: sessionId
        val cfg = runCatching {
            res["configOptions"]?.let { el ->
                json.decodeFromJsonElement(ListSerializer(AcpConfigOption.serializer()), el)
            }
        }.getOrNull().orEmpty()
        detachCurrentStreamBuffers()
        _activeSessionId.value = sid
        _activeCwd.value = cwd
        _activeSessionReadOnly.value = false
        _sessionAttached.value = true
        attachedRestartCount = runtimeStatus.value.restartCount
        _interruptedTurnBanner.value = null
        _configOptions.value = cfg
        _usageUsed.value = 0L
        _usageSize.value = 0L
        lastSubmittedPromptText = null
        settingsStore.update { it.copy(selectedSessionId = sid) }

        loadSurfaceHistoryIntoChat(sid)
        if (sid in streamingSessions && currentAssistantMsgId == null) {
            resumedMidStream.add(sid)
            val assistantId = "asst-${UUID.randomUUID()}"
            currentStreamSessionId = sid
            currentAssistantMsgId = assistantId
            appendMessage(UiChatMessage(assistantId, "assistant", isStreaming = true))
        }
        syncActiveStreamingFlag()
        syncPendingDialogs()
        refreshSlashCommands(sid)
    }

    private suspend fun loadSurfaceHistoryIntoChat(sessionId: String) {
        val surface = runCatching { runtimeController.readSessionSurfaceFromBridge(sessionId) }.getOrNull()
        if (surface == null) {
            _messages.value = listOf(
                UiChatMessage(
                    "hist-unavail",
                    "system",
                    t("已恢复对话，但暂时无法加载历史记录。", "Session resumed, but its history preview is unavailable.")
                )
            )
            return
        }
        val ok = surface["ok"]?.jsonPrimitive?.booleanOrNull ?: true
        if (!ok) {
            _activeSessionReadOnly.value = true
            _configOptions.value = emptyList()
            _usageUsed.value = 0L
            _usageSize.value = 0L
            _slashCommands.value = emptyList()
            _messages.value = listOf(
                UiChatMessage(
                    id = "hist-corrupt",
                    role = "system",
                    text = t(
                        "历史记录无法读取（${surface["message"]?.jsonPrimitive?.contentOrNull}），已切换为只读，磁盘文件未改动。",
                        "Unreadable session history (${surface["message"]?.jsonPrimitive?.contentOrNull}). Marked read-only without touching disk files."
                    ),
                    isError = true
                )
            )
            return
        }
        val eventsArr = runCatching { surface["events"]?.jsonArray }.getOrNull()
        if (eventsArr != null) {
            val folded = SurfaceHistoryFold.foldEvents(eventsArr)
            val seenIds = HashSet<String>(folded.size * 2)
            _messages.value = folded.mapIndexed { idx, turn ->
                val uniqueId = if (seenIds.add(turn.id)) turn.id else "${turn.id}-$idx"
                UiChatMessage(
                    id = uniqueId,
                    role = turn.role,
                    text = turn.text,
                    thinking = turn.thinking,
                    tools = turn.tools.map { tool ->
                        UiToolCall(
                            toolCallId = tool.id,
                            title = tool.name,
                            status = when {
                                tool.outcomeUnknown -> "unknown"
                                tool.isError -> "failed"
                                tool.result != null -> "completed"
                                else -> "pending"
                            },
                            inputSummary = tool.arguments,
                            outputSummary = tool.result.orEmpty(),
                            isError = tool.isError,
                            outcomeUnknown = tool.outcomeUnknown
                        )
                    },
                    isUnknownOutcomeBanner = turn.interruptedUnknown
                )
            }
            lastSubmittedPromptText = _messages.value.lastOrNull { it.role == "user" }?.text
        } else {
            _messages.value = emptyList()
        }
    }

    fun closeSession(sessionId: String) {
        scope.launch {
            sessionOpMutex.withLock { closeSessionLocked(sessionId) }
        }
    }

    private suspend fun closeSessionLocked(sessionId: String) {
        _sessionBusy.value = true
        try {
            runCatching {
                runtimeController.closeSession(sessionId)
                resumedMidStream.remove(sessionId)
                streamingSessions.remove(sessionId)
                clearPendingForSession(sessionId, cancelOnRuntime = true)
                if (_activeSessionId.value == sessionId) {
                    detachCurrentStreamBuffers()
                    _activeSessionId.value = null
                    _activeSessionReadOnly.value = false
                    _sessionAttached.value = false
                    _interruptedTurnBanner.value = null
                    _messages.value = emptyList()
                    _configOptions.value = emptyList()
                    _usageUsed.value = 0L
                    _usageSize.value = 0L
                    _slashCommands.value = emptyList()
                }
                syncActiveStreamingFlag()
                syncPendingDialogs()
                refreshSessions()
                banner(t("对话已关闭", "Session closed"))
            }.onFailure {
                banner(t("无法关闭对话：${it.message}", "Cannot close session: ${it.message}"))
            }
        } finally {
            _sessionBusy.value = false
        }
    }

    fun refreshSessions() {
        if (!isRuntimeUsable()) {
            _sessionsError.value = null
            return
        }
        val gen = ++sessionsRefreshGen
        scope.launch {
            _sessionsLoading.value = true
            _sessionsError.value = null
            val res = runCatching { runtimeController.listSessionsFromBridge() }
            if (gen != sessionsRefreshGen) return@launch
            res.onSuccess {
                _sessions.value = it.sortedByDescending { s -> s.createdAt }
            }.onFailure {
                _sessionsError.value = it.message ?: "Failed to query sessions"
            }
            _sessionsLoading.value = false
        }
    }

    private fun refreshSlashCommands(sessionId: String?) {
        scope.launch {
            runCatching {
                runtimeController.listCommandsFromBridge(sessionId)
            }.onSuccess {
                if (sessionId == null || sessionId == _activeSessionId.value) {
                    _slashCommands.value = it
                }
            }
        }
    }

    /**
     * Sets one ACP config option on the active chat. [onResult] runs on the main thread with the
     * complete option list after the change, or `null` when nothing was applied (failure, chat
     * switched meanwhile, or no attached writable chat).
     */
    fun setConfigOption(
        configId: String,
        value: String,
        onResult: (List<AcpConfigOption>?) -> Unit = {}
    ) {
        if (_activeSessionReadOnly.value || !_sessionAttached.value) {
            onResult(null)
            return
        }
        val sid = _activeSessionId.value ?: run {
            onResult(null)
            return
        }
        scope.launch {
            val result = runCatching {
                val raw = runtimeController.setConfigOption(sid, configId, value)
                if (sid != _activeSessionId.value) return@runCatching null
                val updated = runCatching {
                    json.decodeFromString<List<AcpConfigOption>>(raw)
                }.getOrNull()
                if (updated != null && updated.isNotEmpty()) {
                    _configOptions.value = updated
                } else {
                    _configOptions.value = _configOptions.value.map {
                        if (it.id == configId) it.copy(currentValue = value) else it
                    }
                }
                _configOptions.value
            }.onFailure {
                banner(t("切换失败：${it.message}", "Config update failed: ${it.message}"))
            }
            onResult(result.getOrNull())
        }
    }

    // ------------------------------------------------------------------------------------------
    // Prompting
    // ------------------------------------------------------------------------------------------

    fun sendPromptOrCommand(input: String, appendUserBubble: Boolean = true): Boolean {
        val trimmed = input.trim()
        if (trimmed.isEmpty() || _streaming.value || _activeSessionReadOnly.value) return false

        val acpReady = isRuntimeUsable() &&
            !_sessionBusy.value &&
            _sessionAttached.value &&
            !_activeSessionId.value.isNullOrBlank()
        if (!acpReady && !trimmed.startsWith("/") && settingsStore.hasApiKey.value && DshnNativeCore.isAvailable) {
            return sendFastChatPrompt(trimmed, appendUserBubble)
        }

        if (_sessionBusy.value) {
            banner(t("正在加载对话，请稍候再发送…", "The chat is still loading, please send again in a moment…"))
            return false
        }
        val sid = _activeSessionId.value
        if (sid.isNullOrBlank()) {
            if (ensurePrerequisites()) {
                banner(
                    if (runtimeStatus.value.state == RuntimeLifecycleState.STARTING || _sessionBusy.value) {
                        t("运行环境正在启动，请稍候…", "The runtime is still starting, please wait…")
                    } else {
                        t("还没有可用的对话，请先启动运行环境", "No active chat yet. Start the runtime first.")
                    }
                )
            }
            return false
        }
        if (!isRuntimeUsable()) {
            if (environmentCoordinator.isInstallBusy()) {
                environmentCoordinator.showInstallingBanner()
                return false
            }
            when (runtimeStatus.value.state) {
                RuntimeLifecycleState.STARTING ->
                    banner(t("运行环境正在启动，请稍候…", "The runtime is still starting, please wait…"))
                RuntimeLifecycleState.STOPPING ->
                    banner(t("运行环境正在停止，请稍后再启动", "The runtime is stopping; start it again in a moment"))
                else -> banner(
                    t("运行环境未运行，请先启动或重启运行环境", "Runtime is not running. Start or restart it first."),
                    BannerAction.RESTART_RUNTIME
                )
            }
            return false
        }
        if (!_sessionAttached.value) {
            reattachActiveSessionAfterRestart()
            banner(t("正在重新连接对话，请稍候再试…", "Reconnecting chat session, please try again shortly…"))
            return false
        }

        if (trimmed.startsWith("/")) {
            val cmdName = trimmed.removePrefix("/").substringBefore(' ').trim()
            if (_slashCommands.value.any { it.name == cmdName }) {
                scope.launch {
                    if (appendUserBubble) {
                        appendMessage(UiChatMessage("user-${UUID.randomUUID()}", "user", trimmed))
                    }
                    runCatching {
                        runtimeController.executeCommandFromBridge(sid, trimmed)
                    }.onSuccess { res ->
                        if (sid == _activeSessionId.value) {
                            val resultObj = runCatching { res["result"]?.jsonObject }.getOrNull()
                            val text = resultObj?.get("text")?.jsonPrimitive?.contentOrNull
                                ?: res.toString()
                            appendSystemMessage("/$cmdName · $text")
                        }
                    }.onFailure {
                        if (sid == _activeSessionId.value) {
                            appendSystemMessage(t("/$cmdName 执行失败：${it.message}", "/$cmdName failed: ${it.message}"), isError = true)
                        }
                    }
                }
                return true
            }
        }

        _interruptedTurnBanner.value = null
        lastSubmittedPromptText = trimmed
        if (appendUserBubble) {
            appendMessage(UiChatMessage("user-${UUID.randomUUID()}", "user", trimmed))
        }

        val assistantId = "asst-${UUID.randomUUID()}"
        streamingSessions.add(sid)
        currentStreamSessionId = sid
        currentAssistantMsgId = assistantId
        streamTextBuf.clear()
        streamThinkBuf.clear()
        streamTools.clear()
        streamFlushJob?.cancel()
        streamFlushJob = null
        streamFlushDirty = false
        streamFirstChunkEmitted = false
        syncActiveStreamingFlag()
        appendMessage(UiChatMessage(assistantId, "assistant", isStreaming = true))

        scope.launch {
            runCatching {
                check(sid == _activeSessionId.value && _sessionAttached.value) {
                    t("对话已关闭或切换，消息未发送", "The chat was closed or switched; message not sent")
                }
                runtimeController.sendPrompt(UUID.randomUUID().toString(), sid, trimmed)
            }.onFailure { err ->
                streamingSessions.remove(sid)
                if (currentStreamSessionId == sid) {
                    finalizeStreamingMessage()
                }
                syncActiveStreamingFlag()
                if (sid == _activeSessionId.value) {
                    val errText = t("发送失败：${err.message}", "Failed to send: ${err.message}")
                    _interruptedTurnBanner.value = errText
                    appendSystemMessage(errText, isError = true)
                }
            }
        }
        return true
    }

    fun retryLastPrompt() {
        val last = lastSubmittedPromptText
            ?: _messages.value.lastOrNull { it.role == "user" }?.text
            ?: return
        if (_streaming.value || _sessionBusy.value) return
        val before = _messages.value
        val lastUserIdx = before.indexOfLast { it.role == "user" }
        _interruptedTurnBanner.value = null
        if (lastUserIdx >= 0) {
            val head = before.subList(0, lastUserIdx + 1)
            val tail = before.subList(lastUserIdx + 1, before.size)
                .filter { it.role == "system" && it.isUnknownOutcomeBanner }
            _messages.value = head + tail
        }
        if (!sendPromptOrCommand(last, appendUserBubble = false)) {
            _messages.value = before
        }
    }

    private fun resolveDirectApiModel(settings: dev.dshn.app.data.AppSettings): String {
        val customBase = settings.deepseekBaseUrl?.trim().orEmpty()
        val rawModel = settings.selectedModel.trim()
        val isCustomHost = customBase.isNotEmpty() && !customBase.contains("api.deepseek.com", ignoreCase = true)
        if (isCustomHost && rawModel.isNotEmpty() && !rawModel.equals("deepseek-flash", ignoreCase = true)) {
            return rawModel
        }
        val wantReasoner = rawModel.contains("reasoner", ignoreCase = true) ||
            settings.reasoningEffort.equals("high", ignoreCase = true) ||
            settings.reasoningEffort.equals("max", ignoreCase = true)
        return if (wantReasoner) "deepseek-reasoner" else "deepseek-chat"
    }

    /**
     * Streams a turn directly via Rust `libdshn_core.so` (rustls + zero-copy SSE) so the user can
     * chat in ~0.4 s without waiting for Ubuntu/Node/DSH container cold start.
     */
    private fun sendFastChatPrompt(trimmed: String, appendUserBubble: Boolean): Boolean {
        _interruptedTurnBanner.value = null
        lastSubmittedPromptText = trimmed
        if (appendUserBubble) {
            appendMessage(UiChatMessage("user-${UUID.randomUUID()}", "user", trimmed))
        }

        val historySnapshot = _messages.value
            .filter { (it.role == "user" || it.role == "assistant") && it.text.isNotBlank() }
            .takeLast(16)

        val assistantId = "fast-asst-${UUID.randomUUID()}"
        fastChatActive = true
        fastChatHasLocalTurns = true
        currentStreamSessionId = null
        currentAssistantMsgId = assistantId
        streamTextBuf.clear()
        streamThinkBuf.clear()
        streamTools.clear()
        streamFlushJob?.cancel()
        streamFlushJob = null
        streamFlushDirty = false
        streamFirstChunkEmitted = false
        syncActiveStreamingFlag()
        appendMessage(UiChatMessage(assistantId, "assistant", isStreaming = true))

        val settings = settingsStore.current()
        val baseUrl = settings.deepseekBaseUrl?.trim()?.trimEnd('/')?.ifEmpty { null }
            ?: "https://api.deepseek.com"
        val endpointUrl = if (baseUrl.endsWith("/chat/completions")) baseUrl else "$baseUrl/chat/completions"
        val model = resolveDirectApiModel(settings)

        val payloadJson = buildJsonObject {
            put("model", model)
            put("stream", true)
            putJsonObject("stream_options") { put("include_usage", true) }
            putJsonArray("messages") {
                for (msg in historySnapshot) {
                    addJsonObject {
                        put("role", msg.role)
                        put("content", msg.text)
                    }
                }
            }
        }.toString()

        scope.launch(Dispatchers.IO) {
            val apiKey = runCatching { settingsStore.getApiKey() }.getOrNull().orEmpty()
            if (apiKey.isBlank()) {
                withContext(Dispatchers.Main.immediate) {
                    fastChatActive = false
                    finalizeStreamingMessage()
                    appendSystemMessage(t("未读取到 API Key", "API key unavailable"), isError = true)
                }
                return@launch
            }
            DshnNativeCore.runFastChatStream(
                endpointUrl = endpointUrl,
                apiKey = apiKey,
                requestBodyJson = payloadJson,
                callback = object : DshnNativeCore.FastChatCallback {
                    override fun onTextDelta(chunk: String) {
                        scope.launch(Dispatchers.Main.immediate) {
                            if (currentAssistantMsgId == assistantId) {
                                streamTextBuf.append(chunk)
                                scheduleStreamingAssistantSync()
                            }
                        }
                    }

                    override fun onReasoningDelta(chunk: String) {
                        scope.launch(Dispatchers.Main.immediate) {
                            if (currentAssistantMsgId == assistantId) {
                                streamThinkBuf.append(chunk)
                                scheduleStreamingAssistantSync()
                            }
                        }
                    }

                    override fun onUsage(promptTokens: Long, completionTokens: Long, totalTokens: Long) {
                        scope.launch(Dispatchers.Main.immediate) {
                            _usageUsed.value = totalTokens
                        }
                    }

                    override fun onDone(finishReason: String) {
                        scope.launch(Dispatchers.Main.immediate) {
                            fastChatActive = false
                            if (currentAssistantMsgId == assistantId) {
                                finalizeStreamingMessage()
                            } else {
                                syncActiveStreamingFlag()
                            }
                            if (finishReason == "cancelled") {
                                appendSystemMessage(t("已停止生成", "Generation stopped"))
                            }
                        }
                    }

                    override fun onError(message: String) {
                        scope.launch(Dispatchers.Main.immediate) {
                            fastChatActive = false
                            if (currentAssistantMsgId == assistantId) {
                                finalizeStreamingMessage()
                            } else {
                                syncActiveStreamingFlag()
                            }
                            val errText = t("极速直连出错：$message", "Rust fast-chat error: $message")
                            _interruptedTurnBanner.value = errText
                            appendSystemMessage(errText, isError = true)
                        }
                    }
                }
            )
        }
        return true
    }

    fun cancelActivePrompt() {
        if (fastChatActive) {
            DshnNativeCore.cancelFastChat()
            return
        }
        val sid = _activeSessionId.value ?: return
        if (!isRuntimeUsable()) {
            streamingSessions.remove(sid)
            if (currentStreamSessionId == sid) finalizeStreamingMessage()
            syncActiveStreamingFlag()
            return
        }
        scope.launch {
            runCatching { runtimeController.cancelPrompt(sid) }
        }
    }

    fun replyAcpPermission(optionId: String?, cancelled: Boolean) {
        val req = _pendingPermission.value ?: return
        val queue = pendingPermissionsBySession[req.sessionId]
        queue?.removeAll { it.requestId == req.requestId }
        if (queue != null && queue.isEmpty()) pendingPermissionsBySession.remove(req.sessionId)
        syncPendingDialogs()
        scope.launch {
            runtimeController.replyPermission(req.requestId, optionId.orEmpty(), cancelled)
        }
    }

    fun replyUserQuestion(answers: List<UserQuestionAnswer>, cancelled: Boolean) {
        val req = _pendingQuestion.value ?: return
        val key = req.sessionId.orEmpty()
        val queue = pendingQuestionsBySession[key]
        queue?.removeAll { it.requestId == req.requestId }
        if (queue != null && queue.isEmpty()) pendingQuestionsBySession.remove(key)
        syncPendingDialogs()
        scope.launch {
            runtimeController.replyQuestion(req.requestId, answers, cancelled)
        }
    }

    fun refreshLogsAndStatus() {
        scope.launch {
            _runtimeLogs.value = runtimeController.getRecentLogs(200)
        }
    }

    fun clearRuntimeLogs() {
        scope.launch {
            runtimeController.clearLogs()
            _runtimeLogs.value = emptyList()
        }
    }

    suspend fun getStatusSnapshot(): RuntimeStatusSnapshot? = runtimeController.getStatusSnapshot()

    suspend fun getRecentLogs(maxLines: Int = 100): List<String> = runtimeController.getRecentLogs(maxLines)

    private fun handleSessionUpdateJson(sessionId: String, jsonText: String) {
        val activeSid = _activeSessionId.value
        if (sessionId.isNotEmpty() && activeSid != null && sessionId != activeSid) {
            return
        }
        val u = runCatching { json.parseToJsonElement(jsonText).jsonObject }.getOrNull() ?: return
        val type = u["type"]?.jsonPrimitive?.contentOrNull
        if (type in setOf("agent_message_chunk", "agent_thought_chunk", "tool_call", "tool_call_update") &&
            currentAssistantMsgId == null && activeSid != null && activeSid in streamingSessions
        ) {
            val assistantId = "asst-${UUID.randomUUID()}"
            currentStreamSessionId = activeSid
            currentAssistantMsgId = assistantId
            streamTextBuf.clear()
            streamThinkBuf.clear()
            streamTools.clear()
            streamFlushJob?.cancel()
            streamFlushJob = null
            streamFlushDirty = false
            streamFirstChunkEmitted = false
            appendMessage(UiChatMessage(assistantId, "assistant", isStreaming = true))
        }
        when (type) {
            "agent_message_chunk" -> {
                streamTextBuf.append(u["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
                scheduleStreamingAssistantSync()
            }
            "agent_thought_chunk" -> {
                streamThinkBuf.append(u["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
                scheduleStreamingAssistantSync()
            }
            "tool_call" -> {
                val id = u["toolCallId"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val title = u["title"]?.jsonPrimitive?.contentOrNull ?: "tool"
                val status = u["status"]?.jsonPrimitive?.contentOrNull ?: "running"
                val rawIn = u["rawInput"]?.let { summarizeJson(it) }.orEmpty()
                val idx = streamTools.indexOfFirst { it.toolCallId == id }
                val tc = UiToolCall(id, title, status, inputSummary = rawIn)
                if (idx >= 0) streamTools[idx] = tc else streamTools.add(tc)
                flushStreamingAssistantMessageNow()
            }
            "tool_call_update" -> {
                val id = u["toolCallId"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val status = u["status"]?.jsonPrimitive?.contentOrNull ?: ""
                val extractedText = u["contentText"]?.jsonPrimitive?.contentOrNull
                val contentStr = extractedText?.take(2400)
                    ?: u["content"]?.let { summarizeJson(it) }.orEmpty()
                val unknown = contentStr.contains("TOOL_OUTCOME_UNKNOWN")
                val idx = streamTools.indexOfFirst { it.toolCallId == id }
                if (idx >= 0) {
                    val prev = streamTools[idx]
                    streamTools[idx] = prev.copy(
                        status = status.ifEmpty { prev.status },
                        outputSummary = if (contentStr.isNotEmpty()) contentStr else prev.outputSummary,
                        isError = status == "failed" || status == "error" || prev.isError,
                        outcomeUnknown = prev.outcomeUnknown || unknown
                    )
                } else {
                    streamTools.add(
                        UiToolCall(
                            toolCallId = id,
                            title = "tool",
                            status = status,
                            outputSummary = contentStr,
                            isError = status == "failed",
                            outcomeUnknown = unknown
                        )
                    )
                }
                flushStreamingAssistantMessageNow()
            }
            "usage_update" -> {
                _usageUsed.value = u["used"]?.jsonPrimitive?.longOrNull ?: _usageUsed.value
                _usageSize.value = u["size"]?.jsonPrimitive?.longOrNull ?: _usageSize.value
            }
            "config_option_update" -> {
                val cfg = runCatching {
                    u["configOptions"]?.let { el ->
                        json.decodeFromJsonElement(ListSerializer(AcpConfigOption.serializer()), el)
                    }
                }.getOrNull()
                if (cfg != null) _configOptions.value = cfg
            }
        }
    }

    private fun summarizeJson(elem: JsonElement): String {
        val s = elem.toString()
        return if (s.length <= 2400) s else s.take(2400) + "..."
    }

    /**
     * Emits the very first streaming chunk immediately (0 ms TTFT penalty) and coalesces
     * rapid subsequent token bursts on a 40 ms window to avoid O(N) String + List allocations per token.
     */
    private fun scheduleStreamingAssistantSync() {
        if (!streamFirstChunkEmitted) {
            streamFirstChunkEmitted = true
            flushStreamingAssistantMessageNow()
            return
        }
        streamFlushDirty = true
        if (streamFlushJob?.isActive == true) return
        streamFlushJob = scope.launch {
            delay(40)
            if (streamFlushDirty) {
                streamFlushDirty = false
                syncStreamingAssistantMessage()
            }
        }
    }

    private fun flushStreamingAssistantMessageNow() {
        streamFlushJob?.cancel()
        streamFlushJob = null
        streamFlushDirty = false
        syncStreamingAssistantMessage()
    }

    private fun syncStreamingAssistantMessage() {
        val msgId = currentAssistantMsgId ?: return
        val updated = UiChatMessage(
            id = msgId,
            role = "assistant",
            text = streamTextBuf.toString(),
            thinking = streamThinkBuf.toString(),
            tools = streamTools.toList(),
            isStreaming = true
        )
        _messages.value = _messages.value.map { if (it.id == msgId) updated else it }
    }

    private fun finalizeStreamingMessage() {
        streamFlushJob?.cancel()
        streamFlushJob = null
        if (streamFlushDirty && currentAssistantMsgId != null) {
            streamFlushDirty = false
            syncStreamingAssistantMessage()
        }
        streamFirstChunkEmitted = false
        val msgId = currentAssistantMsgId
        currentStreamSessionId = null
        currentAssistantMsgId = null
        streamTextBuf.clear()
        streamThinkBuf.clear()
        streamTools.clear()
        if (msgId != null) {
            _messages.value = _messages.value.mapNotNull {
                when {
                    it.id != msgId -> it
                    it.text.isEmpty() && it.thinking.isEmpty() && it.tools.isEmpty() -> null
                    else -> it.copy(isStreaming = false)
                }
            }
        }
        syncActiveStreamingFlag()
    }

    private fun appendMessage(msg: UiChatMessage) {
        _messages.value = _messages.value + msg
    }

    private fun appendSystemMessage(text: String, isUnknownOutcome: Boolean = false, isError: Boolean = false) {
        appendMessage(
            UiChatMessage(
                id = "sys-${UUID.randomUUID()}",
                role = "system",
                text = text,
                isUnknownOutcomeBanner = isUnknownOutcome,
                isError = isError || isUnknownOutcome
            )
        )
    }
}
