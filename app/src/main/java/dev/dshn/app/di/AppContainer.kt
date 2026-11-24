package dev.dshn.app.di

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import dev.dshn.app.AppViewModel
import dev.dshn.app.bridge.ConfirmationBroker
import dev.dshn.app.bridge.DeviceBridgeMcpServer
import dev.dshn.app.bridge.HostRootShellSession
import dev.dshn.app.bridge.RootAuthManager
import dev.dshn.app.bridge.WirelessAdbClient
import dev.dshn.app.data.AndroidClipboardHelper
import dev.dshn.app.data.ClipboardHelper
import dev.dshn.app.data.SettingsStore
import dev.dshn.app.data.StatusBannerBus
import dev.dshn.app.runtime.ContainerLauncher
import dev.dshn.app.runtime.EnvManager
import dev.dshn.app.runtime.EnvironmentCoordinator
import dev.dshn.app.runtime.RuntimeController
import dev.dshn.app.runtime.RuntimeSessionCoordinator
import dev.dshn.app.runtime.TerminalPtySession
import dev.dshn.app.security.AuditLogger
import dev.dshn.feature.bridge.DeviceBridgeViewModel
import dev.dshn.feature.chat.ChatViewModel
import dev.dshn.feature.settings.EnvironmentDiagnosticsViewModel
import dev.dshn.feature.settings.SettingsViewModel
import dev.dshn.feature.setup.SetupViewModel
import dev.dshn.feature.setup.shouldRequireInitialSetup
import dev.dshn.feature.terminal.TerminalViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Explicit constructor-injection composition root for the `:app` process.
 * Instantiated lazily by [dev.dshn.app.DshnApplication] so `:runtime` process startups
 * never spin up UI coordinators or duplicate the device bridge server.
 */
class AppContainer(application: Application) {

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val containerLauncher: ContainerLauncher = ContainerLauncher(application)
    val settingsStore: SettingsStore = SettingsStore(application)
    val auditLogger: AuditLogger = AuditLogger(application.filesDir)
    val envManager: EnvManager = EnvManager(application, containerLauncher)
    val adbClient: WirelessAdbClient = WirelessAdbClient(application)
    val rootAuthManager: RootAuthManager = RootAuthManager(auditLogger)
    val confirmationBroker: ConfirmationBroker = ConfirmationBroker(application)
    val statusBannerBus: StatusBannerBus = StatusBannerBus(settingsStore)
    val clipboardHelper: ClipboardHelper = AndroidClipboardHelper(application, statusBannerBus)

    val deviceBridgeServer: DeviceBridgeMcpServer = DeviceBridgeMcpServer(
        context = application,
        scope = appScope,
        settingsStore = settingsStore,
        auditLogger = auditLogger,
        confirmationBroker = confirmationBroker,
        adbClient = adbClient,
        rootAuthManager = rootAuthManager
    )

    val runtimeController: RuntimeController = RuntimeController(application, appScope)
    val terminalPty: TerminalPtySession = TerminalPtySession(containerLauncher, appScope)
    val hostRootShell: HostRootShellSession = HostRootShellSession(rootAuthManager, appScope)

    val environmentCoordinator: EnvironmentCoordinator = EnvironmentCoordinator(
        envManager = envManager,
        settingsStore = settingsStore,
        runtimeController = runtimeController,
        terminalPty = terminalPty,
        statusBannerBus = statusBannerBus,
        scope = appScope
    )

    val runtimeSessionCoordinator: RuntimeSessionCoordinator = RuntimeSessionCoordinator(
        runtimeController = runtimeController,
        settingsStore = settingsStore,
        environmentCoordinator = environmentCoordinator,
        statusBannerBus = statusBannerBus,
        scope = appScope
    )

    val initialSetupRequired: Boolean

    val appViewModelFactory: ViewModelProvider.Factory = AppViewModel.Factory(
        settingsStore = settingsStore,
        statusBannerBus = statusBannerBus,
        runtimeSessionCoordinator = runtimeSessionCoordinator,
        confirmationBroker = confirmationBroker
    )

    val setupViewModelFactory: ViewModelProvider.Factory = SetupViewModel.Factory(
        settingsStore = settingsStore,
        environmentCoordinator = environmentCoordinator,
        runtimeSessionCoordinator = runtimeSessionCoordinator,
        clipboardHelper = clipboardHelper,
        statusBannerBus = statusBannerBus
    )

    val chatViewModelFactory: ViewModelProvider.Factory = ChatViewModel.Factory(
        settingsStore = settingsStore,
        runtimeSessionCoordinator = runtimeSessionCoordinator,
        environmentCoordinator = environmentCoordinator,
        clipboardHelper = clipboardHelper
    )

    val terminalViewModelFactory: ViewModelProvider.Factory = TerminalViewModel.Factory(
        settingsStore = settingsStore,
        environmentCoordinator = environmentCoordinator,
        runtimeSessionCoordinator = runtimeSessionCoordinator,
        terminalPty = terminalPty,
        hostRootShell = hostRootShell,
        rootAuthManager = rootAuthManager,
        statusBannerBus = statusBannerBus
    )

    val deviceBridgeViewModelFactory: ViewModelProvider.Factory = DeviceBridgeViewModel.Factory(
        settingsStore = settingsStore,
        deviceBridgeServer = deviceBridgeServer,
        adbClient = adbClient,
        rootAuthManager = rootAuthManager,
        auditLogger = auditLogger,
        clipboardHelper = clipboardHelper,
        statusBannerBus = statusBannerBus
    )

    val settingsViewModelFactory: ViewModelProvider.Factory = SettingsViewModel.Factory(
        settingsStore = settingsStore,
        runtimeSessionCoordinator = runtimeSessionCoordinator,
        environmentCoordinator = environmentCoordinator,
        rootAuthManager = rootAuthManager,
        auditLogger = auditLogger,
        clipboardHelper = clipboardHelper,
        statusBannerBus = statusBannerBus
    )

    val envDiagnosticsViewModelFactory: ViewModelProvider.Factory = EnvironmentDiagnosticsViewModel.Factory(
        settingsStore = settingsStore,
        envManager = envManager,
        environmentCoordinator = environmentCoordinator,
        runtimeSessionCoordinator = runtimeSessionCoordinator,
        rootAuthManager = rootAuthManager,
        auditLogger = auditLogger,
        clipboardHelper = clipboardHelper,
        statusBannerBus = statusBannerBus,
        cacheDir = application.cacheDir
    )

    init {
        val (needSetup, markDone) = shouldRequireInitialSetup(
            settings = settingsStore.current(),
            hasApiKey = settingsStore.hasApiKey.value,
            rootfsReady = envManager.isRootfsReady()
        )
        if (markDone) {
            settingsStore.update { it.copy(onboardingCompleted = true) }
        }
        initialSetupRequired = needSetup

        deviceBridgeServer.start()
        runtimeController.bind()

        appScope.launch(Dispatchers.IO) {
            runCatching { rootAuthManager.probeEnvironment(settingsStore.current().customSuBinaryPath) }
            runtimeSessionCoordinator.refreshApiKeyMaskSync()
            environmentCoordinator.initializeOnStartup(setupRequired = initialSetupRequired)
        }
    }
}
