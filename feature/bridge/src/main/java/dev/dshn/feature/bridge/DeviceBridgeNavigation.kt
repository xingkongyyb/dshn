package dev.dshn.feature.bridge

import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable

@Serializable
data object DeviceBridgeRoute

@Serializable
data object DeviceLabRoute

fun NavGraphBuilder.deviceBridgeScreens(
    factory: ViewModelProvider.Factory,
    navController: NavController,
    onBack: () -> Unit,
    onOpenLab: () -> Unit
) {
    composable<DeviceBridgeRoute> { entry ->
        val vm: DeviceBridgeViewModel = viewModel(
            viewModelStoreOwner = entry,
            factory = factory
        )
        DeviceBridgeScreen(
            vm = vm,
            onBack = onBack,
            onOpenLab = onOpenLab
        )
    }

    composable<DeviceLabRoute> { entry ->
        val parentOwner = remember(entry) {
            runCatching { navController.getBackStackEntry<DeviceBridgeRoute>() }.getOrDefault(entry)
        }
        val vm: DeviceBridgeViewModel = viewModel(
            viewModelStoreOwner = parentOwner,
            factory = factory
        )
        DeviceLabScreen(
            vm = vm,
            onBack = onBack
        )
    }
}
