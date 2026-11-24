package dev.dshn.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import dev.dshn.app.bridge.ConfirmationBroker
import dev.dshn.app.bridge.PendingBridgeConfirmation
import dev.dshn.app.data.AppSettings
import dev.dshn.app.data.SettingsStore
import dev.dshn.app.data.StatusBannerBus
import dev.dshn.app.data.StatusBannerUi
import dev.dshn.app.runtime.PendingAcpPermissionUi
import dev.dshn.app.runtime.RuntimeSessionCoordinator
import dev.dshn.app.runtime.UserQuestionAnswer
import dev.dshn.app.runtime.UserQuestionRequest
import kotlinx.coroutines.flow.StateFlow

/**
 * Shell-level ViewModel for [MainActivity], exposing theme/locale settings, global snackbar
 * banners, and process-wide agent/bridge confirmation dialogs.
 */
class AppViewModel(
    settingsStore: SettingsStore,
    private val statusBannerBus: StatusBannerBus,
    private val runtimeSessionCoordinator: RuntimeSessionCoordinator,
    private val confirmationBroker: ConfirmationBroker
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsStore.settings
    val statusBanner: StateFlow<StatusBannerUi?> = statusBannerBus.banner
    val pendingPermission: StateFlow<PendingAcpPermissionUi?> = runtimeSessionCoordinator.pendingPermission
    val pendingQuestion: StateFlow<UserQuestionRequest?> = runtimeSessionCoordinator.pendingQuestion
    val pendingBridgeConfirm: StateFlow<PendingBridgeConfirmation?> = confirmationBroker.pending

    fun resolveSessionDisplayLabel(sessionId: String?): String? =
        runtimeSessionCoordinator.resolveSessionDisplayLabel(sessionId)

    fun restartRuntime() = runtimeSessionCoordinator.restartRuntime()

    fun dismissBanner(id: Long? = null) = statusBannerBus.dismiss(id)

    fun replyAcpPermission(optionId: String?, cancelled: Boolean) =
        runtimeSessionCoordinator.replyAcpPermission(optionId, cancelled)

    fun replyUserQuestion(answers: List<UserQuestionAnswer>, cancelled: Boolean) =
        runtimeSessionCoordinator.replyUserQuestion(answers, cancelled)

    fun respondBridgeConfirmation(allowed: Boolean) {
        val id = confirmationBroker.pending.value?.id ?: return
        confirmationBroker.respond(id, allowed)
    }

    class Factory(
        private val settingsStore: SettingsStore,
        private val statusBannerBus: StatusBannerBus,
        private val runtimeSessionCoordinator: RuntimeSessionCoordinator,
        private val confirmationBroker: ConfirmationBroker
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(AppViewModel::class.java))
            return AppViewModel(
                settingsStore = settingsStore,
                statusBannerBus = statusBannerBus,
                runtimeSessionCoordinator = runtimeSessionCoordinator,
                confirmationBroker = confirmationBroker
            ) as T
        }
    }
}
