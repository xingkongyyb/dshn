package dev.dshn.feature.chat

import androidx.compose.ui.unit.Dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable

@Serializable
data object ChatRoute

fun NavGraphBuilder.chatScreen(
    factory: ViewModelProvider.Factory,
    onComposerHeight: (Dp) -> Unit,
    onOpenPlugins: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenDeviceBridge: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenModelSettings: () -> Unit,
    onOpenEnvironmentSettings: () -> Unit,
    onOpenDiagnostics: () -> Unit
) {
    composable<ChatRoute> { entry ->
        val vm: ChatViewModel = viewModel(viewModelStoreOwner = entry, factory = factory)
        ChatScreen(
            vm = vm,
            onOpenPlugins = onOpenPlugins,
            onOpenTerminal = onOpenTerminal,
            onOpenDeviceBridge = onOpenDeviceBridge,
            onOpenSettings = onOpenSettings,
            onOpenModelSettings = onOpenModelSettings,
            onOpenEnvironmentSettings = onOpenEnvironmentSettings,
            onOpenDiagnostics = onOpenDiagnostics,
            onComposerHeight = onComposerHeight
        )
    }
}
