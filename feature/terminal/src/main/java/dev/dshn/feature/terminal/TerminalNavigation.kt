package dev.dshn.feature.terminal

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable

@Serializable
data object TerminalRoute

fun NavGraphBuilder.terminalScreen(
    factory: ViewModelProvider.Factory,
    ownerProvider: () -> ViewModelStoreOwner? = { null },
    onBack: () -> Unit
) {
    composable<TerminalRoute> { entry ->
        val vm: TerminalViewModel = viewModel(
            viewModelStoreOwner = ownerProvider() ?: entry,
            factory = factory
        )
        TerminalScreen(
            vm = vm,
            onBack = onBack
        )
    }
}
