package dev.dshn.app.runtime

import dev.dshn.app.bridge.RootAuthManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.OutputStream

/**
 * Interactive Android host root shell (`su`) controller for the Terminal tab.
 * Requires active user-granted root authorization in [RootAuthManager].
 */
class HostRootShellSession(
    private val rootAuthManager: RootAuthManager,
    private val scope: CoroutineScope
) {
    private var proc: Process? = null
    private var stdin: OutputStream? = null
    private var stdoutJob: Job? = null
    private var stderrJob: Job? = null

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _output = MutableSharedFlow<String>(extraBufferCapacity = 256)
    val output: SharedFlow<String> = _output

    fun start(ttlMinutes: Int = 0): Result<Unit> = runCatching {
        stop()
        val p = rootAuthManager.startInteractiveRootShell(ttlMinutes)
        proc = p
        stdin = p.outputStream
        _running.value = true
        _output.tryEmit("\u001B[31m[DSHN HOST ROOT SHELL (uid=0)]\u001B[0m Connected via su.\r\n")

        stdoutJob = scope.launch(Dispatchers.IO) {
            try {
                val buf = ByteArray(2048)
                while (true) {
                    val n = p.inputStream.read(buf)
                    if (n < 0) break
                    if (n > 0) {
                        _output.tryEmit(String(buf, 0, n, Charsets.UTF_8))
                    }
                }
            } catch (_: Exception) {
            } finally {
                _running.value = false
                _output.tryEmit("\r\n\u001B[33m[host root shell exited]\u001B[0m\r\n")
            }
        }

        stderrJob = scope.launch(Dispatchers.IO) {
            runCatching {
                val buf = ByteArray(2048)
                while (true) {
                    val n = p.errorStream.read(buf)
                    if (n < 0) break
                    if (n > 0) {
                        _output.tryEmit("\u001B[33m${String(buf, 0, n, Charsets.UTF_8)}\u001B[0m")
                    }
                }
            }
        }

        // Probe identity immediately so the user sees uid=0 confirmation
        writeInput("id\n")
    }

    fun writeInput(text: String) {
        if (!_running.value) return
        val out = stdin ?: return
        scope.launch(Dispatchers.IO) {
            runCatching {
                synchronized(out) {
                    out.write(text.toByteArray(Charsets.UTF_8))
                    out.flush()
                }
            }
        }
    }

    fun stop() {
        stdoutJob?.cancel()
        stdoutJob = null
        stderrJob?.cancel()
        stderrJob = null
        stdin?.let { out ->
            runCatching {
                out.write("exit\n".toByteArray(Charsets.UTF_8))
                out.flush()
                out.close()
            }
        }
        stdin = null
        runCatching { proc?.destroyForcibly() }
        proc = null
        _running.value = false
    }
}
