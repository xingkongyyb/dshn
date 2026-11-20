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
import dev.dshn.app.bridge.RootAuthManager
import dev.dshn.app.bridge.RootAuthState
import dev.dshn.app.bridge.WirelessAdbClient
import dev.dshn.app.data.AdbTransportMode
import dev.dshn.app.data.AppLocaleMode
import dev.dshn.app.data.AppSettings
import dev.dshn.app.data.AppThemeMode
import dev.dshn.app.data.PermissionTier
import dev.dshn.app.data.SettingsStore
import dev.dshn.app.runtime.BundledEnvManifest
import dev.dshn.app.runtime.ContainerLauncher
import dev.dshn.app.runtime.DshUpgradeReport
import dev.dshn.app.runtime.EnvInstallProgress
import dev.dshn.app.runtime.EnvManager
import dev.dshn.app.runtime.EnvManifest
import dev.dshn.app.runtime.EnvSnapshotInfo
import dev.dshn.app.runtime.HostRootShellSession
import dev.dshn.app.runtime.RuntimeCallbackEvent
import dev.dshn.app.runtime.RuntimeController
import dev.dshn.app.runtime.RuntimeLifecycleState
import dev.dshn.app.runtime.RuntimeUiStatus
import dev.dshn.app.runtime.SelfTestReport
import dev.dshn.app.runtime.SessionSummaryItem
import dev.dshn.app.runtime.SlashCommandDescriptor
import dev.dshn.app.runtime.TerminalPtySession
import dev.dshn.app.runtime.UserQuestionAnswer
import dev.dshn.app.runtime.UserQuestionRequest
import dev.dshn.app.security.AuditEntry
import dev.dshn.app.security.AuditLogger
import dev.dshn.app.security.RootCommandPolicy
import dev.dshn.app.security.RootPolicyMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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

/** Screens reachable in the app. CHAT is the home screen; everything else is pushed on top. */
enum class AppRoute {
    CHAT,
    TERMINAL,
    DEVICE_BRIDGE,
    DEVICE_LAB,
    SETTINGS,
    SETTINGS_MODEL,
    SETTINGS_PERMISSION,
    SETTINGS_BACKGROUND,
    SETTINGS_ENVIRONMENT,
    SETTINGS_DIAGNOSTICS,
    ABOUT
}

enum class TerminalShellMode { CONTAINER_UBUNTU, HOST_ROOT_SU }

/** Optional follow-up action attached to a snackbar message. */
enum class BannerAction { RESTART_RUNTIME, OPEN_LOGS, OPEN_MODEL_SETTINGS, OPEN_ENVIRONMENT }

