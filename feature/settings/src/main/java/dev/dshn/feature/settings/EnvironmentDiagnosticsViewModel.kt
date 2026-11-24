package dev.dshn.feature.settings

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.dshn.app.bridge.DshnAccessibilityService
import dev.dshn.app.bridge.RootAuthManager
import dev.dshn.app.data.ClipboardHelper
import dev.dshn.app.data.SettingsStore
import dev.dshn.app.data.StatusBannerBus
import dev.dshn.app.runtime.BundledEnvManifest
import dev.dshn.app.runtime.DshUpgradeReport
import dev.dshn.app.runtime.EnvInstallProgress
import dev.dshn.app.runtime.EnvManager
import dev.dshn.app.runtime.EnvManifest
import dev.dshn.app.runtime.EnvSnapshotInfo
import dev.dshn.app.runtime.EnvironmentCoordinator
import dev.dshn.app.runtime.RuntimeSessionCoordinator
import dev.dshn.app.runtime.RuntimeUiStatus
import dev.dshn.app.runtime.SelfTestReport
import dev.dshn.app.security.AuditLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File

class EnvironmentDiagnosticsViewModel(
    private val settingsStore: SettingsStore,
    private val envManager: EnvManager,
    private val environmentCoordinator: EnvironmentCoordinator,
    private val runtimeSessionCoordinator: RuntimeSessionCoordinator,
    private val rootAuthManager: RootAuthManager,
    private val auditLogger: AuditLogger,
    private val clipboardHelper: ClipboardHelper,
    private val statusBannerBus: StatusBannerBus,
    private val cacheDir: File
) : ViewModel() {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val envManifest: StateFlow<EnvManifest> = environmentCoordinator.envManifest
    val bundledEnvManifest: StateFlow<BundledEnvManifest?> = environmentCoordinator.bundledEnvManifest
    val envInstallProgress: StateFlow<EnvInstallProgress> = environmentCoordinator.envInstallProgress
    val envChecking: StateFlow<Boolean> = environmentCoordinator.envChecking
    val runtimeStatus: StateFlow<RuntimeUiStatus> = runtimeSessionCoordinator.runtimeStatus
    val snapshots: StateFlow<List<EnvSnapshotInfo>> = environmentCoordinator.snapshots
    val selfTestReport: StateFlow<SelfTestReport?> = environmentCoordinator.selfTestReport
    val selfTestRunning: StateFlow<Boolean> = environmentCoordinator.selfTestRunning
    val dshUpgrading: StateFlow<Boolean> = environmentCoordinator.dshUpgrading
    val dshUpgradeReport: StateFlow<DshUpgradeReport?> = environmentCoordinator.dshUpgradeReport
    val runtimeLogs: StateFlow<List<String>> = runtimeSessionCoordinator.runtimeLogs

    fun onEnvironmentEntered() {
        environmentCoordinator.refreshSnapshots()
        environmentCoordinator.refreshEnvironment()
    }

    fun onDiagnosticsEntered() {
        runtimeSessionCoordinator.refreshLogsAndStatus()
    }

    fun startRuntimeAndAttach() {
        runtimeSessionCoordinator.startRuntimeAndAttach()
    }

    fun restartRuntime() {
        runtimeSessionCoordinator.restartRuntime()
    }

    fun stopRuntime() {
        runtimeSessionCoordinator.stopRuntime()
    }

    fun installBundledEnvironment(reinstall: Boolean) {
        environmentCoordinator.installBundledEnvironment(reinstall = reinstall)
    }

    fun refreshEnvironment() {
        environmentCoordinator.refreshEnvironment()
    }

    fun runSelfTest() {
        environmentCoordinator.runSelfTest()
    }

    fun upgradeDshToVersion(targetVersion: String = EnvManager.TARGET_DSH_VERSION) {
        environmentCoordinator.upgradeDshToVersion(targetVersion)
    }

    fun createEnvSnapshot() {
        environmentCoordinator.createEnvSnapshot()
    }

    fun restoreEnvSnapshot(snapshotId: String) {
        environmentCoordinator.restoreEnvSnapshot(snapshotId)
    }

    fun refreshLogsAndStatus() {
        runtimeSessionCoordinator.refreshLogsAndStatus()
    }

    fun clearRuntimeLogs() {
        runtimeSessionCoordinator.clearRuntimeLogs()
    }

    fun copyToClipboard(label: String, text: String) {
        clipboardHelper.copy(label, text)
    }

    fun exportSanitizedDiagnosticsToClipboard() {
        viewModelScope.launch(Dispatchers.IO) {
            val statusSnap = runtimeSessionCoordinator.getStatusSnapshot()
            val logs = runtimeSessionCoordinator.getRecentLogs(100)
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
                putJsonArray("recentLogs") { logs.forEach { add(JsonPrimitive(it)) } }
            }.toString()

            val outFile = File(cacheDir, "dshn-diagnostics-latest.json")
            outFile.writeText(diag, Charsets.UTF_8)

            withContext(Dispatchers.Main) {
                clipboardHelper.copy("DSHN Diagnostics", diag, announce = false)
                statusBannerBus.showLocalized(
                    "脱敏诊断信息已复制到剪贴板",
                    "Sanitized diagnostics copied to clipboard"
                )
            }
        }
    }

    class Factory(
        private val settingsStore: SettingsStore,
        private val envManager: EnvManager,
        private val environmentCoordinator: EnvironmentCoordinator,
        private val runtimeSessionCoordinator: RuntimeSessionCoordinator,
        private val rootAuthManager: RootAuthManager,
        private val auditLogger: AuditLogger,
        private val clipboardHelper: ClipboardHelper,
        private val statusBannerBus: StatusBannerBus,
        private val cacheDir: File
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(EnvironmentDiagnosticsViewModel::class.java))
            return EnvironmentDiagnosticsViewModel(
                settingsStore = settingsStore,
                envManager = envManager,
                environmentCoordinator = environmentCoordinator,
                runtimeSessionCoordinator = runtimeSessionCoordinator,
                rootAuthManager = rootAuthManager,
                auditLogger = auditLogger,
                clipboardHelper = clipboardHelper,
                statusBannerBus = statusBannerBus,
                cacheDir = cacheDir
            ) as T
        }
    }
}
