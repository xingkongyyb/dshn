package dev.dshn.app.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.dshn.app.acp.AcpConfigOption
import dev.dshn.app.acp.PermissionOption
import dev.dshn.app.acp.SurfaceHistoryFold
import dev.dshn.app.bridge.AdbIdentitySummary
import dev.dshn.app.bridge.ConfirmationBroker
import dev.dshn.app.bridge.DeviceBridgeMcpServer
import dev.dshn.app.bridge.DiscoveredAdbEndpoint
import dev.dshn.app.bridge.DshnAccessibilityService
import dev.dshn.app.bridge.PendingBridgeConfirmation
import dev.dshn.app.bridge.WirelessAdbClient
import dev.dshn.app.data.AdbTransportMode
import dev.dshn.app.data.AppLocaleMode
import dev.dshn.app.data.AppSettings
import dev.dshn.app.data.AppThemeMode
import dev.dshn.app.data.PermissionTier
import dev.dshn.app.data.SettingsStore
import dev.dshn.app.runtime.ContainerLauncher
import dev.dshn.app.runtime.EnvManager
import dev.dshn.app.runtime.EnvManifest
import dev.dshn.app.runtime.EnvSnapshotInfo
import dev.dshn.app.runtime.RuntimeCallbackEvent
import dev.dshn.app.runtime.RuntimeController
import dev.dshn.app.runtime.RuntimeLifecycleState
import dev.dshn.app.runtime.RuntimeStatusSnapshot
import dev.dshn.app.runtime.RuntimeUiStatus
import dev.dshn.app.runtime.SelfTestReport
import dev.dshn.app.runtime.SessionSummaryItem
import dev.dshn.app.runtime.SlashCommandDescriptor
import dev.dshn.app.runtime.TerminalPtySession
import dev.dshn.app.runtime.UserQuestionAnswer
import dev.dshn.app.runtime.UserQuestionRequest
import dev.dshn.app.security.AuditEntry
import dev.dshn.app.security.AuditLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.util.UUID

enum class MainTab { CHAT, SESSIONS, TERMINAL, LAB, SETTINGS }

data class AdbProbeUiState(
    val probing: Boolean = false,
    val authorized: Boolean = false,
    val endpoint: String? = null,
    val summary: String = "",
    val isError: Boolean = false
)

data class LabTrialResult(
    val tool: String,
    val decisionLabel: String,
    val isError: Boolean,
    val durationMs: Long,
    val outputText: String,
    val timestampMs: Long
)

data class UiToolCall(
    val toolCallId: String,
    val title: String,
    val status: String,
    val inputSummary: String = "",
    val outputSummary: String = "",
    val isError: Boolean = false,
    val outcomeUnknown: Boolean = false
)

data class UiChatMessage(
    val id: String,
    val role: String, // "user", "assistant", "system"
    val text: String = "",
    val thinking: String = "",
    val tools: List<UiToolCall> = emptyList(),
    val isStreaming: Boolean = false,
    val isUnknownOutcomeBanner: Boolean = false
)

