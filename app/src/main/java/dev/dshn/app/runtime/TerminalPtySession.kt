package dev.dshn.app.runtime

import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream

/**
 * Interactive PTY session over bxroot running `/opt/dshn/dshn-pty-runner.mjs` (`node-pty`)
 * with isolated `DSH_HOME=/root/.dsh-terminal`.
 */
class TerminalPtySession(
    private val launcher: ContainerLauncher,
    private val scope: CoroutineScope
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private var proc: Process? = null
    private var stdin: OutputStream? = null
    private var readJob: Job? = null

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _output = MutableSharedFlow<String>(extraBufferCapacity = 256)
    val output: SharedFlow<String> = _output

    private val _exitCode = MutableStateFlow<Int?>(null)
    val exitCode: StateFlow<Int?> = _exitCode.asStateFlow()

    fun start(cols: Int = 80, rows: Int = 24, cwd: String = "/root") {
        stop()
        _exitCode.value = null
        val p = launcher.start(
            "/opt/node24/bin/node",
            "/opt/dshn/dshn-pty-runner.mjs",
            "--cols", cols.toString(),
            "--rows", rows.toString(),
            "--cwd", cwd,
            extraEnv = mapOf(
                "DSHN_TERMINAL_DSH_HOME" to "/root/.dsh-terminal",
                "DSH_HOME" to "/root/.dsh-terminal"
            )
        )
        proc = p
        stdin = p.outputStream
        _running.value = true

        // Drain stderr continuously
        scope.launch(Dispatchers.IO) {
            runCatching {
                p.errorStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                    if (!line.contains("selftest")) {
                        _output.tryEmit("\u001B[33m[stderr] $line\u001B[0m\r\n")
                    }
                }
            }
        }

        readJob = scope.launch(Dispatchers.IO) {
            try {
                BufferedReader(InputStreamReader(p.inputStream, Charsets.UTF_8)).forEachLine { line ->
                    if (line.isBlank()) return@forEachLine
                    val frame = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()
                    if (frame == null) {
                        _output.tryEmit(line + "\r\n")
                        return@forEachLine
                    }
                    when (frame["type"]?.jsonPrimitive?.contentOrNull) {
                        "data" -> {
                            val b64 = frame["b64"]?.jsonPrimitive?.contentOrNull ?: return@forEachLine
                            val bytes = Base64.decode(b64, Base64.DEFAULT)
                            _output.tryEmit(String(bytes, Charsets.UTF_8))
                        }
                        "exit" -> {
                            val code = frame["code"]?.jsonPrimitive?.intOrNull ?: 0
                            _exitCode.value = code
                            _running.value = false
                        }
                        "error" -> {
                            val msg = frame["message"]?.jsonPrimitive?.contentOrNull ?: "PTY error"
                            _output.tryEmit("\r\n\u001B[31m[pty error] $msg\u001B[0m\r\n")
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                _running.value = false
            }
        }
    }

    fun writeInput(text: String) {
        if (!_running.value) return
        val b64 = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        sendControl(buildJsonObject {
            put("type", "input")
            put("b64", b64)
        }.toString())
    }

    fun resize(cols: Int, rows: Int) {
        if (!_running.value) return
        sendControl(buildJsonObject {
            put("type", "resize")
            put("cols", cols)
            put("rows", rows)
        }.toString())
    }

    private fun sendControl(line: String) {
        val out = stdin ?: return
        scope.launch(Dispatchers.IO) {
            runCatching {
                synchronized(out) {
                    out.write((line + "\n").toByteArray(Charsets.UTF_8))
                    out.flush()
                }
            }
        }
    }

    fun stop() {
        readJob?.cancel()
        readJob = null
        stdin?.let { out ->
            runCatching {
                out.write("{\"type\":\"kill\"}\n".toByteArray(Charsets.UTF_8))
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
