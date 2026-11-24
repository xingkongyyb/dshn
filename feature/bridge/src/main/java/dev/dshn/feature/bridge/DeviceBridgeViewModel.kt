package dev.dshn.feature.bridge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.dshn.app.bridge.AdbIdentitySummary
import dev.dshn.app.bridge.DeviceBridgeMcpServer
import dev.dshn.app.bridge.DiscoveredAdbEndpoint
import dev.dshn.app.bridge.DshnAccessibilityService
import dev.dshn.app.bridge.RootAuthManager
import dev.dshn.app.bridge.RootAuthState
import dev.dshn.app.bridge.WirelessAdbClient
import dev.dshn.app.data.AdbTransportMode
import dev.dshn.app.data.AppSettings
import dev.dshn.app.data.ClipboardHelper
import dev.dshn.app.data.SettingsStore
import dev.dshn.app.data.StatusBannerBus
import dev.dshn.app.security.AuditEntry
import dev.dshn.app.security.AuditLogger
import dev.dshn.app.security.RootPolicyMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class AdbProbeUiState(
    val probing: Boolean = false,
    val authorized: Boolean = false,
    val endpoint: String = "",
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

class DeviceBridgeViewModel(
    private val settingsStore: SettingsStore,
    private val deviceBridgeServer: DeviceBridgeMcpServer,
    private val adbClient: WirelessAdbClient,
    private val rootAuthManager: RootAuthManager,
    private val auditLogger: AuditLogger,
    private val clipboardHelper: ClipboardHelper,
    private val statusBannerBus: StatusBannerBus
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsStore.settings
    val accessibilityConnected: StateFlow<Boolean> = DshnAccessibilityService.connected
    val discoveredAdbEndpoints: StateFlow<List<DiscoveredAdbEndpoint>> = adbClient.endpoints
    val adbScanning: StateFlow<Boolean> = adbClient.scanning
    val rootAuthState: StateFlow<RootAuthState> = rootAuthManager.state

    private val _adbIdentity = MutableStateFlow<AdbIdentitySummary?>(null)
    val adbIdentity: StateFlow<AdbIdentitySummary?> = _adbIdentity.asStateFlow()

    private val _adbProbeState = MutableStateFlow(AdbProbeUiState())
    val adbProbeState: StateFlow<AdbProbeUiState> = _adbProbeState.asStateFlow()

    private val _rootAuthorizing = MutableStateFlow(false)
    val rootAuthorizing: StateFlow<Boolean> = _rootAuthorizing.asStateFlow()

    private val _labRunning = MutableStateFlow(false)
    val labRunning: StateFlow<Boolean> = _labRunning.asStateFlow()

    private val _labLastResult = MutableStateFlow<LabTrialResult?>(null)
    val labLastResult: StateFlow<LabTrialResult?> = _labLastResult.asStateFlow()

    private val _auditEntries = MutableStateFlow<List<AuditEntry>>(emptyList())
    val auditEntries: StateFlow<List<AuditEntry>> = _auditEntries.asStateFlow()

    private var rootAuthJob: Job? = null

    fun onEntered() {
        refreshAuditEntries()
        refreshAdbIdentity()
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
        statusBannerBus.showLocalized(
            "已开启 ADB 试验预设（逐次确认开启，无障碍关闭）",
            "ADB trial gates enabled (per-call confirmation ON, Accessibility OFF)."
        )
    }

    fun enableRootTrialPreset() {
        updateSettings {
            it.copy(
                bridgeEnabled = true,
                allowRootBridge = true,
                requirePerCallConfirmation = true
            )
        }
        statusBannerBus.showLocalized(
            "已开启 Root 试验预设（逐次确认开启）",
            "Root Bridge trial gates enabled (per-call confirmation ON)."
        )
    }

    fun lockAllDeviceBridgeSwitches() {
        rootAuthJob?.cancel()
        rootAuthJob = null
        _rootAuthorizing.value = false
        if (adbClient.scanning.value) {
            adbClient.stopDiscovery()
        }
        _adbProbeState.value = AdbProbeUiState()
        // HostRootShellSession and TerminalViewModel both observe rootAuthManager.state
        // and automatically terminate the root shell / switch back to container Ubuntu.
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
        statusBannerBus.showLocalized(
            "设备桥与 Root 已全部关闭",
            "All Device Bridge & Root switches locked OFF."
        )
    }

    fun probeRootEnvironment() {
        viewModelScope.launch(Dispatchers.IO) {
            val st = rootAuthManager.probeEnvironment(settingsStore.current().customSuBinaryPath)
            statusBannerBus.showLocalized(
                if (st.suBinaryPath != null) {
                    "检测到 ${st.suManagerHint}：${st.suBinaryPath}"
                } else {
                    "这台设备上没有检测到 su"
                },
                if (st.suBinaryPath != null) {
                    "Detected ${st.suManagerHint} at ${st.suBinaryPath} (${st.status.name})"
                } else {
                    "No su binary detected on this device."
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
                        statusBannerBus.showLocalized(
                            "Root 已授权（${st.suManagerHint}）",
                            "Root authorized (${st.suManagerHint}): ${st.uidOutput}"
                        )
                    },
                    onFailure = { err ->
                        statusBannerBus.showLocalized(
                            "Root 授权失败：${err.message}",
                            "Root authorization failed: ${err.message}"
                        )
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
        rootAuthManager.revokeAuthorization("Revoked by user")
        updateSettings { it.copy(allowRootBridge = false) }
        refreshAuditEntries()
        statusBannerBus.showLocalized(
            "已撤销 Root 授权并关闭 Root 命令",
            "Root authorization revoked & Root Bridge switched OFF."
        )
    }

    fun setRootPolicyMode(mode: RootPolicyMode) {
        updateSettings { it.copy(rootPolicyMode = mode) }
    }

    fun setRootSessionTtl(minutes: Int) {
        updateSettings { it.copy(rootSessionTtlMinutes = minutes.coerceAtLeast(0)) }
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
                summary = statusBannerBus.text("地址格式不正确，应为 主机:端口", "Invalid endpoint host:port"),
                isError = true
            )
            return
        }
        viewModelScope.launch {
            _adbProbeState.value = AdbProbeUiState(
                probing = true,
                authorized = false,
                endpoint = "$trimmedHost:$port",
                summary = statusBannerBus.text(
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
                    statusBannerBus.showLocalized(
                        "ADB 已连接：$trimmedHost:$port",
                        "ADB authorized ($trimmedHost:$port)"
                    )
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
                    statusBannerBus.showLocalized(
                        "ADB 连接失败：$msg",
                        "ADB probe failed: $msg"
                    )
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

    fun copyToClipboard(label: String, text: String) {
        clipboardHelper.copy(label, text)
    }

    override fun onCleared() {
        adbClient.stopDiscovery()
        super.onCleared()
    }

    class Factory(
        private val settingsStore: SettingsStore,
        private val deviceBridgeServer: DeviceBridgeMcpServer,
        private val adbClient: WirelessAdbClient,
        private val rootAuthManager: RootAuthManager,
        private val auditLogger: AuditLogger,
        private val clipboardHelper: ClipboardHelper,
        private val statusBannerBus: StatusBannerBus
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(DeviceBridgeViewModel::class.java))
            return DeviceBridgeViewModel(
                settingsStore = settingsStore,
                deviceBridgeServer = deviceBridgeServer,
                adbClient = adbClient,
                rootAuthManager = rootAuthManager,
                auditLogger = auditLogger,
                clipboardHelper = clipboardHelper,
                statusBannerBus = statusBannerBus
            ) as T
        }
    }
}
