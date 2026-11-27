package dev.dshn.app.runtime

import dev.dshn.app.data.BannerAction
import dev.dshn.app.data.SettingsStore
import dev.dshn.app.data.StatusBannerBus
import dev.dshn.app.data.resolveIsZhLocale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Decides whether the runtime should auto-start after a bundled environment install succeeds.
 * Evaluates live [onboardingCompleted] at completion time so finishing the setup wizard while
 * extraction is still running still triggers automatic startup once extraction completes.
 */
internal fun shouldAutoStartAfterBundledInstall(
    wasRunning: Boolean,
    autoStartIfReady: Boolean,
    onboardingCompleted: Boolean,
    hasApiKey: Boolean,
    runtimeAlreadyUsable: Boolean
): Boolean = (wasRunning || autoStartIfReady || onboardingCompleted) && hasApiKey && !runtimeAlreadyUsable

/**
 * Shared coordinator managing container rootfs assets, bundled runtime installation,
 * snapshots/restores, self-tests, and DSH upgrades across Setup, Chat, Terminal, and Settings.
 */
class EnvironmentCoordinator(
    private val envManager: EnvManager,
    private val settingsStore: SettingsStore,
    private val runtimeController: RuntimeController,
    private val terminalPty: TerminalPtySession,
    private val statusBannerBus: StatusBannerBus,
    private val scope: CoroutineScope
) {
    private val envInstallRunning = AtomicBoolean(false)

    private val _envManifest = MutableStateFlow(EnvManifest())
    val envManifest: StateFlow<EnvManifest> = _envManifest.asStateFlow()

    private val _bundledEnvManifest = MutableStateFlow<BundledEnvManifest?>(null)
    val bundledEnvManifest: StateFlow<BundledEnvManifest?> = _bundledEnvManifest.asStateFlow()

    private val _envInstallProgress = MutableStateFlow(EnvInstallProgress())
    val envInstallProgress: StateFlow<EnvInstallProgress> = _envInstallProgress.asStateFlow()

    private val _envChecking = MutableStateFlow(false)
    val envChecking: StateFlow<Boolean> = _envChecking.asStateFlow()

    private val _snapshots = MutableStateFlow<List<EnvSnapshotInfo>>(emptyList())
    val snapshots: StateFlow<List<EnvSnapshotInfo>> = _snapshots.asStateFlow()

    private val _selfTestReport = MutableStateFlow<SelfTestReport?>(null)
    val selfTestReport: StateFlow<SelfTestReport?> = _selfTestReport.asStateFlow()

    private val _selfTestRunning = MutableStateFlow(false)
    val selfTestRunning: StateFlow<Boolean> = _selfTestRunning.asStateFlow()

    private val _dshUpgrading = MutableStateFlow(false)
    val dshUpgrading: StateFlow<Boolean> = _dshUpgrading.asStateFlow()

    private val _dshUpgradeReport = MutableStateFlow<DshUpgradeReport?>(null)
    val dshUpgradeReport: StateFlow<DshUpgradeReport?> = _dshUpgradeReport.asStateFlow()

    private val _runtimeReattachRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val runtimeReattachRequests: SharedFlow<Unit> = _runtimeReattachRequests.asSharedFlow()

    private fun isZh(): Boolean = resolveIsZhLocale(settingsStore.current().localeMode)
    private fun t(zh: String, en: String): String = if (isZh()) zh else en

    private fun isRuntimeUsable(): Boolean {
        val st = runtimeController.status.value.state
        return st == RuntimeLifecycleState.HEALTHY || st == RuntimeLifecycleState.DEGRADED
    }

    fun isRootfsReady(): Boolean = envManager.isRootfsReady()

    val rootfsDir: java.io.File get() = envManager.rootfs

    fun isInstallBusy(): Boolean = envInstallRunning.get() || _envInstallProgress.value.inProgress

    fun showInstallingBanner() {
        statusBannerBus.show(
            t("正在安装内置运行环境，请稍候…", "Installing bundled runtime, please wait…"),
            BannerAction.OPEN_ENVIRONMENT
        )
    }

    fun refreshManifestOnly() {
        scope.launch(Dispatchers.IO) {
            _envManifest.value = envManager.loadManifest()
        }
    }

    fun refreshEnvironment() {
        if (_envChecking.value || _envInstallProgress.value.inProgress) return
        scope.launch(Dispatchers.IO) {
            _envChecking.value = true
            runCatching { envManager.ensureRuntimeAssets() }
                .onSuccess { _envManifest.value = it }
            delay(350) // let the checklist animation read as a real check
            _envChecking.value = false
        }
    }

    suspend fun initializeOnStartup(setupRequired: Boolean) {
        val bundled = _bundledEnvManifest.value ?: envManager.loadBundledManifest().also {
            _bundledEnvManifest.value = it
        }
        val currentManifest = envManager.ensureRuntimeAssets()
        _envManifest.value = currentManifest
        scope.launch(Dispatchers.IO) {
            _snapshots.value = envManager.listSnapshots()
        }
        val needsBundledInstall = bundled != null && (
            !envManager.isRootfsReady() ||
                (currentManifest.bundledEnvVersion.isNotBlank() && currentManifest.bundledEnvVersion != bundled.bundleVersion)
            )
        if (needsBundledInstall) {
            installBundledEnvironmentInternal(reinstall = currentManifest.rootfsReady, autoStartIfReady = !setupRequired)
        } else if ((!setupRequired || settingsStore.current().onboardingCompleted) &&
            settingsStore.hasApiKey.value &&
            envManager.isRootfsReady()
        ) {
            _runtimeReattachRequests.tryEmit(Unit)
        }
    }

    fun installBundledEnvironment(reinstall: Boolean = false) {
        if (isInstallBusy()) return
        scope.launch(Dispatchers.IO) {
            val autoStart = settingsStore.current().onboardingCompleted
            installBundledEnvironmentInternal(reinstall = reinstall, autoStartIfReady = autoStart)
        }
    }

    private suspend fun installBundledEnvironmentInternal(reinstall: Boolean, autoStartIfReady: Boolean) {
        if (!envInstallRunning.compareAndSet(false, true)) return
        try {
            installBundledEnvironmentLocked(reinstall = reinstall, autoStartIfReady = autoStartIfReady)
        } finally {
            envInstallRunning.set(false)
        }
    }

    private suspend fun installBundledEnvironmentLocked(reinstall: Boolean, autoStartIfReady: Boolean) {
        val wasRunning = isRuntimeUsable()
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
            if (wasRunning) delay(600)
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
                    statusBannerBus.show(
                        t(
                            "内置运行环境已重新安装（对话与 API Key 已保留）",
                            "Bundled runtime reinstalled (chats & API key preserved)"
                        )
                    )
                }
                if (shouldAutoStartAfterBundledInstall(
                        wasRunning = wasRunning,
                        autoStartIfReady = autoStartIfReady,
                        onboardingCompleted = settingsStore.current().onboardingCompleted,
                        hasApiKey = settingsStore.hasApiKey.value,
                        runtimeAlreadyUsable = isRuntimeUsable()
                    )
                ) {
                    _runtimeReattachRequests.tryEmit(Unit)
                }
            },
            onFailure = { err ->
                val msg = err.message ?: err.javaClass.simpleName
                _envInstallProgress.value = EnvInstallProgress(inProgress = false, error = msg)
                statusBannerBus.show(
                    t("内置环境安装失败：$msg", "Bundled runtime install failed: $msg"),
                    BannerAction.OPEN_ENVIRONMENT
                )
            }
        )
    }

    fun upgradeDshToVersion(targetVersion: String = EnvManager.TARGET_DSH_VERSION) {
        if (isInstallBusy()) {
            showInstallingBanner()
            return
        }
        if (_dshUpgrading.value) return
        scope.launch(Dispatchers.IO) {
            _dshUpgrading.value = true
            val wasRunning = isRuntimeUsable()
            if (wasRunning) {
                runCatching { runtimeController.stopRuntime() }
                delay(800)
            }
            val res = envManager.upgradeDshToVersion(targetVersion)
            _envManifest.value = envManager.loadManifest()
            _snapshots.value = envManager.listSnapshots()
            res.fold(
                onSuccess = { report ->
                    _dshUpgradeReport.value = report
                    statusBannerBus.show(
                        t(
                            "DSH 已更新到 ${report.installedVersion}" + if (report.selfTestPassed) "，自检通过" else "，自检未通过",
                            "DSH upgraded ${report.previousVersion.ifEmpty { "?" }} -> ${report.installedVersion} (selfTest=${report.selfTestPassed})"
                        )
                    )
                    if (wasRunning) _runtimeReattachRequests.tryEmit(Unit)
                },
                onFailure = { err ->
                    statusBannerBus.show(
                        t("DSH 更新失败：${err.message}", "DSH upgrade failed: ${err.message}"),
                        BannerAction.OPEN_LOGS
                    )
                    if (wasRunning) _runtimeReattachRequests.tryEmit(Unit)
                }
            )
            _dshUpgrading.value = false
        }
    }

    fun createEnvSnapshot() {
        if (isInstallBusy()) {
            showInstallingBanner()
            return
        }
        scope.launch(Dispatchers.IO) {
            val res = envManager.createSnapshot("user")
            statusBannerBus.show(
                res.fold(
                    onSuccess = { t("快照已创建（包含 ${it.sessionCount} 个对话）", "Created snapshot ${it.id} (${it.sessionCount} sessions)") },
                    onFailure = { t("创建快照失败：${it.message}", "Snapshot failed: ${it.message}") }
                )
            )
            refreshSnapshots()
        }
    }

    fun restoreEnvSnapshot(snapshotId: String) {
        if (isInstallBusy()) {
            showInstallingBanner()
            return
        }
        scope.launch(Dispatchers.IO) {
            val wasRunning = isRuntimeUsable()
            if (wasRunning) runtimeController.stopRuntime()
            val res = envManager.restoreSnapshot(snapshotId)
            statusBannerBus.show(
                res.fold(
                    onSuccess = { t("已恢复快照，正在重启运行环境…", "Restored snapshot ${it.id}. Restarting runtime...") },
                    onFailure = { t("恢复被拒绝并已回滚：${it.message}", "Restore rejected/rolled back: ${it.message}") }
                )
            )
            refreshSnapshots()
            if (wasRunning) _runtimeReattachRequests.tryEmit(Unit)
        }
    }

    fun runSelfTest() {
        if (isInstallBusy()) {
            showInstallingBanner()
            return
        }
        if (_selfTestRunning.value) return
        scope.launch(Dispatchers.IO) {
            _selfTestRunning.value = true
            val report = envManager.runSelfTest()
            _selfTestReport.value = report
            _envManifest.value = envManager.loadManifest()
            _selfTestRunning.value = false
        }
    }

    fun refreshSnapshots() {
        scope.launch(Dispatchers.IO) {
            _snapshots.value = envManager.listSnapshots()
        }
    }
}
