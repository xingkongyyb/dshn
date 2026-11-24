package dev.dshn.feature.settings

import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.navigation
import kotlinx.serialization.Serializable

@Serializable
data object SettingsGraph

@Serializable
data object SettingsHomeRoute

@Serializable
data object SettingsModelRoute

@Serializable
data object SettingsPermissionRoute

@Serializable
data object SettingsBackgroundRoute

@Serializable
data object SettingsEnvironmentRoute

@Serializable
data object SettingsDiagnosticsRoute

@Serializable
data object AboutRoute

fun NavGraphBuilder.settingsGraph(
    navController: NavController,
    settingsFactory: ViewModelProvider.Factory,
    envDiagFactory: ViewModelProvider.Factory,
    onBack: () -> Unit,
    onOpenDeviceBridge: () -> Unit,
    onOpenTerminal: () -> Unit,
    onReopenSetup: () -> Unit
) {
    navigation<SettingsGraph>(startDestination = SettingsHomeRoute) {
        composable<SettingsHomeRoute> { entry ->
            val graphOwner = remember(entry) {
                runCatching { navController.getBackStackEntry<SettingsGraph>() }.getOrDefault(entry)
            }
            val vm: SettingsViewModel = viewModel(
                viewModelStoreOwner = graphOwner,
                factory = settingsFactory
            )
            SettingsHomeScreen(
                vm = vm,
                onBack = onBack,
                onOpenModel = { navController.navigate(SettingsModelRoute) { launchSingleTop = true } },
                onOpenPermission = { navController.navigate(SettingsPermissionRoute) { launchSingleTop = true } },
                onOpenDeviceBridge = onOpenDeviceBridge,
                onOpenTerminal = onOpenTerminal,
                onOpenBackground = { navController.navigate(SettingsBackgroundRoute) { launchSingleTop = true } },
                onOpenEnvironment = { navController.navigate(SettingsEnvironmentRoute) { launchSingleTop = true } },
                onOpenDiagnostics = { navController.navigate(SettingsDiagnosticsRoute) { launchSingleTop = true } },
                onReopenSetup = onReopenSetup,
                onOpenAbout = { navController.navigate(AboutRoute) { launchSingleTop = true } }
            )
        }

        composable<SettingsModelRoute> { entry ->
            val graphOwner = remember(entry) {
                runCatching { navController.getBackStackEntry<SettingsGraph>() }.getOrDefault(entry)
            }
            val vm: SettingsViewModel = viewModel(
                viewModelStoreOwner = graphOwner,
                factory = settingsFactory
            )
            ModelSettingsScreen(vm = vm, onBack = onBack)
        }

        composable<SettingsPermissionRoute> { entry ->
            val graphOwner = remember(entry) {
                runCatching { navController.getBackStackEntry<SettingsGraph>() }.getOrDefault(entry)
            }
            val vm: SettingsViewModel = viewModel(
                viewModelStoreOwner = graphOwner,
                factory = settingsFactory
            )
            PermissionSettingsScreen(vm = vm, onBack = onBack)
        }

        composable<SettingsBackgroundRoute> { entry ->
            val graphOwner = remember(entry) {
                runCatching { navController.getBackStackEntry<SettingsGraph>() }.getOrDefault(entry)
            }
            val vm: SettingsViewModel = viewModel(
                viewModelStoreOwner = graphOwner,
                factory = settingsFactory
            )
            BackgroundSettingsScreen(vm = vm, onBack = onBack)
        }

        composable<SettingsEnvironmentRoute> { entry ->
            val graphOwner = remember(entry) {
                runCatching { navController.getBackStackEntry<SettingsGraph>() }.getOrDefault(entry)
            }
            val vm: EnvironmentDiagnosticsViewModel = viewModel(
                viewModelStoreOwner = graphOwner,
                factory = envDiagFactory
            )
            EnvironmentSettingsScreen(vm = vm, onBack = onBack)
        }

        composable<SettingsDiagnosticsRoute> { entry ->
            val graphOwner = remember(entry) {
                runCatching { navController.getBackStackEntry<SettingsGraph>() }.getOrDefault(entry)
            }
            val vm: EnvironmentDiagnosticsViewModel = viewModel(
                viewModelStoreOwner = graphOwner,
                factory = envDiagFactory
            )
            DiagnosticsScreen(vm = vm, onBack = onBack)
        }

        composable<AboutRoute> { entry ->
            val graphOwner = remember(entry) {
                runCatching { navController.getBackStackEntry<SettingsGraph>() }.getOrDefault(entry)
            }
            val vm: SettingsViewModel = viewModel(
                viewModelStoreOwner = graphOwner,
                factory = settingsFactory
            )
            AboutScreen(vm = vm, onBack = onBack)
        }
    }
}