data class PendingAcpPermissionUi(
    val requestId: String,
    val sessionId: String,
    val toolCallId: String?,
    val title: String?,
    val options: List<PermissionOption>
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; isLenient = true }
    private val launcher = ContainerLauncher(app)
    val settingsStore = SettingsStore(app)
    val auditLogger = AuditLogger(File(launcher.rootFilesDir, "security"))
    val envManager = EnvManager(app, launcher)
    val adbClient = WirelessAdbClient(app)
    val confirmationBroker = ConfirmationBroker(app)
    val deviceBridgeServer = DeviceBridgeMcpServer(
        context = app,
        scope = viewModelScope,
        settingsStore = settingsStore,
        auditLogger = auditLogger,
        confirmationBroker = confirmationBroker,
        adbClient = adbClient
    )
    private val runtimeController = RuntimeController(app, viewModelScope)
    private val terminalPty = TerminalPtySession(launcher, viewModelScope)
    private val terminalBuffer = AnsiTerminalBuffer()

    val settings: StateFlow<AppSettings> = settingsStore.settings
    val hasApiKey: StateFlow<Boolean> = settingsStore.hasApiKey
    val runtimeStatus: StateFlow<RuntimeUiStatus> = runtimeController.status
    val accessibilityConnected: StateFlow<Boolean> = DshnAccessibilityService.connected
    val discoveredAdbEndpoints: StateFlow<List<DiscoveredAdbEndpoint>> = adbClient.endpoints
    val adbScanning: StateFlow<Boolean> = adbClient.scanning
    val pendingBridgeConfirm: StateFlow<PendingBridgeConfirmation?> = confirmationBroker.pending

    private val _selectedTab = MutableStateFlow(MainTab.CHAT)
    val selectedTab: StateFlow<MainTab> = _selectedTab.asStateFlow()

    // Chat state
    private val _messages = MutableStateFlow<List<UiChatMessage>>(emptyList())
    val messages: StateFlow<List<UiChatMessage>> = _messages.asStateFlow()

    private val _activeSessionId = MutableStateFlow<String?>(null)
    val activeSessionId: StateFlow<String?> = _activeSessionId.asStateFlow()

    private val _activeSessionReadOnly = MutableStateFlow(false)
    val activeSessionReadOnly: StateFlow<Boolean> = _activeSessionReadOnly.asStateFlow()

    private val _activeCwd = MutableStateFlow("/root")
    val activeCwd: StateFlow<String> = _activeCwd.asStateFlow()

    private val _streaming = MutableStateFlow(false)
    val streaming: StateFlow<Boolean> = _streaming.asStateFlow()

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

    private val _interruptedTurnBanner = MutableStateFlow<String?>(null)
    val interruptedTurnBanner: StateFlow<String?> = _interruptedTurnBanner.asStateFlow()

    private var lastSubmittedPromptText: String? = null
    private var currentAssistantMsgId: String? = null
    private val streamTextBuf = StringBuilder()
    private val streamThinkBuf = StringBuilder()
    private val streamTools = mutableListOf<UiToolCall>()

    // Sessions state
    private val _sessions = MutableStateFlow<List<SessionSummaryItem>>(emptyList())
    val sessions: StateFlow<List<SessionSummaryItem>> = _sessions.asStateFlow()

    private val _sessionsLoading = MutableStateFlow(false)
    val sessionsLoading: StateFlow<Boolean> = _sessionsLoading.asStateFlow()

    private val _sessionsError = MutableStateFlow<String?>(null)
    val sessionsError: StateFlow<String?> = _sessionsError.asStateFlow()

    // Terminal state
    private val _terminalOutput = MutableStateFlow("")
    val terminalOutput: StateFlow<String> = _terminalOutput.asStateFlow()
    val terminalRunning: StateFlow<Boolean> = terminalPty.running

    // Settings / Env / Diagnostics state
    private val _envManifest = MutableStateFlow(envManager.loadManifest())
    val envManifest: StateFlow<EnvManifest> = _envManifest.asStateFlow()

    private val _snapshots = MutableStateFlow(envManager.listSnapshots())
    val snapshots: StateFlow<List<EnvSnapshotInfo>> = _snapshots.asStateFlow()

    private val _selfTestReport = MutableStateFlow<SelfTestReport?>(null)
    val selfTestReport: StateFlow<SelfTestReport?> = _selfTestReport.asStateFlow()

    private val _selfTestRunning = MutableStateFlow(false)
    val selfTestRunning: StateFlow<Boolean> = _selfTestRunning.asStateFlow()

    private val _runtimeLogs = MutableStateFlow<List<String>>(emptyList())
    val runtimeLogs: StateFlow<List<String>> = _runtimeLogs.asStateFlow()

    private val _auditEntries = MutableStateFlow<List<AuditEntry>>(emptyList())
    val auditEntries: StateFlow<List<AuditEntry>> = _auditEntries.asStateFlow()

    private val _adbIdentity = MutableStateFlow<AdbIdentitySummary?>(null)
    val adbIdentity: StateFlow<AdbIdentitySummary?> = _adbIdentity.asStateFlow()

    private val _adbProbeState = MutableStateFlow(AdbProbeUiState())
    val adbProbeState: StateFlow<AdbProbeUiState> = _adbProbeState.asStateFlow()

    private val _labRunning = MutableStateFlow(false)
    val labRunning: StateFlow<Boolean> = _labRunning.asStateFlow()

    private val _labLastResult = MutableStateFlow<LabTrialResult?>(null)
    val labLastResult: StateFlow<LabTrialResult?> = _labLastResult.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    init {
        deviceBridgeServer.start()
        runtimeController.bind()
        collectRuntimeEvents()
        collectTerminalOutput()

        viewModelScope.launch(Dispatchers.IO) {
            _envManifest.value = envManager.ensureRuntimeAssets()
            refreshAuditEntries()
            refreshAdbIdentity()
            if (settingsStore.hasApiKey.value && envManager.isRootfsReady()) {
                startRuntimeAndAttach()
            }
        }
    }

    fun selectTab(tab: MainTab) {
        _selectedTab.value = tab
        when (tab) {
            MainTab.SESSIONS -> refreshSessions()
            MainTab.LAB -> {
                refreshAuditEntries()
                refreshAdbIdentity()
            }
            MainTab.SETTINGS -> {
                refreshLogsAndStatus()
                refreshAuditEntries()
                refreshSnapshots()
            }
            else -> {}
        }
    }

    fun dismissBanner() {
        _statusBanner.value = null
    }

    private fun collectRuntimeEvents() {
        viewModelScope.launch {
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
                        _pendingPermission.value = PendingAcpPermissionUi(
                            requestId = ev.requestId,
                            sessionId = ev.sessionId,
                            toolCallId = ev.toolCallId,
                            title = ev.title,
                            options = opts
                        )
                    }
                    is RuntimeCallbackEvent.QuestionRequested -> {
                        val q = runCatching {
                            json.decodeFromString<UserQuestionRequest>(ev.questionRequestJson)
                        }.getOrNull()
                        if (q != null) _pendingQuestion.value = q
                    }
                    is RuntimeCallbackEvent.QuestionCancelled -> {
                        if (_pendingQuestion.value?.requestId == ev.requestId) {
                            _pendingQuestion.value = null
                        }
                    }
                    is RuntimeCallbackEvent.PromptCompleted -> {
                        if (ev.sessionId == _activeSessionId.value) {
                            finalizeStreamingMessage()
                            if (!ev.errorMessage.isNullOrBlank()) {
                                appendSystemMessage("Prompt error: ${ev.errorMessage}")
                            }
                        }
                    }
                    is RuntimeCallbackEvent.InterruptedTurnUnknown -> {
                        if (ev.sessionId == _activeSessionId.value) {
                            finalizeStreamingMessage()
                            _interruptedTurnBanner.value = ev.message
                            appendSystemMessage(ev.message, isUnknownOutcome = true)
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

    private fun collectTerminalOutput() {
        viewModelScope.launch {
            terminalPty.output.collect { chunk ->
                terminalBuffer.append(chunk)
                _terminalOutput.value = terminalBuffer.renderText()
            }
        }
    }

    fun startRuntimeAndAttach() {
        viewModelScope.launch {
            runCatching {
                runtimeController.startRuntime()
                waitForHealthyRuntime()
                val savedSid = settingsStore.current().selectedSessionId
                if (!savedSid.isNullOrBlank()) {
                    val resumed = runCatching { resumeSessionInternal(savedSid, _activeCwd.value) }.isSuccess
                    if (!resumed) createSessionInternal(_activeCwd.value)
                } else {
                    createSessionInternal(_activeCwd.value)
                }
            }.onFailure {
                _statusBanner.value = "Runtime startup issue: ${it.message}"
            }
        }
    }

    fun restartRuntime() {
        viewModelScope.launch {
            _statusBanner.value = "Restarting DSH runtime..."
            runCatching {
                runtimeController.restartRuntime()
                waitForHealthyRuntime()
                _activeSessionId.value?.let { sid ->
                    runCatching { resumeSessionInternal(sid, _activeCwd.value) }
                }
                _statusBanner.value = "Runtime restarted."
            }.onFailure {
                _statusBanner.value = "Restart failed: ${it.message}"
            }
        }
    }

    fun stopRuntime() {
        viewModelScope.launch {
            runtimeController.stopRuntime()
        }
    }

    private suspend fun waitForHealthyRuntime() {
        val deadline = System.currentTimeMillis() + 35_000L
        while (System.currentTimeMillis() < deadline) {
            val st = runtimeStatus.value.state
            if (st == RuntimeLifecycleState.HEALTHY || st == RuntimeLifecycleState.DEGRADED) return
            if (st == RuntimeLifecycleState.ERROR) error(runtimeStatus.value.detail)
            kotlinx.coroutines.delay(250)
        }
        error("Timed out waiting for DSH ACP initialization")
    }

    fun createNewSession(cwd: String = "/root") {
        viewModelScope.launch {
            runCatching {
                if (runtimeStatus.value.state != RuntimeLifecycleState.HEALTHY &&
                    runtimeStatus.value.state != RuntimeLifecycleState.DEGRADED
                ) {
                    runtimeController.startRuntime()
                    waitForHealthyRuntime()
                }
                createSessionInternal(cwd)
                _selectedTab.value = MainTab.CHAT
            }.onFailure {
                _statusBanner.value = "New session failed: ${it.message}"
            }
        }
    }

    private suspend fun createSessionInternal(cwd: String) {
        val res = runtimeController.newSession(cwd)
        val sid = res["sessionId"]?.jsonPrimitive?.contentOrNull.orEmpty()
        check(sid.isNotBlank()) { "Empty sessionId from session/new" }
        val cfg = runCatching {
            res["configOptions"]?.let { el ->
                json.decodeFromJsonElement(
                    kotlinx.serialization.builtins.ListSerializer(AcpConfigOption.serializer()),
                    el
                )
            }
        }.getOrNull().orEmpty()
        _activeSessionId.value = sid
        _activeCwd.value = cwd
        _activeSessionReadOnly.value = false
        _configOptions.value = cfg
        _messages.value = emptyList()
        _usageUsed.value = 0L
        _usageSize.value = 0L
        settingsStore.update { it.copy(selectedSessionId = sid) }
        refreshSlashCommands(sid)
    }

    fun switchToSession(item: SessionSummaryItem) {
        viewModelScope.launch {
            if (item.corrupt || item.readOnly) {
                // Corrupt file: load preview only cleanly and mark read-only without touching files
                _activeSessionId.value = item.id
                _activeCwd.value = item.cwd ?: "/root"
                _activeSessionReadOnly.value = true
                _messages.value = listOf(
                    UiChatMessage(
                        id = "corrupt-${item.id}",
                        role = "system",
                        text = "Session ${item.id} has unreadable/corrupt log format (${item.error ?: item.formatStatus}). Preserved strictly read-only without moving or altering disk files."
                    )
                )
                _selectedTab.value = MainTab.CHAT
                return@launch
            }

            runCatching {
                val cwd = item.cwd ?: "/root"
                resumeSessionInternal(item.id, cwd)
                _selectedTab.value = MainTab.CHAT
            }.onFailure {
                _statusBanner.value = "Failed to switch session: ${it.message}"
            }
        }
    }

    private suspend fun resumeSessionInternal(sessionId: String, cwd: String) {
        val res = runtimeController.resumeSession(sessionId, cwd)
        val sid = res["sessionId"]?.jsonPrimitive?.contentOrNull ?: sessionId
        val cfg = runCatching {
            res["configOptions"]?.let { el ->
                json.decodeFromJsonElement(
                    kotlinx.serialization.builtins.ListSerializer(AcpConfigOption.serializer()),
                    el
                )
            }
        }.getOrNull().orEmpty()
        _activeSessionId.value = sid
        _activeCwd.value = cwd
        _activeSessionReadOnly.value = false
        _configOptions.value = cfg
        settingsStore.update { it.copy(selectedSessionId = sid) }

        // Replay persisted surface history from bridge UDS
        loadSurfaceHistoryIntoChat(sid)
        refreshSlashCommands(sid)
    }

    private suspend fun loadSurfaceHistoryIntoChat(sessionId: String) {
        val surface = runCatching { runtimeController.readSessionSurfaceFromBridge(sessionId) }.getOrNull()
        if (surface == null) {
            _messages.value = listOf(
                UiChatMessage("hist-unavail", "system", "Resumed session $sessionId (history preview unavailable).")
            )
            return
        }
        val ok = surface["ok"]?.jsonPrimitive?.booleanOrNull ?: true
        if (!ok) {
            _activeSessionReadOnly.value = true
            _messages.value = listOf(
                UiChatMessage(
                    id = "hist-corrupt",
                    role = "system",
                    text = "Unreadable session history (${surface["message"]?.jsonPrimitive?.contentOrNull}). Marked read-only without touching disk files."
                )
            )
            return
        }
        val eventsArr = runCatching { surface["events"]?.jsonArray }.getOrNull()
        if (eventsArr != null) {
            val folded = SurfaceHistoryFold.foldEvents(eventsArr)
            _messages.value = folded.map { turn ->
                UiChatMessage(
                    id = turn.id,
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
        }
    }

    fun closeSession(sessionId: String) {
        viewModelScope.launch {
            runCatching {
                runtimeController.closeSession(sessionId)
                if (_activeSessionId.value == sessionId) {
                    _activeSessionId.value = null
                    _messages.value = emptyList()
                }
                refreshSessions()
            }.onFailure {
                _statusBanner.value = "Cannot close session: ${it.message}"
            }
        }
    }

    fun refreshSessions() {
        viewModelScope.launch {
            _sessionsLoading.value = true
            _sessionsError.value = null
            runCatching {
                runtimeController.listSessionsFromBridge()
            }.onSuccess {
                _sessions.value = it
            }.onFailure {
                _sessionsError.value = it.message ?: "Failed to query sessions"
            }
            _sessionsLoading.value = false
        }
    }

    private fun refreshSlashCommands(sessionId: String?) {
        viewModelScope.launch {
            runCatching {
                runtimeController.listCommandsFromBridge(sessionId)
            }.onSuccess {
                _slashCommands.value = it
            }
        }
    }

    fun setConfigOption(configId: String, value: String) {
        val sid = _activeSessionId.value ?: return
        viewModelScope.launch {
            runCatching {
                val raw = runtimeController.setConfigOption(sid, configId, value)
                val updated = runCatching {
                    json.decodeFromString<List<AcpConfigOption>>(raw)
                }.getOrNull()
                if (updated != null && updated.isNotEmpty()) {
                    _configOptions.value = updated
                }
            }.onFailure {
                _statusBanner.value = "Config update failed: ${it.message}"
            }
        }
    }

    fun sendPromptOrCommand(input: String) {
        val trimmed = input.trim()
        if (trimmed.isEmpty() || _streaming.value || _activeSessionReadOnly.value) return
        val sid = _activeSessionId.value
        if (sid.isNullOrBlank()) {
            _statusBanner.value = "No active session. Create a session or configure API key."
            return
        }

        // Check registered slash command execution
        if (trimmed.startsWith("/")) {
            val cmdName = trimmed.removePrefix("/").substringBefore(' ').trim()
            if (_slashCommands.value.any { it.name == cmdName }) {
                viewModelScope.launch {
                    appendMessage(UiChatMessage("user-${UUID.randomUUID()}", "user", trimmed))
                    runCatching {
                        runtimeController.executeCommandFromBridge(sid, trimmed)
                    }.onSuccess { res ->
                        val resultObj = runCatching { res["result"]?.jsonObject }.getOrNull()
                        val text = resultObj?.get("text")?.jsonPrimitive?.contentOrNull
                            ?: res.toString()
                        appendSystemMessage("/$cmdName: $text")
                    }.onFailure {
                        appendSystemMessage("/$cmdName failed: ${it.message}")
                    }
                }
                return
            }
        }

        _interruptedTurnBanner.value = null
        lastSubmittedPromptText = trimmed
        appendMessage(UiChatMessage("user-${UUID.randomUUID()}", "user", trimmed))

        val assistantId = "asst-${UUID.randomUUID()}"
        currentAssistantMsgId = assistantId
        streamTextBuf.clear()
        streamThinkBuf.clear()
        streamTools.clear()
        _streaming.value = true
        appendMessage(UiChatMessage(assistantId, "assistant", isStreaming = true))

        viewModelScope.launch {
            runCatching {
                runtimeController.sendPrompt(UUID.randomUUID().toString(), sid, trimmed)
            }.onFailure {
                finalizeStreamingMessage()
                appendSystemMessage("Failed to dispatch prompt: ${it.message}")
            }
        }
    }

    fun retryLastPrompt() {
        val last = lastSubmittedPromptText ?: return
        _interruptedTurnBanner.value = null
        sendPromptOrCommand(last)
    }

    fun cancelActivePrompt() {
        val sid = _activeSessionId.value ?: return
        viewModelScope.launch {
            runtimeController.cancelPrompt(sid)
        }
    }

    fun replyAcpPermission(optionId: String?, cancelled: Boolean) {
        val req = _pendingPermission.value ?: return
        _pendingPermission.value = null
        viewModelScope.launch {
            runtimeController.replyPermission(req.requestId, optionId.orEmpty(), cancelled)
        }
    }

    fun replyUserQuestion(answers: List<UserQuestionAnswer>, cancelled: Boolean) {
        val req = _pendingQuestion.value ?: return
        _pendingQuestion.value = null
        viewModelScope.launch {
            runtimeController.replyQuestion(req.requestId, answers, cancelled)
        }
    }

    fun respondBridgeConfirmation(allowOnce: Boolean) {
        val req = confirmationBroker.pending.value ?: return
        confirmationBroker.respond(req.id, allowOnce)
    }

    private fun handleSessionUpdateJson(sessionId: String, jsonText: String) {
        if (sessionId.isNotEmpty() && _activeSessionId.value != null && sessionId != _activeSessionId.value) {
            return
        }
        val u = runCatching { json.parseToJsonElement(jsonText).jsonObject }.getOrNull() ?: return
        when (u["type"]?.jsonPrimitive?.contentOrNull) {
            "agent_message_chunk" -> {
                streamTextBuf.append(u["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
                syncStreamingAssistantMessage()
            }
            "agent_thought_chunk" -> {
                streamThinkBuf.append(u["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
                syncStreamingAssistantMessage()
            }
            "tool_call" -> {
                val id = u["toolCallId"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val title = u["title"]?.jsonPrimitive?.contentOrNull ?: "tool"
                val status = u["status"]?.jsonPrimitive?.contentOrNull ?: "running"
                val rawIn = u["rawInput"]?.let { summarizeJson(it) }.orEmpty()
                val idx = streamTools.indexOfFirst { it.toolCallId == id }
                val tc = UiToolCall(id, title, status, inputSummary = rawIn)
                if (idx >= 0) streamTools[idx] = tc else streamTools.add(tc)
                syncStreamingAssistantMessage()
            }
            "tool_call_update" -> {
                val id = u["toolCallId"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val status = u["status"]?.jsonPrimitive?.contentOrNull ?: ""
                val contentStr = u["content"]?.let { summarizeJson(it) }.orEmpty()
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
                syncStreamingAssistantMessage()
            }
            "usage_update" -> {
                _usageUsed.value = u["used"]?.jsonPrimitive?.longOrNull ?: _usageUsed.value
                _usageSize.value = u["size"]?.jsonPrimitive?.longOrNull ?: _usageSize.value
            }
            "config_option_update" -> {
                val cfg = runCatching {
                    u["configOptions"]?.let { el ->
                        json.decodeFromJsonElement(
                            kotlinx.serialization.builtins.ListSerializer(AcpConfigOption.serializer()),
                            el
                        )
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
        val msgId = currentAssistantMsgId
        currentAssistantMsgId = null
        _streaming.value = false
        if (msgId != null) {
            _messages.value = _messages.value.map {
                if (it.id == msgId) it.copy(isStreaming = false) else it
            }
        }
    }

    private fun appendMessage(msg: UiChatMessage) {
        _messages.value = _messages.value + msg
    }

    private fun appendSystemMessage(text: String, isUnknownOutcome: Boolean = false) {
        appendMessage(
            UiChatMessage(
                id = "sys-${UUID.randomUUID()}",
                role = "system",
                text = text,
                isUnknownOutcomeBanner = isUnknownOutcome
            )
        )
    }

    // Terminal actions
    fun startTerminal(cwd: String = _activeCwd.value) {
        terminalPty.start(cols = 80, rows = 26, cwd = cwd)
    }

    fun sendTerminalInput(text: String) {
        terminalPty.writeInput(text)
    }

    fun clearTerminal() {
        terminalBuffer.clear()
        _terminalOutput.value = ""
    }

    // Settings & Env actions
    fun saveApiKey(key: String) {
        viewModelScope.launch(Dispatchers.IO) {
            settingsStore.setApiKey(key)
            runtimeController.reloadSettings()
            _statusBanner.value = if (key.isBlank()) "API key cleared." else "API key saved encrypted."
            if (key.isNotBlank() && runtimeStatus.value.state == RuntimeLifecycleState.STOPPED) {
                startRuntimeAndAttach()
            }
        }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        settingsStore.update(transform)
    }

    fun setThemeMode(mode: AppThemeMode) {
        updateSettings { it.copy(themeMode = mode) }
    }

    fun toggleLightDarkTheme(currentlyDark: Boolean) {
        setThemeMode(if (currentlyDark) AppThemeMode.LIGHT else AppThemeMode.DARK)
    }

    fun setLocaleMode(mode: AppLocaleMode) {
        updateSettings { it.copy(localeMode = mode) }
    }

    fun setPermissionTier(tier: PermissionTier) {
        updateSettings { it.copy(permissionMode = tier) }
        _statusBanner.value = "Permission tier set to ${tier.displayName}. Restart runtime to apply to ACP."
    }

    fun toggleAdbDiscovery() {
        if (adbClient.scanning.value) adbClient.stopDiscovery() else adbClient.startDiscovery()
    }

    fun refreshAdbIdentity() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { adbClient.getIdentitySummary() }
                .onSuccess { _adbIdentity.value = it }
        }
    }

    fun setAdbTransportMode(mode: AdbTransportMode) {
        updateSettings { it.copy(adbTransportMode = mode) }
    }

    fun enableAdbTrialPreset() {
        updateSettings {
            it.copy(
                bridgeEnabled = true,
                allowAdbBridge = true,
                requirePerCallConfirmation = true,
                allowAccessibilityRead = false,
                allowAccessibilityControl = false
            )
        }
        _statusBanner.value = "ADB trial gates enabled (per-call confirmation ON, Accessibility OFF)."
    }

    fun lockAllDeviceBridgeSwitches() {
        updateSettings {
            it.copy(
                bridgeEnabled = false,
                allowAccessibilityRead = false,
                allowAccessibilityControl = false,
                allowAdbBridge = false,
                requirePerCallConfirmation = true
            )
        }
        _statusBanner.value = "All Device Bridge switches locked OFF."
    }

    fun copyToClipboard(label: String, text: String) {
        val cm = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        _statusBanner.value = "Copied $label to clipboard."
    }

    fun testAdbConnection(
        host: String,
        port: Int,
        mode: AdbTransportMode = settingsStore.current().adbTransportMode
    ) {
        val trimmedHost = host.trim()
        if (trimmedHost.isEmpty() || port !in 1..65535) {
            _adbProbeState.value = AdbProbeUiState(
                probing = false,
                authorized = false,
                endpoint = "$host:$port",
                summary = "Invalid endpoint host:port",
                isError = true
            )
            return
        }
        viewModelScope.launch {
            _adbProbeState.value = AdbProbeUiState(
                probing = true,
                authorized = false,
                endpoint = "$trimmedHost:$port",
                summary = "Handshaking ($mode) with $trimmedHost:$port... If prompted on screen, tap Allow.",
                isError = false
            )
            val res = adbClient.probeConnect(trimmedHost, port, mode)
            res.fold(
                onSuccess = { detail ->
                    updateSettings { s -> s.copy(lastAdbEndpoint = "$trimmedHost:$port") }
                    auditLogger.log("adb", "probe", "auto_allowed", "Connected $trimmedHost:$port", detail)
                    _adbProbeState.value = AdbProbeUiState(
                        probing = false,
                        authorized = true,
                        endpoint = "$trimmedHost:$port",
                        summary = detail,
                        isError = false
                    )
                    _statusBanner.value = "ADB authorized ($trimmedHost:$port): $detail"
                },
                onFailure = { err ->
                    val msg = err.message ?: err.javaClass.simpleName
                    auditLogger.log("adb", "probe", "error", "Failed $trimmedHost:$port", msg)
                    _adbProbeState.value = AdbProbeUiState(
                        probing = false,
                        authorized = false,
                        endpoint = "$trimmedHost:$port",
                        summary = msg,
                        isError = true
                    )
                    _statusBanner.value = "ADB probe failed: $msg"
                }
            )
            refreshAuditEntries()
        }
    }

    fun runBridgeTrialTool(toolName: String, arguments: JsonObject = buildJsonObject {}) {
        if (_labRunning.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _labRunning.value = true
            val startMs = System.currentTimeMillis()
            val resultObj = runCatching {
                deviceBridgeServer.invokeTrialTool(toolName, arguments)
            }.getOrElse { err ->
                val elapsed = System.currentTimeMillis() - startMs
                _labLastResult.value = LabTrialResult(
                    tool = toolName,
                    decisionLabel = "ERROR",
                    isError = true,
                    durationMs = elapsed,
                    outputText = err.message ?: err.javaClass.simpleName,
                    timestampMs = System.currentTimeMillis()
                )
                _labRunning.value = false
                refreshAuditEntries()
                return@launch
            }
            val elapsed = System.currentTimeMillis() - startMs
            val isErr = resultObj["isError"]?.jsonPrimitive?.booleanOrNull ?: false
            val textParts = runCatching { resultObj["content"]?.jsonArray }
                .getOrNull()
                ?.mapNotNull { el ->
                    runCatching { el.jsonObject["text"]?.jsonPrimitive?.contentOrNull }.getOrNull()
                }
                .orEmpty()
            val body = textParts.joinToString("\n").ifBlank { resultObj.toString() }
            val decision = when {
                body.startsWith("[POLICY_BLOCKED]") -> "POLICY_BLOCKED"
                body.startsWith("[USER_DENIED]") -> "USER_DENIED"
                body.startsWith("[UNAVAILABLE]") -> "UNAVAILABLE"
                body.startsWith("[INVALID_ARGS]") -> "INVALID_ARGS"
                isErr -> "ERROR"
                else -> "ALLOWED_OK"
            }
            _labLastResult.value = LabTrialResult(
                tool = toolName,
                decisionLabel = decision,
                isError = isErr,
                durationMs = elapsed,
                outputText = body,
                timestampMs = System.currentTimeMillis()
            )
            _labRunning.value = false
            refreshAuditEntries()
        }
    }

    fun createEnvSnapshot() {
        viewModelScope.launch(Dispatchers.IO) {
            val res = envManager.createSnapshot("user")
            _statusBanner.value = res.fold(
                onSuccess = { "Created snapshot ${it.id} (${it.sessionCount} sessions)" },
                onFailure = { "Snapshot failed: ${it.message}" }
            )
            refreshSnapshots()
        }
    }

    fun restoreEnvSnapshot(snapshotId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val wasRunning = runtimeStatus.value.state == RuntimeLifecycleState.HEALTHY
            if (wasRunning) runtimeController.stopRuntime()
            val res = envManager.restoreSnapshot(snapshotId)
            _statusBanner.value = res.fold(
                onSuccess = { "Restored snapshot ${it.id}. Restarting runtime..." },
                onFailure = { "Restore rejected/rolled back: ${it.message}" }
            )
            refreshSnapshots()
            if (wasRunning) startRuntimeAndAttach()
        }
    }

    fun runSelfTest() {
        viewModelScope.launch(Dispatchers.IO) {
            _selfTestRunning.value = true
            val report = envManager.runSelfTest()
            _selfTestReport.value = report
            _envManifest.value = envManager.loadManifest()
            _selfTestRunning.value = false
        }
    }

    fun refreshSnapshots() {
        viewModelScope.launch(Dispatchers.IO) {
            _snapshots.value = envManager.listSnapshots()
        }
    }

    fun refreshAuditEntries() {
        viewModelScope.launch(Dispatchers.IO) {
            _auditEntries.value = auditLogger.readRecent(80)
        }
    }

    fun clearAuditEntries() {
        viewModelScope.launch(Dispatchers.IO) {
            auditLogger.clear()
            _auditEntries.value = emptyList()
        }
    }

    fun refreshLogsAndStatus() {
        viewModelScope.launch {
            _runtimeLogs.value = runtimeController.getRecentLogs(200)
        }
    }

    fun clearRuntimeLogs() {
        viewModelScope.launch {
            runtimeController.clearLogs()
            _runtimeLogs.value = emptyList()
        }
    }

    fun exportSanitizedDiagnosticsToClipboard() {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val statusSnap = runtimeController.getStatusSnapshot()
            val logs = runtimeController.getRecentLogs(100)
                .map { it.replace(Regex("""sk-[A-Za-z0-9_-]{12,}"""), "[REDACTED]") }
            val audit = auditLogger.readRecent(50)
            val s = settingsStore.current()
            val diag = buildJsonObject {
                put("exportedAtMs", System.currentTimeMillis())
                put("androidRelease", Build.VERSION.RELEASE)
                put("sdkInt", Build.VERSION.SDK_INT)
                put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
                put("hasApiKey", settingsStore.hasApiKey.value)
                put("permissionTier", s.permissionMode.envValue)
                put("bridgeEnabled", s.bridgeEnabled)
                put("allowAccessibilityRead", s.allowAccessibilityRead)
                put("allowAccessibilityControl", s.allowAccessibilityControl)
                put("allowAdbBridge", s.allowAdbBridge)
                put("requirePerCallConfirmation", s.requirePerCallConfirmation)
                put("accessibilityConnected", DshnAccessibilityService.connected.value)
                put("manifest", json.parseToJsonElement(json.encodeToString(envManager.loadManifest())))
                if (statusSnap != null) {
                    put("runtimeStatus", json.parseToJsonElement(json.encodeToString(statusSnap)))
                }
                put("recentAuditCount", audit.size)
                putJsonArray("recentLogs") { logs.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
            }.toString()

            val outFile = File(app.cacheDir, "dshn-diagnostics-latest.json")
            outFile.writeText(diag, Charsets.UTF_8)

            launch(Dispatchers.Main) {
                val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("DSHN Diagnostics", diag))
                _statusBanner.value = "Sanitized diagnostics copied to clipboard & saved to ${outFile.name}"
            }
        }
    }

    override fun onCleared() {
        terminalPty.stop()
        adbClient.stopDiscovery()
        deviceBridgeServer.stop()
        runtimeController.unbind()
        super.onCleared()
    }
}
