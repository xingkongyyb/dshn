package dev.dshn.app.bridge

import android.content.Context
import android.os.Build
import dev.dshn.app.bridge.virtualdisplay.VirtualDisplayAgent
import dev.dshn.app.data.SettingsStore
import dev.dshn.app.security.AuditLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class VirtualDisplayUiState(
    val active: Boolean = false,
    val starting: Boolean = false,
    val displayId: Int = -1,
    val width: Int = 720,
    val height: Int = 1280,
    val dpi: Int = 300,
    val driver: String = "",
    val rustEncoder: Boolean = false,
    val uiTreeAvailable: Boolean = false,
    val directTouchAvailable: Boolean = false,
    val topComponent: String = "",
    val lastError: String? = null
)

data class VirtualFrameSnapshot(
    val jpegBytes: ByteArray,
    val width: Int,
    val height: Int,
    val hash: Long,
    val encoder: String,
    val timestampMs: Long
)

/**
 * Manages a live Android 14+ background virtual display driven by [VirtualDisplayAgent]
 * over either user-authorized Root (`su`) or Wireless ADB (`shell,v2,raw`).
 */
class VirtualDisplayController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settingsStore: SettingsStore,
    private val adbClient: WirelessAdbClient,
    private val rootAuthManager: RootAuthManager,
    private val auditLogger: AuditLogger
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val opMutex = Mutex()
    private val nextReqId = AtomicLong(1L)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()

    private val _state = MutableStateFlow(VirtualDisplayUiState())
    val state: StateFlow<VirtualDisplayUiState> = _state.asStateFlow()

    private val _latestFrame = MutableStateFlow<VirtualFrameSnapshot?>(null)
    val latestFrame: StateFlow<VirtualFrameSnapshot?> = _latestFrame.asStateFlow()

    @Volatile private var transport: AgentTransport? = null
    @Volatile private var readerJob: Job? = null
    @Volatile private var lastFrameHash: Long = 0L

    private val rootFilesDir = File(context.filesDir.absolutePath.replaceFirst("/data/user/0/", "/data/data/"))
    private val screensDir = File(rootFilesDir, "bridge/screens")

    private interface AgentTransport : java.io.Closeable {
        val driverLabel: String
        fun writeLine(line: String)
        fun readLine(): String?
    }

    private class RootProcessTransport(private val proc: Process) : AgentTransport {
        override val driverLabel: String = "root"
        private val writer = BufferedWriter(OutputStreamWriter(proc.outputStream, Charsets.UTF_8))
        private val reader = BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8))

        override fun writeLine(line: String) {
            synchronized(writer) {
                writer.write(line)
                writer.newLine()
                writer.flush()
            }
        }

        override fun readLine(): String? = reader.readLine()

        override fun close() {
            runCatching { writer.close() }
            runCatching { reader.close() }
            runCatching { proc.destroyForcibly() }
        }
    }

    private class AdbTransport(private val session: WirelessAdbClient.AdbRawShellSession) : AgentTransport {
        override val driverLabel: String = "adb"
        override fun writeLine(line: String) = session.writeLine(line)
        override fun readLine(): String? = session.readStdoutLine()
        override fun close() = session.close()
    }

    fun isActiveDisplay(displayId: Int): Boolean {
        val st = _state.value
        return st.active && st.displayId > 0 && st.displayId == displayId
    }

    suspend fun open(
        width: Int = 720,
        height: Int = 1280,
        dpi: Int = 300,
        launchPackage: String? = null
    ): Result<VirtualDisplayUiState> = opMutex.withLock {
        runCatching {
            if (Build.VERSION.SDK_INT < 34) {
                error("Background virtual display requires Android 14+ (API 34+); current API=${Build.VERSION.SDK_INT}")
            }
            if (_state.value.active && transport != null) {
                if (!launchPackage.isNullOrBlank()) {
                    sendCommandLocked("launch", buildJsonObject { put("package", launchPackage.trim()) })
                }
                return@runCatching _state.value
            }

            _state.value = _state.value.copy(starting = true, lastError = null)
            val conn = connectTransport()
            transport = conn
            val helloReady = CompletableDeferred<JsonObject>()
            startReaderLoop(conn, helloReady)

            val hello = withTimeout(10_000L) { helloReady.await() }
            if (hello["ok"]?.jsonPrimitive?.booleanOrNull != true) {
                val msg = hello["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull ?: "Agent handshake failed"
                teardownLocked(msg)
                error(msg)
            }

            val res = sendCommandLocked(
                "open",
                buildJsonObject {
                    put("width", width)
                    put("height", height)
                    put("dpi", dpi)
                }
            )
            if (res["ok"]?.jsonPrimitive?.booleanOrNull != true) {
                val msg = res["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull ?: "Failed to create virtual display"
                teardownLocked(msg)
                error(msg)
            }

            val openedState = VirtualDisplayUiState(
                active = true,
                starting = false,
                displayId = res["display_id"]?.jsonPrimitive?.intOrNull ?: -1,
                width = res["width"]?.jsonPrimitive?.intOrNull ?: width,
                height = res["height"]?.jsonPrimitive?.intOrNull ?: height,
                dpi = res["dpi"]?.jsonPrimitive?.intOrNull ?: dpi,
                driver = res["driver"]?.jsonPrimitive?.contentOrNull ?: conn.driverLabel,
                rustEncoder = res["rust_encoder"]?.jsonPrimitive?.booleanOrNull ?: false,
                uiTreeAvailable = res["ui_tree_available"]?.jsonPrimitive?.booleanOrNull ?: false,
                directTouchAvailable = res["direct_touch_available"]?.jsonPrimitive?.booleanOrNull ?: false,
                lastError = null
            )
            _state.value = openedState
            lastFrameHash = 0L
            auditLogger.log(
                "virtual_display",
                "open",
                "allowed",
                "Opened virtual display #${openedState.displayId} (${openedState.width}x${openedState.height} @${openedState.dpi}, driver=${openedState.driver}, rust=${openedState.rustEncoder})"
            )

            if (!launchPackage.isNullOrBlank()) {
                val launchRes = sendCommandLocked("launch", buildJsonObject { put("package", launchPackage.trim()) })
                val top = launchRes["top_component"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (top.isNotEmpty()) {
                    _state.value = _state.value.copy(topComponent = top)
                }
            }
            _state.value
        }.onFailure { err ->
            val msg = err.message ?: err.javaClass.simpleName
            _state.value = _state.value.copy(active = false, starting = false, lastError = msg)
        }
    }

    suspend fun launchApp(packageName: String, activity: String? = null): Result<JsonObject> = opMutex.withLock {
        runCatching {
            val res = sendCommandLocked(
                "launch",
                buildJsonObject {
                    put("package", packageName.trim())
                    if (!activity.isNullOrBlank()) put("activity", activity.trim())
                }
            )
            checkOk(res)
            val top = res["top_component"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (top.isNotEmpty()) _state.value = _state.value.copy(topComponent = top)
            res
        }
    }

    suspend fun observe(
        includeTree: Boolean = true,
        includeImage: Boolean = true,
        quality: Int = 72,
        maxWidth: Int = 720,
        maxNodes: Int = 160,
        skipUnchanged: Boolean = false
    ): Result<JsonObject> = opMutex.withLock {
        runCatching {
            val res = sendCommandLocked(
                "observe",
                buildJsonObject {
                    put("include_tree", includeTree)
                    put("include_image", includeImage)
                    put("quality", quality)
                    put("max_width", maxWidth)
                    put("max_nodes", maxNodes)
                    put("previous_hash", if (skipUnchanged) lastFrameHash else 0L)
                },
                timeoutMs = 12_000L
            )
            checkOk(res)
            val top = res["top_component"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (top.isNotEmpty()) _state.value = _state.value.copy(topComponent = top)

            val unchanged = res["unchanged"]?.jsonPrimitive?.booleanOrNull ?: false
            val b64 = res["jpeg_base64"]?.jsonPrimitive?.contentOrNull
            if (!unchanged && !b64.isNullOrEmpty()) {
                val bytes = Base64.getDecoder().decode(b64)
                val hash = res["frame_hash"]?.jsonPrimitive?.longOrNull ?: 0L
                lastFrameHash = hash
                _latestFrame.value = VirtualFrameSnapshot(
                    jpegBytes = bytes,
                    width = res["image_width"]?.jsonPrimitive?.intOrNull ?: _state.value.width,
                    height = res["image_height"]?.jsonPrimitive?.intOrNull ?: _state.value.height,
                    hash = hash,
                    encoder = res["encoder"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    timestampMs = System.currentTimeMillis()
                )
                runCatching {
                    if (!screensDir.exists()) screensDir.mkdirs()
                    File(screensDir, "vd_latest.jpg").writeBytes(bytes)
                }
            }
            res
        }
    }

    suspend fun tap(x: Float, y: Float): Result<JsonObject> = opMutex.withLock {
        runCatching {
            val res = sendCommandLocked("tap", buildJsonObject { put("x", x); put("y", y) })
            checkOk(res)
            res
        }
    }

    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Int = 300): Result<JsonObject> = opMutex.withLock {
        runCatching {
            val res = sendCommandLocked(
                "swipe",
                buildJsonObject {
                    put("x1", x1); put("y1", y1)
                    put("x2", x2); put("y2", y2)
                    put("duration_ms", durationMs)
                }
            )
            checkOk(res)
            res
        }
    }

    suspend fun inputText(text: String): Result<JsonObject> = opMutex.withLock {
        runCatching {
            val res = sendCommandLocked("input", buildJsonObject { put("text", text) })
            checkOk(res)
            res
        }
    }

    suspend fun sendKey(action: String): Result<JsonObject> = opMutex.withLock {
        runCatching {
            val res = sendCommandLocked("key", buildJsonObject { put("action", action) })
            checkOk(res)
            res
        }
    }

    suspend fun close(reason: String? = null) = opMutex.withLock {
        if (transport != null) {
            runCatching { sendCommandLocked("close", buildJsonObject {}, timeoutMs = 2_500L) }
        }
        teardownLocked(reason)
        auditLogger.log("virtual_display", "close", "allowed", reason ?: "Closed virtual display")
    }

    private suspend fun connectTransport(): AgentTransport {
        val settings = settingsStore.current()
        val apkPath = context.applicationInfo.sourceDir
        val nativeLibDir = context.applicationInfo.nativeLibraryDir

        // 1. Prefer authorized Root when Root Bridge is enabled.
        if (settings.allowRootBridge && rootAuthManager.isAuthorized(settings.rootSessionTtlMinutes)) {
            val su = rootAuthManager.state.value.suBinaryPath
            if (!su.isNullOrBlank()) {
                val cmd = "CLASSPATH='$apkPath' /system/bin/app_process /system/bin " +
                    "dev.dshn.app.bridge.virtualdisplay.VirtualDisplayAgent --native-lib-dir '$nativeLibDir'"
                val proc = withContext(Dispatchers.IO) {
                    ProcessBuilder(su, "-c", cmd).redirectErrorStream(false).start()
                }
                return RootProcessTransport(proc)
            }
        }

        // 2. Fallback to Wireless ADB when ADB Bridge is enabled & endpoint configured/discovered
        //    (or auto-probe local `adb tcpip 5555` loopback `127.0.0.1:5555`).
        if (settings.allowAdbBridge) {
            val ep = resolveAdbEndpoint(settings.lastAdbEndpoint)
                ?: ("127.0.0.1" to 5555)
            val session = runCatching {
                adbClient.openVirtualDisplayAgentSession(
                    host = ep.first,
                    port = ep.second,
                    apkPath = apkPath,
                    nativeLibDir = nativeLibDir,
                    transportMode = settings.adbTransportMode
                )
            }.getOrElse { err ->
                val detail = err.message ?: err.javaClass.simpleName
                error(
                    "无法经无线 ADB（${ep.first}:${ep.second}）启动虚拟屏：$detail。请先在下方「无线 ADB」连接本机（或在电脑执行一次 adb tcpip 5555 后再试），或授权 Root。"
                )
            }
            if (settings.lastAdbEndpoint.isNullOrBlank()) {
                settingsStore.update { it.copy(lastAdbEndpoint = "${ep.first}:${ep.second}") }
            }
            return AdbTransport(session)
        }

        error("请先开启并连接下方「无线 ADB」（或开启并授权「Root 命令」）后再启动后台虚拟屏。")
    }

    private fun resolveAdbEndpoint(saved: String?): Pair<String, Int>? {
        val parsed = saved?.trim()?.let { raw ->
            val idx = raw.lastIndexOf(':')
            if (idx > 0) {
                val h = raw.substring(0, idx).trim()
                val p = raw.substring(idx + 1).trim().toIntOrNull()
                if (h.isNotEmpty() && p != null && p in 1..65535) h to p else null
            } else null
        }
        if (parsed != null) return parsed
        val discovered = adbClient.endpoints.value.firstOrNull { it.serviceType.contains("connect") }
            ?: adbClient.endpoints.value.firstOrNull()
        return discovered?.let { it.host to it.port }
    }

    private fun startReaderLoop(conn: AgentTransport, helloReady: CompletableDeferred<JsonObject>) {
        readerJob?.cancel()
        readerJob = scope.launch(Dispatchers.IO) {
            try {
                while (true) {
                    val line = conn.readLine() ?: break
                    val parsed = parseWireLine(line) ?: continue
                    val event = parsed["event"]?.jsonPrimitive?.contentOrNull
                    if (event == "hello") {
                        if (!helloReady.isCompleted) helloReady.complete(parsed)
                        continue
                    }
                    if (event == "stopped") {
                        _state.value = _state.value.copy(active = false, starting = false, lastError = "Display stopped by Android")
                        continue
                    }
                    val reqId = parsed["id"]?.jsonPrimitive?.longOrNull ?: continue
                    val resObj = parsed["result"]?.jsonObject ?: parsed
                    pending.remove(reqId)?.complete(resObj)
                }
            } catch (t: Throwable) {
                if (!helloReady.isCompleted) helloReady.completeExceptionally(t)
            } finally {
                val eof = IllegalStateException("Virtual display agent disconnected")
                if (!helloReady.isCompleted) helloReady.completeExceptionally(eof)
                pending.values.forEach { it.completeExceptionally(eof) }
                pending.clear()
                if (_state.value.active) {
                    _state.value = _state.value.copy(active = false, starting = false)
                }
            }
        }
    }

    private suspend fun sendCommandLocked(
        op: String,
        args: JsonObject,
        timeoutMs: Long = 10_000L
    ): JsonObject {
        val conn = transport ?: error("Virtual display agent is not running.")
        val id = nextReqId.getAndIncrement()
        val deferred = CompletableDeferred<JsonObject>()
        pending[id] = deferred
        val reqLine = buildJsonObject {
            put("id", id)
            put("op", op)
            put("args", args)
        }.toString()
        try {
            withContext(Dispatchers.IO) { conn.writeLine(reqLine) }
            return withTimeout(timeoutMs) { deferred.await() }
        } catch (t: Throwable) {
            pending.remove(id)
            throw t
        }
    }

    private fun checkOk(res: JsonObject) {
        if (res["ok"]?.jsonPrimitive?.booleanOrNull != true) {
            val msg = res["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                ?: "Virtual display command failed"
            error(msg)
        }
    }

    private fun teardownLocked(errorMsg: String?) {
        readerJob?.cancel()
        readerJob = null
        runCatching { transport?.close() }
        transport = null
        pending.values.forEach { it.cancel() }
        pending.clear()
        lastFrameHash = 0L
        _latestFrame.value = null
        _state.value = VirtualDisplayUiState(lastError = errorMsg)
    }

    companion object {
        private val wireJson = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Parses a `DSHN_VD:{...}` protocol line, ignoring noisy shell banners/warnings. */
        internal fun parseWireLine(rawLine: String): JsonObject? {
            val idx = rawLine.indexOf(VirtualDisplayAgent.WIRE_PREFIX)
            if (idx < 0) return null
            val payload = rawLine.substring(idx + VirtualDisplayAgent.WIRE_PREFIX.length).trim()
            if (payload.isEmpty()) return null
            return runCatching { wireJson.parseToJsonElement(payload).jsonObject }.getOrNull()
        }
    }
}
