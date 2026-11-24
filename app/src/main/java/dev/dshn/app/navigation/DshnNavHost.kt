package dev.dshn.app.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.ViewModelStoreOwner
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import dev.dshn.app.di.AppContainer
import dev.dshn.feature.bridge.DeviceBridgeRoute
import dev.dshn.feature.bridge.DeviceLabRoute
import dev.dshn.feature.bridge.deviceBridgeScreens
import dev.dshn.feature.chat.ChatRoute
import dev.dshn.feature.chat.chatScreen
import dev.dshn.feature.settings.SettingsDiagnosticsRoute
import dev.dshn.feature.settings.SettingsEnvironmentRoute
import dev.dshn.feature.settings.SettingsGraph
import dev.dshn.feature.settings.SettingsModelRoute
import dev.dshn.feature.settings.settingsGraph
import dev.dshn.feature.setup.SetupRoute
import dev.dshn.feature.setup.setupScreen
import dev.dshn.feature.terminal.TerminalRoute
import dev.dshn.feature.terminal.terminalScreen

@Composable
fun DshnNavHost(
    navController: NavHostController,
    appContainer: AppContainer,
    activityOwner: ViewModelStoreOwner,
    startDestination: Any,
    onComposerHeight: (Dp) -> Unit,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
        enterTransition = { slideInHorizontally { w -> w / 5 } + fadeIn() },
        exitTransition = { slideOutHorizontally { w -> -w / 8 } + fadeOut() },
        popEnterTransition = { slideInHorizontally { w -> -w / 5 } + fadeIn() },
        popExitTransition = { slideOutHorizontally { w -> w / 5 } + fadeOut() }
    ) {
        setupScreen(
            factory = appContainer.setupViewModelFactory,
            onCompleted = { reopened ->
                if (reopened) {
                    navController.popBackStack<SetupRoute>(inclusive = true)
                } else {
                    navController.navigate(ChatRoute) {
                        popUpTo<SetupRoute> { inclusive = true }
                        launchSingleTop = true
                    }
                }
            },
            onCancelReopened = {
                navController.popBackStack<SetupRoute>(inclusive = true)
            }
        )

        chatScreen(
            factory = appContainer.chatViewModelFactory,
            onComposerHeight = onComposerHeight,
            onOpenTerminal = { navController.navigate(TerminalRoute) { launchSingleTop = true } },
            onOpenDeviceBridge = { navController.navigate(DeviceBridgeRoute) { launchSingleTop = true } },
            onOpenSettings = { navController.navigate(SettingsGraph) { launchSingleTop = true } },
            onOpenModelSettings = { navController.navigate(SettingsModelRoute) { launchSingleTop = true } },
            onOpenEnvironmentSettings = { navController.navigate(SettingsEnvironmentRoute) { launchSingleTop = true } },
            onOpenDiagnostics = { navController.navigate(SettingsDiagnosticsRoute) { launchSingleTop = true } }
        )

        terminalScreen(
            factory = appContainer.terminalViewModelFactory,
            ownerProvider = { activityOwner },
            onBack = { navController.popBackStack() }
        )

        deviceBridgeScreens(
            factory = appContainer.deviceBridgeViewModelFactory,
            navController = navController,
            onBack = { navController.popBackStack() },
            onOpenLab = { navController.navigate(DeviceLabRoute) { launchSingleTop = true } }
        )

        settingsGraph(
            navController = navController,
            settingsFactory = appContainer.settingsViewModelFactory,
            envDiagFactory = appContainer.envDiagnosticsViewModelFactory,
            onBack = { navController.popBackStack() },
            onOpenDeviceBridge = { navController.navigate(DeviceBridgeRoute) { launchSingleTop = true } },
            onOpenTerminal = { navController.navigate(TerminalRoute) { launchSingleTop = true } },
            onReopenSetup = { navController.navigate(SetupRoute(reopened = true)) { launchSingleTop = true } }
        )
    }
}
