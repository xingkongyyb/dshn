package dev.dshn.feature.setup

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable

@Serializable
data class SetupRoute(val reopened: Boolean = false)

fun NavGraphBuilder.setupScreen(
    factory: ViewModelProvider.Factory,
    onCompleted: (reopened: Boolean) -> Unit,
    onCancelReopened: () -> Unit
) {
    composable<SetupRoute> { entry ->
        val route = entry.toRoute<SetupRoute>()
        val vm: SetupViewModel = viewModel(viewModelStoreOwner = entry, factory = factory)
        SetupScreen(
            vm = vm,
            reopened = route.reopened,
            onCompleted = { onCompleted(route.reopened) },
            onCancelReopened = onCancelReopened
        )
    }
}