data class StatusBannerUi(
    val text: String,
    val action: BannerAction? = null,
    val id: Long = System.nanoTime()
)

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
    val isUnknownOutcomeBanner: Boolean = false,
    val isError: Boolean = false
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
    val rootAuthManager = RootAuthManager(auditLogger)
    val confirmationBroker = ConfirmationBroker(app)
    val deviceBridgeServer = DeviceBridgeMcpServer(
        context = app,
        scope = viewModelScope,
        settingsStore = settingsStore,
        auditLogger = auditLogger,
        confirmationBroker = confirmationBroker,
        adbClient = adbClient,
        rootAuthManager = rootAuthManager
    )
    private val runtimeController = RuntimeController(app, viewModelScope)
    private val terminalPty = TerminalPtySession(launcher, viewModelScope)
    private val hostRootShell = HostRootShellSession(rootAuthManager, viewModelScope)
    private val terminalBuffer = AnsiTerminalBuffer()

    val settings: StateFlow<AppSettings> = settingsStore.settings
    val hasApiKey: StateFlow<Boolean> = settingsStore.hasApiKey
    val runtimeStatus: StateFlow<RuntimeUiStatus> = runtimeController.status
    val accessibilityConnected: StateFlow<Boolean> = DshnAccessibilityService.connected
    val discoveredAdbEndpoints: StateFlow<List<DiscoveredAdbEndpoint>> = adbClient.endpoints
    val adbScanning: StateFlow<Boolean> = adbClient.scanning
    val rootAuthState: StateFlow<RootAuthState> = rootAuthManager.state
    val pendingBridgeConfirm: StateFlow<PendingBridgeConfirmation?> = confirmationBroker.pending

    // Navigation
    private val _backStack = MutableStateFlow(listOf(AppRoute.CHAT))
    val backStack: StateFlow<List<AppRoute>> = _backStack.asStateFlow()

    // First-run setup gate. Existing installs that are already configured skip it silently.
    private val _setupRequired = MutableStateFlow(computeInitialSetupRequired())
    val setupRequired: StateFlow<Boolean> = _setupRequired.asStateFlow()

    /** True while the setup wizard was manually reopened from Settings (so Back cancels cleanly). */
    private val _setupReopened = MutableStateFlow(false)
    val setupReopened: StateFlow<Boolean> = _setupReopened.asStateFlow()

    // Chat state
    private val _messages = MutableStateFlow<List<UiChatMessage>>(emptyList())
    val messages: StateFlow<List<UiChatMessage>> = _messages.asStateFlow()

    private val _activeSessionId = MutableStateFlow<String?>(null)
    val activeSessionId: StateFlow<String?> = _activeSessionId.asStateFlow()

    private val _activeSessionReadOnly = MutableStateFlow(false)
    val activeSessionReadOnly: StateFlow<Boolean> = _activeSessionReadOnly.asStateFlow()

    /** True only when [_activeSessionId] has been created or resumed in the live ACP runtime process. */
    private val _sessionAttached = MutableStateFlow(false)
    val sessionAttached: StateFlow<Boolean> = _sessionAttached.asStateFlow()

    private val _activeCwd = MutableStateFlow(settingsStore.current().defaultCwd.ifBlank { "/root" })
    val activeCwd: StateFlow<String> = _activeCwd.asStateFlow()

    private val _streaming = MutableStateFlow(false)
    val streaming: StateFlow<Boolean> = _streaming.asStateFlow()

    private val _sessionBusy = MutableStateFlow(false)
    /** True while a session is being created, resumed or switched. */
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

    /** Session IDs that currently have a queued permission or question waiting for user input. */
    private val _sessionsAwaitingInput = MutableStateFlow<Set<String>>(emptySet())
    val sessionsAwaitingInput: StateFlow<Set<String>> = _sessionsAwaitingInput.asStateFlow()

    private val _interruptedTurnBanner = MutableStateFlow<String?>(null)
    val interruptedTurnBanner: StateFlow<String?> = _interruptedTurnBanner.asStateFlow()

    private val sessionOpMutex = Mutex()
    private val streamingSessions = LinkedHashSet<String>()
    /** Sessions re-opened mid-reply: chunks sent while they were in the background are reloaded on completion. */
    private val resumedMidStream = HashSet<String>()
    private var sessionsRefreshGen = 0
    private val envInstallRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun envInstallBusy(): Boolean = envInstallRunning.get() || _envInstallProgress.value.inProgress

    private fun bannerInstalling() {
        banner(t("正在安装内置运行环境，请稍候…", "Installing bundled runtime, please wait…"), BannerAction.OPEN_ENVIRONMENT)
    }
    private val pendingPermissionsBySession = LinkedHashMap<String, ArrayDeque<PendingAcpPermissionUi>>()
    private val pendingQuestionsBySession = LinkedHashMap<String, ArrayDeque<UserQuestionRequest>>()
    private var attachedRestartCount: Int = -1
    private var currentStreamSessionId: String? = null
    private var saveApiKeyJob: Job? = null
    private var rootAuthJob: Job? = null

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
    private val _terminalMode = MutableStateFlow(TerminalShellMode.CONTAINER_UBUNTU)
    val terminalMode: StateFlow<TerminalShellMode> = _terminalMode.asStateFlow()

    private val _terminalOutput = MutableStateFlow("")
    val terminalOutput: StateFlow<String> = _terminalOutput.asStateFlow()
    val terminalRunning: StateFlow<Boolean> = terminalPty.running
    val hostRootTerminalRunning: StateFlow<Boolean> = hostRootShell.running

    // Root & DSH upgrade state
    private val _rootAuthorizing = MutableStateFlow(false)
    val rootAuthorizing: StateFlow<Boolean> = _rootAuthorizing.asStateFlow()

    private val _dshUpgrading = MutableStateFlow(false)
    val dshUpgrading: StateFlow<Boolean> = _dshUpgrading.asStateFlow()

    private val _dshUpgradeReport = MutableStateFlow<DshUpgradeReport?>(null)
    val dshUpgradeReport: StateFlow<DshUpgradeReport?> = _dshUpgradeReport.asStateFlow()

    // Settings / Env / Diagnostics state
    private val _envManifest = MutableStateFlow(envManager.loadManifest())
    val envManifest: StateFlow<EnvManifest> = _envManifest.asStateFlow()

    private val _bundledEnvManifest = MutableStateFlow(envManager.loadBundledManifest())
    val bundledEnvManifest: StateFlow<BundledEnvManifest?> = _bundledEnvManifest.asStateFlow()

    private val _envInstallProgress = MutableStateFlow(EnvInstallProgress())
    val envInstallProgress: StateFlow<EnvInstallProgress> = _envInstallProgress.asStateFlow()

    private val _envChecking = MutableStateFlow(false)
    val envChecking: StateFlow<Boolean> = _envChecking.asStateFlow()

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

    private val _apiKeyMasked = MutableStateFlow<String?>(null)
    val apiKeyMasked: StateFlow<String?> = _apiKeyMasked.asStateFlow()

    private val _statusBanner = MutableStateFlow<StatusBannerUi?>(null)
    val statusBanner: StateFlow<StatusBannerUi?> = _statusBanner.asStateFlow()

    init {
        deviceBridgeServer.start()
        runtimeController.bind()
        collectRuntimeStatus()
        collectRuntimeEvents()
        collectTerminalOutput()

        viewModelScope.launch(Dispatchers.IO) {
            val currentManifest = envManager.ensureRuntimeAssets()
            _envManifest.value = currentManifest
            rootAuthManager.probeEnvironment(settingsStore.current().customSuBinaryPath)
            refreshApiKeyMaskInternal()
            refreshAuditEntries()
            refreshAdbIdentity()
            val bundled = _bundledEnvManifest.value
            val needsBundledInstall = bundled != null && (
                !envManager.isRootfsReady() ||
                (currentManifest.bundledEnvVersion.isNotBlank() && currentManifest.bundledEnvVersion != bundled.bundleVersion)
            )
            if (needsBundledInstall) {
                installBundledEnvironmentInternal(reinstall = currentManifest.rootfsReady)
            } else if (!_setupRequired.value && settingsStore.hasApiKey.value && envManager.isRootfsReady()) {
                startRuntimeAndAttach()
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    private fun isZh(): Boolean = resolveIsZh(settingsStore.current().localeMode)

    private fun t(zh: String, en: String): String = if (isZh()) zh else en

    private fun banner(text: String, action: BannerAction? = null) {
        _statusBanner.value = StatusBannerUi(text, action)
    }

    private fun computeInitialSetupRequired(): Boolean {
        val (required, markDone) = shouldRequireInitialSetup(
            settings = settingsStore.current(),
            hasApiKey = settingsStore.hasApiKey.value,
            rootfsReady = envManager.isRootfsReady()
        )
        if (markDone) {
            // Already configured before the setup wizard existed: mark done and go straight to chat.
            settingsStore.update { it.copy(onboardingCompleted = true) }
        }
        return required
    }

    /** Clears the banner, but only if it is still the one identified by [id] (when given). */
    fun dismissBanner(id: Long? = null) {
        val cur = _statusBanner.value ?: return
        if (id == null || cur.id == id) _statusBanner.value = null
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
    // Navigation & setup
    // ------------------------------------------------------------------------------------------

    fun navigate(route: AppRoute) {
        if (route == AppRoute.CHAT) {
            navigateHome()
            return
        }
        _backStack.value = pushUniqueRoute(_backStack.value, route)
        onRouteEntered(route)
    }

    /** Pops one screen. Returns false when already at the home screen. */
    fun navigateBack(): Boolean {
        val stack = _backStack.value
        if (stack.size <= 1) return false
        _backStack.value = stack.dropLast(1)
        return true
    }

    fun navigateHome() {
        _backStack.value = listOf(AppRoute.CHAT)
    }

    private fun onRouteEntered(route: AppRoute) {
        when (route) {
            AppRoute.DEVICE_BRIDGE, AppRoute.DEVICE_LAB -> {
                refreshAuditEntries()
                refreshAdbIdentity()
            }
            AppRoute.SETTINGS -> refreshManifestOnly()
            AppRoute.SETTINGS_MODEL -> refreshApiKeyMask()
            AppRoute.SETTINGS_ENVIRONMENT -> {
                refreshSnapshots()
                refreshEnvironment()
            }
            AppRoute.SETTINGS_DIAGNOSTICS -> refreshLogsAndStatus()
            else -> {}
        }
    }

    fun recordSetupWizardStep(stepIndex: Int) {
        if (_setupReopened.value) return
        val cur = settingsStore.current()
        if (cur.onboardingCompleted) return
        val started = cur.setupWizardStarted || stepIndex > 0
        if (cur.setupWizardStarted != started || cur.setupWizardStep != stepIndex) {
            settingsStore.update { it.copy(setupWizardStarted = started, setupWizardStep = stepIndex) }
        }
    }

    fun completeSetup() {
        _setupReopened.value = false
        settingsStore.update {
            it.copy(
                onboardingCompleted = true,
                setupWizardStarted = false,
                setupWizardStep = 0
            )
        }
        _setupRequired.value = false
        navigateHome()
        viewModelScope.launch {
            saveApiKeyJob?.join()
            if (settingsStore.hasApiKey.value && envManager.isRootfsReady() && !isRuntimeUsable() &&
                runtimeStatus.value.state != RuntimeLifecycleState.STARTING
            ) {
                startRuntimeAndAttach()
            }
        }
    }

    fun reopenSetup() {
        refreshEnvironment()
        refreshApiKeyMask()
        _setupReopened.value = true
        _setupRequired.value = true
    }

    fun cancelReopenedSetup() {
        if (!_setupReopened.value) return
        _setupReopened.value = false
        _setupRequired.value = false
    }

    fun refreshEnvironment() {
        if (_envChecking.value || _envInstallProgress.value.inProgress) return
        viewModelScope.launch(Dispatchers.IO) {
            _envChecking.value = true
            runCatching { envManager.ensureRuntimeAssets() }
                .onSuccess { _envManifest.value = it }
            kotlinx.coroutines.delay(350) // let the checklist animation read as a real check
            _envChecking.value = false
        }
    }

    fun installBundledEnvironment(reinstall: Boolean = false) {
        if (envInstallBusy()) return
        viewModelScope.launch(Dispatchers.IO) {
            installBundledEnvironmentInternal(reinstall = reinstall)
        }
    }

    private suspend fun installBundledEnvironmentInternal(reinstall: Boolean) {
        // Only one install may run at a time (auto-install on launch vs. a user tap, or repeated taps).
        if (!envInstallRunning.compareAndSet(false, true)) return
        try {
            installBundledEnvironmentLocked(reinstall)
        } finally {
            envInstallRunning.set(false)
        }
    }

    private suspend fun installBundledEnvironmentLocked(reinstall: Boolean) {
        val wasRunning = isRuntimeUsable()
        // Publish "installing" before stopping the runtime so every other action sees it immediately.
        _envInstallProgress.value = EnvInstallProgress(
            inProgress = true,
            stageIndex = 1,
            stageCount = (_bundledEnvManifest.value?.archives?.size ?: 3) + 1,
            stageLabelZh = "准备解压内置环境…",
            stageLabelEn = "Preparing bundled runtime…"
        )
        if (wasRunning || reinstall) {
            terminalPty.stop()
            runCatching { runtimeController.stopRuntime() }
            if (wasRunning) kotlinx.coroutines.delay(600)
        }
        val res = envManager.installBundledEnvironment { progress ->
            _envInstallProgress.value = progress
        }
        res.fold(
            onSuccess = { manifest ->
                runCatching { settingsStore.ensureContainerApiKeySynced() }
                _envManifest.value = manifest
                _envInstallProgress.value = EnvInstallProgress(inProgress = false)
                if (reinstall) {
                    banner(t("内置运行环境已重新安装（对话与 API Key 已保留）", "Bundled runtime reinstalled (chats & API key preserved)"))
                }
                if ((wasRunning || !_setupRequired.value) && settingsStore.hasApiKey.value && !isRuntimeUsable()) {
                    startRuntimeAndAttach()
                }
            },
            onFailure = { err ->
                val msg = err.message ?: err.javaClass.simpleName
                _envInstallProgress.value = EnvInstallProgress(inProgress = false, error = msg)
                banner(t("内置环境安装失败：$msg", "Bundled runtime install failed: $msg"), BannerAction.OPEN_ENVIRONMENT)
            }
        )
    }

    private fun refreshManifestOnly() {
        viewModelScope.launch(Dispatchers.IO) {
            _envManifest.value = envManager.loadManifest()
        }
    }

    fun refreshApiKeyMask() {
        viewModelScope.launch(Dispatchers.IO) { refreshApiKeyMaskInternal() }
    }

    private fun refreshApiKeyMaskInternal() {
        val key = runCatching { settingsStore.getApiKey() }.getOrNull()
        _apiKeyMasked.value = key?.let { k ->
            val tail = k.takeLast(4)
            val head = if (k.startsWith("sk-")) "sk-" else k.take(2)
            "$head••••••••$tail"
        }
    }

    // ------------------------------------------------------------------------------------------
    // Runtime events & status
    // ------------------------------------------------------------------------------------------

    private fun syncActiveStreamingFlag() {
        val activeSid = _activeSessionId.value
        _streaming.value = activeSid != null && activeSid in streamingSessions
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
            viewModelScope.launch {
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
        viewModelScope.launch {
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
        viewModelScope.launch {
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
                                // Part of this reply streamed while the chat was in the background and was
                                // never received here; replace the partial bubble with the persisted turn.
                                val sid = ev.sessionId
                                viewModelScope.launch {
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

    private fun collectTerminalOutput() {
        viewModelScope.launch {
            terminalPty.output.collect { chunk ->
                if (_terminalMode.value == TerminalShellMode.CONTAINER_UBUNTU) {
                    terminalBuffer.append(chunk)
                    _terminalOutput.value = terminalBuffer.renderText()
                }
            }
        }
        viewModelScope.launch {
            hostRootShell.output.collect { chunk ->
                if (_terminalMode.value == TerminalShellMode.HOST_ROOT_SU) {
                    terminalBuffer.append(chunk)
                    _terminalOutput.value = terminalBuffer.renderText()
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Runtime lifecycle
    // ------------------------------------------------------------------------------------------

    /** Checks the prerequisites for talking to DSH and explains what is missing. */
    private fun ensurePrerequisites(): Boolean {
        if (!settingsStore.hasApiKey.value) {
            banner(t("请先设置 DeepSeek API Key", "Set your DeepSeek API key first"), BannerAction.OPEN_MODEL_SETTINGS)
            return false
        }
        if (_envInstallProgress.value.inProgress) {
            banner(t("正在安装内置运行环境，请稍候…", "Installing bundled runtime, please wait…"), BannerAction.OPEN_ENVIRONMENT)
            return false
        }
        if (!envManager.isRootfsReady()) {
            banner(t("运行环境尚未准备好", "The runtime environment is not ready yet"), BannerAction.OPEN_ENVIRONMENT)
            return false
        }
        return true
    }

    fun startRuntimeAndAttach() {
        if (!ensurePrerequisites()) return
        viewModelScope.launch {
            sessionOpMutex.withLock {
                if (isRuntimeUsable() && _sessionAttached.value && _activeSessionId.value != null && !_activeSessionReadOnly.value) {
                    return@withLock
                }
                _sessionBusy.value = true
                try {
                    runCatching {
                        runtimeController.startRuntime()
                        waitForHealthyRuntime()
                        val savedSid = settingsStore.current().selectedSessionId
                        if (!savedSid.isNullOrBlank()) {
                            val resumed = runCatching { resumeSessionInternal(savedSid, _activeCwd.value) }.isSuccess
                            if (!resumed) createSessionInternal(defaultCwd())
                        } else {
                            createSessionInternal(defaultCwd())
                        }
                        refreshSessions()
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
        if (envInstallBusy()) {
            bannerInstalling()
            return
        }
        viewModelScope.launch {
            sessionOpMutex.withLock {
                if (envInstallBusy()) {
                    bannerInstalling()
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
        viewModelScope.launch {
            sessionOpMutex.withLock {
                runCatching { runtimeController.stopRuntime() }
                    .onFailure { banner(t("停止失败：${it.message}", "Stop failed: ${it.message}")) }
            }
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
        error(t("等待 DSH 初始化超时", "Timed out waiting for DSH ACP initialization"))
    }

    private fun defaultCwd(): String = settingsStore.current().defaultCwd.trim().ifEmpty { "/root" }

    // ------------------------------------------------------------------------------------------
    // Sessions
    // ------------------------------------------------------------------------------------------

    /**
     * Starts a fresh conversation. Reuses the current one when it is still empty so tapping
     * "New chat" repeatedly does not pile up blank sessions.
     */
    fun startNewChat(cwd: String? = null) {
        if (_sessionBusy.value) return
        val target = cwd?.trim()?.ifEmpty { null } ?: defaultCwd()
        navigateHome()
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
        viewModelScope.launch {
            sessionOpMutex.withLock {
                _sessionBusy.value = true
                try {
                    runCatching {
                        if (!isRuntimeUsable()) {
                            runtimeController.startRuntime()
                            waitForHealthyRuntime()
                        }
                        createSessionInternal(cwd)
                        navigateHome()
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
        detachCurrentStreamBuffers()
        _activeSessionId.value = sid
        _activeCwd.value = cwd
        _activeSessionReadOnly.value = false
        _sessionAttached.value = true
        attachedRestartCount = runtimeStatus.value.restartCount
        _interruptedTurnBanner.value = null
        _configOptions.value = cfg
        _messages.value = emptyList()
        _usageUsed.value = 0L
        _usageSize.value = 0L
        lastSubmittedPromptText = null
        syncActiveStreamingFlag()
        syncPendingDialogs()
        settingsStore.update { it.copy(selectedSessionId = sid) }
        refreshSlashCommands(sid)
    }

    fun switchToSession(item: SessionSummaryItem) {
        if (item.id == _activeSessionId.value) {
            navigateHome()
            return
        }
        if (_sessionBusy.value) return
        viewModelScope.launch {
            sessionOpMutex.withLock {
                if (item.corrupt || item.readOnly) {
                    // Corrupt file: preview read-only without touching anything on disk.
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
                    navigateHome()
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
                        navigateHome()
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
                json.decodeFromJsonElement(
                    kotlinx.serialization.builtins.ListSerializer(AcpConfigOption.serializer()),
                    el
                )
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

        // Replay persisted surface history from the bridge UDS
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
        viewModelScope.launch {
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
        viewModelScope.launch {
            _sessionsLoading.value = true
            _sessionsError.value = null
            val res = runCatching { runtimeController.listSessionsFromBridge() }
            if (gen != sessionsRefreshGen) return@launch // a newer refresh owns the list now
            res.onSuccess {
                _sessions.value = it.sortedByDescending { s -> s.createdAt }
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
                if (sessionId == null || sessionId == _activeSessionId.value) {
                    _slashCommands.value = it
                }
            }
        }
    }

    fun setConfigOption(configId: String, value: String) {
        if (_activeSessionReadOnly.value || !_sessionAttached.value) return
        val sid = _activeSessionId.value ?: return
        viewModelScope.launch {
            runCatching {
                val raw = runtimeController.setConfigOption(sid, configId, value)
                if (sid != _activeSessionId.value) return@runCatching
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
            }.onFailure {
                banner(t("切换失败：${it.message}", "Config update failed: ${it.message}"))
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Prompting
    // ------------------------------------------------------------------------------------------

    /** Returns true when the input was accepted (so the composer can be cleared). */
    fun sendPromptOrCommand(input: String, appendUserBubble: Boolean = true): Boolean {
        val trimmed = input.trim()
        if (trimmed.isEmpty() || _streaming.value || _activeSessionReadOnly.value) return false
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
            if (envInstallBusy()) {
                bannerInstalling()
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

        // Registered slash command
        if (trimmed.startsWith("/")) {
            val cmdName = trimmed.removePrefix("/").substringBefore(' ').trim()
            if (_slashCommands.value.any { it.name == cmdName }) {
                viewModelScope.launch {
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
        syncActiveStreamingFlag()
        appendMessage(UiChatMessage(assistantId, "assistant", isStreaming = true))

        viewModelScope.launch {
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
            // Drop the previous answer (and its error notes) for this turn; keep "outcome unknown" notices.
            val head = before.subList(0, lastUserIdx + 1)
            val tail = before.subList(lastUserIdx + 1, before.size)
                .filter { it.role == "system" && it.isUnknownOutcomeBanner }
            _messages.value = head + tail
        }
        if (!sendPromptOrCommand(last, appendUserBubble = false)) {
            _messages.value = before // nothing was sent: put the old answer back
        }
    }

    fun cancelActivePrompt() {
        val sid = _activeSessionId.value ?: return
        if (!isRuntimeUsable()) {
            streamingSessions.remove(sid)
            if (currentStreamSessionId == sid) finalizeStreamingMessage()
            syncActiveStreamingFlag()
            return
        }
        viewModelScope.launch {
            runCatching { runtimeController.cancelPrompt(sid) }
        }
    }

    fun replyAcpPermission(optionId: String?, cancelled: Boolean) {
        val req = _pendingPermission.value ?: return
        val queue = pendingPermissionsBySession[req.sessionId]
        queue?.removeAll { it.requestId == req.requestId }
        if (queue != null && queue.isEmpty()) pendingPermissionsBySession.remove(req.sessionId)
        syncPendingDialogs()
        viewModelScope.launch {
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
        viewModelScope.launch {
            runtimeController.replyQuestion(req.requestId, answers, cancelled)
        }
    }

    fun respondBridgeConfirmation(allowOnce: Boolean) {
        val req = confirmationBroker.pending.value ?: return
        confirmationBroker.respond(req.id, allowOnce)
    }

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
            appendMessage(UiChatMessage(assistantId, "assistant", isStreaming = true))
        }
        when (type) {
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
        currentStreamSessionId = null
        currentAssistantMsgId = null
        streamTextBuf.clear()
        streamThinkBuf.clear()
        streamTools.clear()
        if (msgId != null) {
            _messages.value = _messages.value.mapNotNull {
                when {
                    it.id != msgId -> it
                    // Drop an assistant placeholder that never received any content.
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

    // ------------------------------------------------------------------------------------------
    // Terminal
    // ------------------------------------------------------------------------------------------

    fun setTerminalMode(mode: TerminalShellMode) {
        if (mode == TerminalShellMode.HOST_ROOT_SU &&
            !rootAuthManager.isAuthorized(settingsStore.current().rootSessionTtlMinutes)
        ) {
            banner(t("请先在「设备桥与 Root」中授权 Root", "Authorize Root in Device bridge first"))
            return
        }
        val prev = _terminalMode.value
        if (prev != mode) {
            when (prev) {
                TerminalShellMode.CONTAINER_UBUNTU -> terminalPty.stop()
                TerminalShellMode.HOST_ROOT_SU -> hostRootShell.stop()
            }
            _terminalMode.value = mode
            clearTerminal()
        }
    }

    fun startTerminal(cwd: String = _activeCwd.value) {
        when (_terminalMode.value) {
            TerminalShellMode.CONTAINER_UBUNTU -> {
                if (envInstallBusy()) {
                    bannerInstalling()
                    return
                }
                if (!envManager.isRootfsReady()) {
                    banner(t("运行环境尚未准备好，无法启动终端", "Runtime environment not ready"), BannerAction.OPEN_ENVIRONMENT)
                    return
                }
                hostRootShell.stop()
                terminalPty.start(cols = 80, rows = 26, cwd = cwd)
            }
            TerminalShellMode.HOST_ROOT_SU -> {
                terminalPty.stop()
                val res = hostRootShell.start(settingsStore.current().rootSessionTtlMinutes)
                res.onFailure {
                    banner(t("Root 终端启动失败：${it.message}", "Host Root shell failed: ${it.message}"))
                }
                refreshAuditEntries()
            }
        }
    }

    fun sendTerminalInput(text: String) {
        when (_terminalMode.value) {
            TerminalShellMode.CONTAINER_UBUNTU -> terminalPty.writeInput(text)
            TerminalShellMode.HOST_ROOT_SU -> {
                val normalized = text.replace("\r", "\n")
                hostRootShell.writeInput(normalized)
            }
        }
    }

    fun clearTerminal() {
        terminalBuffer.clear()
        _terminalOutput.value = ""
    }

    // ------------------------------------------------------------------------------------------
    // Settings
    // ------------------------------------------------------------------------------------------

    fun saveApiKey(
        key: String,
        autoStartRuntime: Boolean = true,
        onResult: (Boolean) -> Unit = {}
    ) {
        val job = viewModelScope.launch(Dispatchers.IO) {
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
                if (autoStartRuntime && key.isNotBlank() && !_setupRequired.value && envManager.isRootfsReady() &&
                    runtimeStatus.value.state == RuntimeLifecycleState.STOPPED
                ) {
                    startRuntimeAndAttach()
                }
            }
        }
        saveApiKeyJob = job
    }

    fun setBridgeEnabled(enabled: Boolean) {
        if (enabled) {
            updateSettings { it.copy(bridgeEnabled = true) }
        } else {
            updateSettings {
                it.copy(
                    bridgeEnabled = false,
                    allowAccessibilityRead = false,
                    allowAccessibilityControl = false,
                    allowAdbBridge = false,
                    allowRootBridge = false
                )
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

    fun setPermissionTier(tier: PermissionTier, notify: Boolean = true) {
        if (settingsStore.current().permissionMode == tier) return
        updateSettings { it.copy(permissionMode = tier) }
        if (notify && isRuntimeUsable()) banner(t("执行权限已更改，重启运行环境后生效", "Permission tier changed. Restart the runtime to apply."), BannerAction.RESTART_RUNTIME)
    }

    fun saveWorkspaceSettings(defaultCwd: String, bindHostPath: String) {
        val before = settingsStore.current().extraWorkspaceBindHostPath
        val newBind = bindHostPath.trim().ifEmpty { null }
        updateSettings {
            it.copy(
                defaultCwd = defaultCwd.trim().ifEmpty { "/root" },
                extraWorkspaceBindHostPath = newBind
            )
        }
        if (before != newBind) {
            banner(t("工作区已保存，重启运行环境后生效", "Workspace saved. Restart the runtime to apply."), BannerAction.RESTART_RUNTIME)
        } else {
            banner(t("已保存", "Saved"))
        }
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
        banner(t("已开启 ADB 试验预设（逐次确认开启，无障碍关闭）", "ADB trial gates enabled (per-call confirmation ON, Accessibility OFF)."))
    }

    fun lockAllDeviceBridgeSwitches() {
        rootAuthJob?.cancel()
        rootAuthJob = null
        _rootAuthorizing.value = false
        hostRootShell.stop()
        if (_terminalMode.value == TerminalShellMode.HOST_ROOT_SU) {
            _terminalMode.value = TerminalShellMode.CONTAINER_UBUNTU
            clearTerminal()
        }
        if (adbClient.scanning.value) {
            adbClient.stopDiscovery()
        }
        _adbProbeState.value = AdbProbeUiState()
        rootAuthManager.revokeAuthorization("Locked via Lock All Device Bridge Switches")
        updateSettings {
            it.copy(
                bridgeEnabled = false,
                allowAccessibilityRead = false,
                allowAccessibilityControl = false,
                allowAdbBridge = false,
                allowRootBridge = false,
                requirePerCallConfirmation = true
            )
        }
        refreshAuditEntries()
        banner(t("设备桥与 Root 已全部关闭", "All Device Bridge & Root switches locked OFF."))
    }

    // Root authorization actions
    fun probeRootEnvironment() {
        viewModelScope.launch(Dispatchers.IO) {
            val st = rootAuthManager.probeEnvironment(settingsStore.current().customSuBinaryPath)
            banner(
                if (st.suBinaryPath != null) {
                    t("检测到 ${st.suManagerHint}：${st.suBinaryPath}", "Detected ${st.suManagerHint} at ${st.suBinaryPath} (${st.status.name})")
                } else {
                    t("这台设备上没有检测到 su", "No su binary detected on this device.")
                }
            )
        }
    }

    fun requestRootAuthorization() {
        if (_rootAuthorizing.value) return
        rootAuthJob = viewModelScope.launch {
            _rootAuthorizing.value = true
            try {
                val customSu = settingsStore.current().customSuBinaryPath
                val res = rootAuthManager.requestAuthorization(customPath = customSu, timeoutSec = 15L)
                res.fold(
                    onSuccess = { st ->
                        banner(t("Root 已授权（${st.suManagerHint}）", "Root authorized (${st.suManagerHint}): ${st.uidOutput}"))
                    },
                    onFailure = { err ->
                        banner(t("Root 授权失败：${err.message}", "Root authorization failed: ${err.message}"))
                    }
                )
            } finally {
                _rootAuthorizing.value = false
                rootAuthJob = null
                refreshAuditEntries()
            }
        }
    }

    fun revokeRootAuthorization() {
        rootAuthJob?.cancel()
        rootAuthJob = null
        _rootAuthorizing.value = false
        hostRootShell.stop()
        if (_terminalMode.value == TerminalShellMode.HOST_ROOT_SU) {
            _terminalMode.value = TerminalShellMode.CONTAINER_UBUNTU
            clearTerminal()
        }
        rootAuthManager.revokeAuthorization("Revoked by user")
        updateSettings { it.copy(allowRootBridge = false) }
        refreshAuditEntries()
        banner(t("已撤销 Root 授权并关闭 Root 命令", "Root authorization revoked & Root Bridge switched OFF."))
    }

    fun enableRootTrialPreset() {
        updateSettings {
            it.copy(
                bridgeEnabled = true,
                allowRootBridge = true,
                requirePerCallConfirmation = true
            )
        }
        banner(t("已开启 Root 试验预设（逐次确认开启）", "Root Bridge trial gates enabled (per-call confirmation ON)."))
    }

    fun setRootPolicyMode(mode: RootPolicyMode) {
        updateSettings { it.copy(rootPolicyMode = mode) }
    }

    fun setRootSessionTtl(minutes: Int) {
        updateSettings { it.copy(rootSessionTtlMinutes = minutes.coerceAtLeast(0)) }
    }

    fun applyPhantomProcessFixViaRoot() {
        viewModelScope.launch(Dispatchers.IO) {
            val ttl = settingsStore.current().rootSessionTtlMinutes
            if (!rootAuthManager.isAuthorized(ttl)) {
                banner(t("请先授权 Root", "Authorize Root first before applying Phantom Process fix."))
                return@launch
            }
            val argv = listOf(
                "/system/bin/device_config",
                "put",
                "activity_manager",
                "max_phantom_processes",
                "2147483647"
            )
            val decision = RootCommandPolicy.evaluate(argv, RootPolicyMode.ALLOWLIST_ONLY)
            if (decision !is RootCommandPolicy.PolicyDecision.Allowed) {
                banner(t("策略拒绝了该命令", "Policy rejected phantom process fix."))
                return@launch
            }
            val res = runCatching {
                rootAuthManager.executeAllowed(decision, timeoutSec = 15L, ttlMinutes = ttl)
            }
            res.fold(
                onSuccess = { exec ->
                    auditLogger.log(
                        "root",
                        "phantom_process_fix",
                        if (exec.exitCode == 0) "allowed_once" else "error",
                        "Set max_phantom_processes=2147483647",
                        "exit=${exec.exitCode} ${exec.stderr.take(120)}"
                    )
                    banner(
                        if (exec.exitCode == 0) {
                            t("已解除幽灵进程数量限制", "Phantom process limit disabled via Root.")
                        } else {
                            t("执行失败（exit=${exec.exitCode}）：${exec.stderr.take(120)}", "Phantom process fix exit=${exec.exitCode}: ${exec.stderr.take(120)}")
                        }
                    )
                },
                onFailure = { err ->
                    banner(t("执行失败：${err.message}", "Phantom process fix failed: ${err.message}"))
                }
            )
            refreshAuditEntries()
        }
    }

    fun saveEndpointAndSearchConfig(
        deepseekBaseUrl: String,
        deepseekSearchBaseUrl: String,
        webSearchProvider: String
    ) {
        updateSettings {
            it.copy(
                deepseekBaseUrl = deepseekBaseUrl.trim().ifEmpty { null },
                deepseekSearchBaseUrl = deepseekSearchBaseUrl.trim().ifEmpty { null },
                webSearchProvider = webSearchProvider.trim()
            )
        }
        banner(t("接口设置已保存，重启运行环境后生效", "Endpoint settings saved. Restart the runtime to apply."), BannerAction.RESTART_RUNTIME)
    }

    fun upgradeDshToVersion(targetVersion: String = EnvManager.TARGET_DSH_VERSION) {
        if (_dshUpgrading.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _dshUpgrading.value = true
            val wasRunning = isRuntimeUsable()
            if (wasRunning) {
                runCatching { runtimeController.stopRuntime() }
                kotlinx.coroutines.delay(800)
            }
            val res = envManager.upgradeDshToVersion(targetVersion)
            _envManifest.value = envManager.loadManifest()
            _snapshots.value = envManager.listSnapshots()
            res.fold(
                onSuccess = { report ->
                    _dshUpgradeReport.value = report
                    banner(
                        t(
                            "DSH 已更新到 ${report.installedVersion}" + if (report.selfTestPassed) "，自检通过" else "，自检未通过",
                            "DSH upgraded ${report.previousVersion.ifEmpty { "?" }} -> ${report.installedVersion} (selfTest=${report.selfTestPassed})"
                        )
                    )
                    if (wasRunning) startRuntimeAndAttach()
                },
                onFailure = { err ->
                    banner(t("DSH 更新失败：${err.message}", "DSH upgrade failed: ${err.message}"), BannerAction.OPEN_LOGS)
                    if (wasRunning) startRuntimeAndAttach()
                }
            )
            _dshUpgrading.value = false
        }
    }

    fun copyToClipboard(label: String, text: String) {
        val cm = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        banner(t("已复制", "Copied"))
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
                endpoint = if (port > 0) "$trimmedHost:$port" else trimmedHost,
                summary = t("地址格式不正确，应为 主机:端口", "Invalid endpoint host:port"),
                isError = true
            )
            return
        }
        viewModelScope.launch {
            _adbProbeState.value = AdbProbeUiState(
                probing = true,
                authorized = false,
                endpoint = "$trimmedHost:$port",
                summary = t(
                    "正在与 $trimmedHost:$port 握手（$mode）… 如果屏幕上弹出授权提示，请点「允许」。",
                    "Handshaking ($mode) with $trimmedHost:$port... If prompted on screen, tap Allow."
                ),
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
                    banner(t("ADB 已连接：$trimmedHost:$port", "ADB authorized ($trimmedHost:$port)"))
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
                    banner(t("ADB 连接失败：$msg", "ADB probe failed: $msg"))
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
                body.startsWith("[ROOT_UNAUTHORIZED]") -> "ROOT_UNAUTHORIZED"
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
            banner(
                res.fold(
                    onSuccess = { t("快照已创建（包含 ${it.sessionCount} 个对话）", "Created snapshot ${it.id} (${it.sessionCount} sessions)") },
                    onFailure = { t("创建快照失败：${it.message}", "Snapshot failed: ${it.message}") }
                )
            )
            refreshSnapshots()
        }
    }

    fun restoreEnvSnapshot(snapshotId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val wasRunning = isRuntimeUsable()
            if (wasRunning) runtimeController.stopRuntime()
            val res = envManager.restoreSnapshot(snapshotId)
            banner(
                res.fold(
                    onSuccess = { t("已恢复快照，正在重启运行环境…", "Restored snapshot ${it.id}. Restarting runtime...") },
                    onFailure = { t("恢复被拒绝并已回滚：${it.message}", "Restore rejected/rolled back: ${it.message}") }
                )
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
                put("allowRootBridge", s.allowRootBridge)
                put("rootPolicyMode", s.rootPolicyMode.name)
                put("rootStatus", rootAuthManager.state.value.status.name)
                put("rootSuDetected", rootAuthManager.state.value.suBinaryPath != null)
                put("rootManagerHint", rootAuthManager.state.value.suManagerHint)
                put("targetDshVersion", EnvManager.TARGET_DSH_VERSION)
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
                banner(t("脱敏诊断信息已复制到剪贴板", "Sanitized diagnostics copied to clipboard"))
            }
        }
    }

    override fun onCleared() {
        terminalPty.stop()
        hostRootShell.stop()
        adbClient.stopDiscovery()
        deviceBridgeServer.stop()
        runtimeController.unbind()
        super.onCleared()
    }
}

/**
 * Deduplicates navigation back-stack entries across the entire stack so every [AppRoute]
 * appears at most once (keeping `SaveableStateProvider(route.name)` collision-free).
 */
internal fun pushUniqueRoute(stack: List<AppRoute>, route: AppRoute): List<AppRoute> {
    if (route == AppRoute.CHAT) return listOf(AppRoute.CHAT)
    val existingIdx = stack.indexOf(route)
    return when {
        existingIdx == stack.lastIndex -> stack
        existingIdx >= 0 -> stack.take(existingIdx + 1)
        else -> stack + route
    }
}

/**
 * Decides whether first-run setup should be shown on cold start.
 * Returns `(setupRequired, shouldMarkOnboardingCompleted)`.
 */
internal fun shouldRequireInitialSetup(
    settings: AppSettings,
    hasApiKey: Boolean,
    rootfsReady: Boolean
): Pair<Boolean, Boolean> {
    if (settings.onboardingCompleted) return false to false
    // If the user already started stepping through the wizard and the process died mid-wizard,
    // keep showing the remaining steps instead of silently skipping them.
    if (settings.setupWizardStarted) return true to false
    if (hasApiKey && rootfsReady) return false to true
    return true to false
}

/**
 * Parses `host:port` (or bracketed IPv6 `[host]:port`) for Wireless ADB endpoints.
 * Malformed input always yields port = -1 so the caller surfaces a clear validation error.
 */
internal fun parseHostPort(raw: String): Pair<String, Int> {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return "" to -1
    if (trimmed.startsWith("[")) {
        val close = trimmed.indexOf("]:")
        if (close <= 1) return trimmed to -1
        val host = trimmed.substring(1, close).trim()
        val portStr = trimmed.substring(close + 2)
        val port = if (portStr.isNotEmpty() && portStr.all { it.isDigit() }) portStr.toIntOrNull() ?: -1 else -1
        return host to port
    }
    val firstColon = trimmed.indexOf(':')
    val lastColon = trimmed.lastIndexOf(':')
    if (firstColon <= 0 || firstColon != lastColon) {
        return trimmed to -1
    }
    val host = trimmed.substring(0, firstColon).trim()
    val portStr = trimmed.substring(firstColon + 1)
    val port = if (portStr.isNotEmpty() && portStr.all { it.isDigit() }) portStr.toIntOrNull() ?: -1 else -1
    return host to port
}
