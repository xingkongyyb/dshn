package dev.dshn.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import dev.dshn.app.acp.AcpConfigOption
import dev.dshn.app.data.AppSettings
import dev.dshn.app.data.ClipboardHelper
import dev.dshn.app.data.SettingsStore
import dev.dshn.app.runtime.EnvInstallProgress
import dev.dshn.app.runtime.EnvManifest
import dev.dshn.app.runtime.EnvironmentCoordinator
import dev.dshn.app.runtime.RuntimeSessionCoordinator
import dev.dshn.app.runtime.RuntimeUiStatus
import dev.dshn.app.runtime.SessionSummaryItem
import dev.dshn.app.runtime.SlashCommandDescriptor
import dev.dshn.app.runtime.UiChatMessage
import kotlinx.coroutines.flow.StateFlow

class ChatViewModel(
    settingsStore: SettingsStore,
    private val runtimeSessionCoordinator: RuntimeSessionCoordinator,
    environmentCoordinator: EnvironmentCoordinator,
    private val clipboardHelper: ClipboardHelper
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsStore.settings
    val hasApiKey: StateFlow<Boolean> = settingsStore.hasApiKey
    val runtimeStatus: StateFlow<RuntimeUiStatus> = runtimeSessionCoordinator.runtimeStatus
    val messages: StateFlow<List<UiChatMessage>> = runtimeSessionCoordinator.messages
    val activeSessionId: StateFlow<String?> = runtimeSessionCoordinator.activeSessionId
    val activeSessionReadOnly: StateFlow<Boolean> = runtimeSessionCoordinator.activeSessionReadOnly
    val sessionAttached: StateFlow<Boolean> = runtimeSessionCoordinator.sessionAttached
    val activeCwd: StateFlow<String> = runtimeSessionCoordinator.activeCwd
    val streaming: StateFlow<Boolean> = runtimeSessionCoordinator.streaming
    val sessionBusy: StateFlow<Boolean> = runtimeSessionCoordinator.sessionBusy
    val configOptions: StateFlow<List<AcpConfigOption>> = runtimeSessionCoordinator.configOptions
    val usageUsed: StateFlow<Long> = runtimeSessionCoordinator.usageUsed
    val usageSize: StateFlow<Long> = runtimeSessionCoordinator.usageSize
    val slashCommands: StateFlow<List<SlashCommandDescriptor>> = runtimeSessionCoordinator.slashCommands
    val interruptedTurnBanner: StateFlow<String?> = runtimeSessionCoordinator.interruptedTurnBanner
    val sessions: StateFlow<List<SessionSummaryItem>> = runtimeSessionCoordinator.sessions
    val sessionsLoading: StateFlow<Boolean> = runtimeSessionCoordinator.sessionsLoading
    val sessionsError: StateFlow<String?> = runtimeSessionCoordinator.sessionsError
    val sessionsAwaitingInput: StateFlow<Set<String>> = runtimeSessionCoordinator.sessionsAwaitingInput
    val envManifest: StateFlow<EnvManifest> = environmentCoordinator.envManifest
    val envInstallProgress: StateFlow<EnvInstallProgress> = environmentCoordinator.envInstallProgress

    fun refreshSessions() = runtimeSessionCoordinator.refreshSessions()

    fun startNewChat(cwd: String? = null) = runtimeSessionCoordinator.startNewChat(cwd)

    fun switchToSession(item: SessionSummaryItem) = runtimeSessionCoordinator.switchToSession(item)

    fun closeSession(sessionId: String) = runtimeSessionCoordinator.closeSession(sessionId)

    fun sendPromptOrCommand(input: String): Boolean = runtimeSessionCoordinator.sendPromptOrCommand(input)

    fun retryLastPrompt() = runtimeSessionCoordinator.retryLastPrompt()

    fun cancelActivePrompt() = runtimeSessionCoordinator.cancelActivePrompt()

    fun setConfigOption(configId: String, value: String) = runtimeSessionCoordinator.setConfigOption(configId, value)

    fun startRuntimeAndAttach() = runtimeSessionCoordinator.startRuntimeAndAttach()

    fun restartRuntime() = runtimeSessionCoordinator.restartRuntime()

    fun copyToClipboard(label: String, text: String) = clipboardHelper.copy(label, text)

    class Factory(
        private val settingsStore: SettingsStore,
        private val runtimeSessionCoordinator: RuntimeSessionCoordinator,
        private val environmentCoordinator: EnvironmentCoordinator,
        private val clipboardHelper: ClipboardHelper
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ChatViewModel::class.java))
            return ChatViewModel(
                settingsStore = settingsStore,
                runtimeSessionCoordinator = runtimeSessionCoordinator,
                environmentCoordinator = environmentCoordinator,
                clipboardHelper = clipboardHelper
            ) as T
        }
    }
}
