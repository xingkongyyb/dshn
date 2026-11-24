package dev.dshn.feature.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import dev.dshn.app.data.AppSettings
import dev.dshn.app.data.BannerAction
import dev.dshn.app.data.ClipboardHelper
import dev.dshn.app.data.PermissionTier
import dev.dshn.app.data.SettingsStore
import dev.dshn.app.data.StatusBannerBus
import dev.dshn.app.runtime.BundledEnvManifest
import dev.dshn.app.runtime.EnvInstallProgress
import dev.dshn.app.runtime.EnvManifest
import dev.dshn.app.runtime.EnvironmentCoordinator
import dev.dshn.app.runtime.RuntimeSessionCoordinator
import kotlinx.coroutines.flow.StateFlow

class SetupViewModel(
    private val settingsStore: SettingsStore,
    private val environmentCoordinator: EnvironmentCoordinator,
    private val runtimeSessionCoordinator: RuntimeSessionCoordinator,
    private val clipboardHelper: ClipboardHelper,
    private val statusBannerBus: StatusBannerBus
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsStore.settings
    val hasApiKey: StateFlow<Boolean> = settingsStore.hasApiKey
    val apiKeyMasked: StateFlow<String?> = runtimeSessionCoordinator.apiKeyMasked
    val envManifest: StateFlow<EnvManifest> = environmentCoordinator.envManifest
    val bundledEnvManifest: StateFlow<BundledEnvManifest?> = environmentCoordinator.bundledEnvManifest
    val envInstallProgress: StateFlow<EnvInstallProgress> = environmentCoordinator.envInstallProgress
    val envChecking: StateFlow<Boolean> = environmentCoordinator.envChecking

    fun initialWizardStep(reopened: Boolean, totalSteps: Int): Int =
        if (reopened) 0 else settingsStore.current().setupWizardStep.coerceIn(0, (totalSteps - 1).coerceAtLeast(0))

    fun onReopenedEntered() {
        environmentCoordinator.refreshEnvironment()
        runtimeSessionCoordinator.refreshApiKeyMask()
    }

    fun recordSetupWizardStep(stepIndex: Int, reopened: Boolean) {
        if (reopened) return
        val cur = settingsStore.current()
        if (cur.onboardingCompleted) return
        val started = cur.setupWizardStarted || stepIndex > 0
        if (cur.setupWizardStarted != started || cur.setupWizardStep != stepIndex) {
            settingsStore.update { it.copy(setupWizardStarted = started, setupWizardStep = stepIndex) }
        }
    }

    fun refreshEnvironment() {
        environmentCoordinator.refreshEnvironment()
    }

    fun refreshApiKeyMask() {
        runtimeSessionCoordinator.refreshApiKeyMask()
    }

    fun installBundledEnvironment(reinstall: Boolean = false) {
        environmentCoordinator.installBundledEnvironment(reinstall = reinstall)
    }

    fun saveApiKey(key: String, onResult: (Boolean) -> Unit = {}) {
        runtimeSessionCoordinator.saveApiKey(key = key, autoStartRuntime = false, onResult = onResult)
    }

    fun setPermissionTier(tier: PermissionTier) {
        if (settingsStore.current().permissionMode == tier) return
        settingsStore.update { it.copy(permissionMode = tier) }
        if (runtimeSessionCoordinator.isRuntimeUsable()) {
            statusBannerBus.showLocalized(
                "执行权限已更改，重启运行环境后生效",
                "Permission tier changed. Restart the runtime to apply.",
                BannerAction.RESTART_RUNTIME
            )
        }
    }

    fun completeSetup() {
        settingsStore.update {
            it.copy(
                onboardingCompleted = true,
                setupWizardStarted = false,
                setupWizardStep = 0
            )
        }
        runtimeSessionCoordinator.startRuntimeAfterSetup()
    }

    fun copyToClipboard(label: String, text: String) {
        clipboardHelper.copy(label, text)
    }

    class Factory(
        private val settingsStore: SettingsStore,
        private val environmentCoordinator: EnvironmentCoordinator,
        private val runtimeSessionCoordinator: RuntimeSessionCoordinator,
        private val clipboardHelper: ClipboardHelper,
        private val statusBannerBus: StatusBannerBus
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(SetupViewModel::class.java))
            return SetupViewModel(
                settingsStore = settingsStore,
                environmentCoordinator = environmentCoordinator,
                runtimeSessionCoordinator = runtimeSessionCoordinator,
                clipboardHelper = clipboardHelper,
                statusBannerBus = statusBannerBus
            ) as T
        }
    }
}
