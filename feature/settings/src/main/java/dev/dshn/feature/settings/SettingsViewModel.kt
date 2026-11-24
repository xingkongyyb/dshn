package dev.dshn.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.dshn.app.bridge.RootAuthManager
import dev.dshn.app.bridge.RootAuthState
import dev.dshn.app.data.AppLocaleMode
import dev.dshn.app.data.AppSettings
import dev.dshn.app.data.AppThemeMode
import dev.dshn.app.data.BannerAction
import dev.dshn.app.data.ClipboardHelper
import dev.dshn.app.data.PermissionTier
import dev.dshn.app.data.SettingsStore
import dev.dshn.app.data.StatusBannerBus
import dev.dshn.app.runtime.EnvInstallProgress
import dev.dshn.app.runtime.EnvManifest
import dev.dshn.app.runtime.EnvironmentCoordinator
import dev.dshn.app.runtime.RuntimeSessionCoordinator
import dev.dshn.app.runtime.RuntimeUiStatus
import dev.dshn.app.security.AuditLogger
import dev.dshn.app.security.RootCommandPolicy
import dev.dshn.app.security.RootPolicyMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val settingsStore: SettingsStore,
    private val runtimeSessionCoordinator: RuntimeSessionCoordinator,
    private val environmentCoordinator: EnvironmentCoordinator,
    private val rootAuthManager: RootAuthManager,
    private val auditLogger: AuditLogger,
    private val clipboardHelper: ClipboardHelper,
    private val statusBannerBus: StatusBannerBus
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsStore.settings
    val hasApiKey: StateFlow<Boolean> = settingsStore.hasApiKey
    val apiKeyMasked: StateFlow<String?> = runtimeSessionCoordinator.apiKeyMasked
    val envManifest: StateFlow<EnvManifest> = environmentCoordinator.envManifest
    val envInstallProgress: StateFlow<EnvInstallProgress> = environmentCoordinator.envInstallProgress
    val runtimeStatus: StateFlow<RuntimeUiStatus> = runtimeSessionCoordinator.runtimeStatus
    val rootAuthState: StateFlow<RootAuthState> = rootAuthManager.state

    fun onSettingsHomeEntered() {
        environmentCoordinator.refreshManifestOnly()
    }

    fun onModelSettingsEntered() {
        runtimeSessionCoordinator.refreshApiKeyMask()
    }

    fun setThemeMode(mode: AppThemeMode) {
        updateSettings { it.copy(themeMode = mode) }
    }

    fun setLocaleMode(mode: AppLocaleMode) {
        updateSettings { it.copy(localeMode = mode) }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        settingsStore.update(transform)
    }

    fun saveApiKey(
        key: String,
        autoStartRuntime: Boolean = true,
        onResult: (Boolean) -> Unit = {}
    ) {
        runtimeSessionCoordinator.saveApiKey(
            key = key,
            autoStartRuntime = autoStartRuntime,
            onResult = onResult
        )
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
        statusBannerBus.showLocalized(
            "接口设置已保存，重启运行环境后生效",
            "Endpoint settings saved. Restart the runtime to apply.",
            BannerAction.RESTART_RUNTIME
        )
    }

    fun setPermissionTier(tier: PermissionTier, notify: Boolean = true) {
        if (settingsStore.current().permissionMode == tier) return
        updateSettings { it.copy(permissionMode = tier) }
        if (notify && runtimeSessionCoordinator.isRuntimeUsable()) {
            statusBannerBus.showLocalized(
                "执行权限已更改，重启运行环境后生效",
                "Permission tier changed. Restart the runtime to apply.",
                BannerAction.RESTART_RUNTIME
            )
        }
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
            statusBannerBus.showLocalized(
                "工作区已保存，重启运行环境后生效",
                "Workspace saved. Restart the runtime to apply.",
                BannerAction.RESTART_RUNTIME
            )
        } else {
            statusBannerBus.showLocalized("已保存", "Saved")
        }
    }

    fun restartRuntime() {
        runtimeSessionCoordinator.restartRuntime()
    }

    fun applyPhantomProcessFixViaRoot() {
        viewModelScope.launch(Dispatchers.IO) {
            val ttl = settingsStore.current().rootSessionTtlMinutes
            if (!rootAuthManager.isAuthorized(ttl)) {
                statusBannerBus.showLocalized(
                    "请先授权 Root",
                    "Authorize Root first before applying Phantom Process fix."
                )
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
                statusBannerBus.showLocalized("策略拒绝了该命令", "Policy rejected phantom process fix.")
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
                    statusBannerBus.showLocalized(
                        if (exec.exitCode == 0) {
                            "已解除幽灵进程数量限制"
                        } else {
                            "执行失败（exit=${exec.exitCode}）：${exec.stderr.take(120)}"
                        },
                        if (exec.exitCode == 0) {
                            "Phantom process limit disabled via Root."
                        } else {
                            "Phantom process fix exit=${exec.exitCode}: ${exec.stderr.take(120)}"
                        }
                    )
                },
                onFailure = { err ->
                    statusBannerBus.showLocalized(
                        "执行失败：${err.message}",
                        "Phantom process fix failed: ${err.message}"
                    )
                }
            )
        }
    }

    fun copyToClipboard(label: String, text: String) {
        clipboardHelper.copy(label, text)
    }

    class Factory(
        private val settingsStore: SettingsStore,
        private val runtimeSessionCoordinator: RuntimeSessionCoordinator,
        private val environmentCoordinator: EnvironmentCoordinator,
        private val rootAuthManager: RootAuthManager,
        private val auditLogger: AuditLogger,
        private val clipboardHelper: ClipboardHelper,
        private val statusBannerBus: StatusBannerBus
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(SettingsViewModel::class.java))
            return SettingsViewModel(
                settingsStore = settingsStore,
                runtimeSessionCoordinator = runtimeSessionCoordinator,
                environmentCoordinator = environmentCoordinator,
                rootAuthManager = rootAuthManager,
                auditLogger = auditLogger,
                clipboardHelper = clipboardHelper,
                statusBannerBus = statusBannerBus
            ) as T
        }
    }
}
