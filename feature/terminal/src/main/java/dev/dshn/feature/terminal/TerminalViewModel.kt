package dev.dshn.feature.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.dshn.app.bridge.HostRootShellSession
import dev.dshn.app.bridge.RootAuthManager
import dev.dshn.app.bridge.RootAuthState
import dev.dshn.app.bridge.RootAuthStatus
import dev.dshn.app.data.BannerAction
import dev.dshn.app.data.SettingsStore
import dev.dshn.app.data.StatusBannerBus
import dev.dshn.app.runtime.EnvironmentCoordinator
import dev.dshn.app.runtime.RuntimeSessionCoordinator
import dev.dshn.app.runtime.TerminalPtySession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class TerminalShellMode { CONTAINER_UBUNTU, HOST_ROOT_SU }

class TerminalViewModel(
    private val settingsStore: SettingsStore,
    private val environmentCoordinator: EnvironmentCoordinator,
    private val runtimeSessionCoordinator: RuntimeSessionCoordinator,
    private val terminalPty: TerminalPtySession,
    private val hostRootShell: HostRootShellSession,
    private val rootAuthManager: RootAuthManager,
    private val statusBannerBus: StatusBannerBus
) : ViewModel() {

    private val terminalBuffer = AnsiTerminalBuffer()

    private val _terminalMode = MutableStateFlow(TerminalShellMode.CONTAINER_UBUNTU)
    val terminalMode: StateFlow<TerminalShellMode> = _terminalMode.asStateFlow()

    private val _terminalOutput = MutableStateFlow("")
    val terminalOutput: StateFlow<String> = _terminalOutput.asStateFlow()

    val terminalRunning: StateFlow<Boolean> = terminalPty.running
    val hostRootTerminalRunning: StateFlow<Boolean> = hostRootShell.running
    val rootAuthState: StateFlow<RootAuthState> = rootAuthManager.state

    init {
        viewModelScope.launch {
            terminalPty.output.collect { chunk ->
                if (_terminalMode.value == TerminalShellMode.CONTAINER_UBUNTU) {
                    terminalBuffer.append(chunk)
                    _terminalOutput.value = terminalBuffer.renderText()
                }
            }
        }
        viewModelScope.launch {
            hostRootShell.output.collect { chunk ->
                if (_terminalMode.value == TerminalShellMode.HOST_ROOT_SU) {
                    terminalBuffer.append(chunk)
                    _terminalOutput.value = terminalBuffer.renderText()
                }
            }
        }
        viewModelScope.launch {
            rootAuthManager.state.collect { st ->
                if (st.status != RootAuthStatus.AUTHORIZED && _terminalMode.value == TerminalShellMode.HOST_ROOT_SU) {
                    hostRootShell.stop()
                    _terminalMode.value = TerminalShellMode.CONTAINER_UBUNTU
                    clearTerminal()
                }
            }
        }
    }

    fun setTerminalMode(mode: TerminalShellMode) {
        if (mode == TerminalShellMode.HOST_ROOT_SU &&
            !rootAuthManager.isAuthorized(settingsStore.current().rootSessionTtlMinutes)
        ) {
            statusBannerBus.showLocalized(
                "请先在「设备桥与 Root」中授权 Root",
                "Authorize Root in Device bridge first"
            )
            return
        }
        val prev = _terminalMode.value
        if (prev != mode) {
            when (prev) {
                TerminalShellMode.CONTAINER_UBUNTU -> terminalPty.stop()
                TerminalShellMode.HOST_ROOT_SU -> hostRootShell.stop()
            }
            _terminalMode.value = mode
            clearTerminal()
        }
    }

    fun startTerminal(cwd: String = runtimeSessionCoordinator.activeCwd.value) {
        when (_terminalMode.value) {
            TerminalShellMode.CONTAINER_UBUNTU -> {
                if (environmentCoordinator.isInstallBusy()) {
                    environmentCoordinator.showInstallingBanner()
                    return
                }
                if (!environmentCoordinator.isRootfsReady()) {
                    statusBannerBus.showLocalized(
                        "运行环境尚未准备好，无法启动终端",
                        "Runtime environment not ready",
                        BannerAction.OPEN_ENVIRONMENT
                    )
                    return
                }
                hostRootShell.stop()
                terminalPty.start(cols = 80, rows = 26, cwd = cwd)
            }
            TerminalShellMode.HOST_ROOT_SU -> {
                terminalPty.stop()
                val res = hostRootShell.start(settingsStore.current().rootSessionTtlMinutes)
                res.onFailure {
                    statusBannerBus.showLocalized(
                        "Root 终端启动失败：${it.message}",
                        "Host Root shell failed: ${it.message}"
                    )
                }
            }
        }
    }

    fun sendTerminalInput(text: String) {
        when (_terminalMode.value) {
            TerminalShellMode.CONTAINER_UBUNTU -> terminalPty.writeInput(text)
            TerminalShellMode.HOST_ROOT_SU -> {
                val normalized = text.replace("\r", "\n")
                hostRootShell.writeInput(normalized)
            }
        }
    }

    fun clearTerminal() {
        terminalBuffer.clear()
        _terminalOutput.value = ""
    }

    override fun onCleared() {
        super.onCleared()
        // Activity-scoped ViewModel: stop interactive container PTY and uid=0 host root shell
        // when MainActivity actually finishes so a root shell never lingers in the background.
        terminalPty.stop()
        hostRootShell.stop()
    }

    class Factory(
        private val settingsStore: SettingsStore,
        private val environmentCoordinator: EnvironmentCoordinator,
        private val runtimeSessionCoordinator: RuntimeSessionCoordinator,
        private val terminalPty: TerminalPtySession,
        private val hostRootShell: HostRootShellSession,
        private val rootAuthManager: RootAuthManager,
        private val statusBannerBus: StatusBannerBus
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(TerminalViewModel::class.java))
            return TerminalViewModel(
                settingsStore = settingsStore,
                environmentCoordinator = environmentCoordinator,
                runtimeSessionCoordinator = runtimeSessionCoordinator,
                terminalPty = terminalPty,
                hostRootShell = hostRootShell,
                rootAuthManager = rootAuthManager,
                statusBannerBus = statusBannerBus
            ) as T
        }
    }
}
