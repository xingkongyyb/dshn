package dev.dshn.app.runtime

import android.content.Context
import android.os.PowerManager
import android.os.Process as AndroidProcess
import android.system.Os
import android.system.OsConstants
import dev.dshn.app.acp.AcpClient
import dev.dshn.app.acp.AcpException
import dev.dshn.app.acp.McpServerConfig
import dev.dshn.app.acp.PermissionOutcome
import dev.dshn.app.acp.PermissionRequest
import dev.dshn.app.acp.SessionUpdate
import dev.dshn.app.data.SettingsStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

enum class RuntimeLifecycleState {
    STOPPED,
    STARTING,
    HEALTHY,
    DEGRADED,
    STOPPING,
    ERROR
}

@Serializable
data class RuntimeStatusSnapshot(
    val state: String,
    val detail: String,
    val restartCount: Int,
    val activePrompts: Int,
    val occupiedSessions: List<String>,
    val dshVersion: String,
    val nodeVersion: String,
    val rootfsReady: Boolean,
    val bridgePingOk: Boolean,
    val permissionMode: String,
    val imagePromptSupported: Boolean = false
)

interface RuntimeEventSink {
    fun onStateChanged(state: RuntimeLifecycleState, detail: String, restartCount: Int)
    fun onSessionUpdate(sessionId: String, jsonUpdate: String)
    fun onPermissionRequested(
        requestId: String,
        sessionId: String,
        toolCallId: String?,
        title: String?,
        optionsJson: String
    )
    fun onQuestionRequested(questionRequestJson: String)
    fun onQuestionCancelled(requestId: String, sessionId: String?)
    fun onPromptCompleted(requestId: String, sessionId: String, stopReason: String?, errorMessage: String?)
    fun onInterruptedTurnUnknown(sessionId: String, message: String)
    fun onStderrLine(line: String)
}

/**
 * Supervises the containerized DSH ACP runtime (`:runtime` process):
 * - Single-writer FileLock + stale container PID cleanup
 * - Continuous stderr ring buffer + runtime.log
 * - Stdout corruption detection (DEGRADED state)
 * - 5-second watchdog + bounded crash-loop restart (<= 3 crashes in 60s)
 * - Active prompt wake lock + occupied session guard + TOOL_OUTCOME_UNKNOWN detection
 */
class RuntimeSupervisor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val sink: RuntimeEventSink
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val launcher = ContainerLauncher(context)
    private val settingsStore = SettingsStore(context)
    private val envManager = EnvManager(context, launcher)
    private val bridgeSockFile = File(launcher.bridgeDir, "session-bridge.sock")
    val bridgeClient = SessionBridgeClient(bridgeSockFile, scope)

    private val runtimeDir = File(launcher.rootFilesDir, "runtime").apply { mkdirs() }
    val ipcXferDir = File(runtimeDir, "ipc-xfer").apply { mkdirs() }
    private val logsDir = File(launcher.rootFilesDir, "logs").apply { mkdirs() }
    private val logFile = File(logsDir, "runtime.log")
    private val lockFile = File(runtimeDir, "runtime.lock")
    private val pidFile = File(runtimeDir, "dsh.pid")

    private val startStopMutex = Mutex()
    private val logRing = ArrayDeque<String>(220)
    private val logLock = Any()

    private val restartTimestamps = ArrayDeque<Long>()
    private val crashRestartCount = AtomicInteger(0)

    private val activePrompts = ConcurrentHashMap<String, String>() // requestId -> sessionId
    private val pendingPermissions = ConcurrentHashMap<String, CompletableDeferred<PermissionOutcome>>()
    private val stopRequested = AtomicBoolean(false)
    private val restartScheduled = AtomicBoolean(false)

    @Volatile var state: RuntimeLifecycleState = RuntimeLifecycleState.STOPPED
        private set
    @Volatile var detail: String = "Idle"
        private set

    @Volatile private var process: Process? = null
    @Volatile private var acpClient: AcpClient? = null
    @Volatile private var bridgePingOk: Boolean = false
    private var updateCollectorJob: Job? = null
    private var bridgeEventsJob: Job? = null
    private var watchdogJob: Job? = null

    private var lockChannelFile: RandomAccessFile? = null
    private var fileLock: FileLock? = null

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dshn:runtime-turn")

    init {
        subscribeBridgeEvents()
    }

    private fun subscribeBridgeEvents() {
        bridgeEventsJob?.cancel()
        bridgeEventsJob = scope.launch {
            bridgeClient.events.collect { ev ->
                when (ev) {
                    is BridgeEvent.QuestionRequested -> {
                        sink.onQuestionRequested(json.encodeToString(ev.request))
                    }
                    is BridgeEvent.QuestionCancelled -> {
                        sink.onQuestionCancelled(ev.requestId, ev.sessionId)
                    }
                }
            }
        }
    }

    suspend fun start() {
        startStopMutex.withLock {
            if (state == RuntimeLifecycleState.HEALTHY || state == RuntimeLifecycleState.STARTING) {
                return
            }
            stopRequested.set(false)
            restartScheduled.set(false)
            doStartLocked()
        }
    }

    suspend fun stop() {
        startStopMutex.withLock {
            stopRequested.set(true)
            doStopLocked("Stopped by user")
        }
    }

    suspend fun restart() {
        startStopMutex.withLock {
            stopRequested.set(true)
            doStopLocked("Restarting runtime...")
            stopRequested.set(false)
            crashRestartCount.set(0)
            restartTimestamps.clear()
            doStartLocked()
        }
    }

    private suspend fun doStartLocked() {
        updateState(RuntimeLifecycleState.STARTING, "Preparing container & runtime assets...")
        appendLog("[supervisor] Starting runtime...")

        if (!acquireSingleWriterLock()) {
            updateState(RuntimeLifecycleState.ERROR, "Failed to acquire single-writer runtime lock")
            return
        }
        cleanupOrphanPid()

        val manifest = runCatching { envManager.ensureRuntimeAssets() }.getOrElse {
            updateState(RuntimeLifecycleState.ERROR, "Asset staging failed: ${it.message}")
            return
        }
        if (!manifest.rootfsReady || !launcher.isReady()) {
            updateState(RuntimeLifecycleState.ERROR, "Container rootfs or bxroot binary missing")
            return
        }

        settingsStore.ensureContainerApiKeySynced()
        val settings = settingsStore.current()
        if (bridgeSockFile.exists()) runCatching { bridgeSockFile.delete() }

        val proc = try {
            launcher.start(
                "/opt/node24/bin/dsh",
                "--profile", "acp",
                "--patch", "/opt/dshn/dshn-bridge.patch.yml",
                permissionMode = settings.permissionMode.envValue,
                extraWorkspaceBind = settings.extraWorkspaceBindHostPath,
                deepseekBaseUrl = settings.deepseekBaseUrl,
                deepseekSearchBaseUrl = settings.deepseekSearchBaseUrl,
                webSearchProvider = settings.webSearchProvider
            )
        } catch (t: Throwable) {
            updateState(RuntimeLifecycleState.ERROR, "Failed to spawn dsh container: ${t.message}")
            return
        }
        process = proc
        extractHostPid(proc)?.let { runCatching { pidFile.writeText(it.toString()) } }

        // Continuous stderr drain
        scope.launch(Dispatchers.IO) {
            runCatching {
                proc.errorStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                    if (!line.contains("selftest")) {
                        appendLog("[stderr] $line")
                        sink.onStderrLine(line)
                    }
                }
            }
        }

        val client = AcpClient(
            input = proc.inputStream,
            output = proc.outputStream,
            scope = scope,
            onPermissionRequest = { req -> handleAcpPermission(req) },
            onStreamDegraded = { badCount, sample ->
                appendLog("[watchdog] Stdout stream degraded ($badCount bad lines): $sample")
                if (state == RuntimeLifecycleState.HEALTHY) {
                    updateState(RuntimeLifecycleState.DEGRADED, "Stdout malformed lines ($badCount)")
                }
            },
            onStreamClosed = { err ->
                if (!stopRequested.get()) {
                    handleUnexpectedProcessExit("ACP stdout closed: ${err?.message ?: "EOF"}")
                }
            }
        )
        acpClient = client

        updateCollectorJob?.cancel()
        updateCollectorJob = scope.launch {
            client.updates.collect { upd ->
                sink.onSessionUpdate(upd.sessionId, serializeSessionUpdate(upd))
            }
        }

        try {
            updateState(RuntimeLifecycleState.STARTING, "Waiting for ACP initialize handshake...")
            val init = client.initialize(timeoutMs = 60_000)
            val caps = client.agentCapabilities
            appendLog("[acp] initialize ok: ${init["agentInfo"] ?: init} (imagePrompt=${caps.imagePrompt}, httpMcp=${caps.httpMcp})")
        } catch (t: Throwable) {
            appendLog("[acp] initialize failed: ${t.message}")
            doStopLocked("ACP initialize failed: ${t.message}")
            updateState(RuntimeLifecycleState.ERROR, "ACP initialize failed: ${t.message}")
            return
        }

        bridgeClient.startEventSubscription()
        bridgePingOk = waitForBridgePing(timeoutMs = 15_000)
        if (!bridgePingOk) {
            appendLog("[bridge] session-bridge.sock did not respond immediately; continuing in degraded mode")
            updateState(RuntimeLifecycleState.DEGRADED, "ACP ready; session bridge UDS unavailable")
        } else {
            appendLog("[bridge] session-bridge.sock healthy")
            updateState(RuntimeLifecycleState.HEALTHY, "DSH ACP ready (dsh ${manifest.dshVersion})")
        }
        startWatchdog()
    }

    private suspend fun waitForBridgePing(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastErr: String? = null
        var backoffMs = 30L
        while (System.currentTimeMillis() < deadline) {
            val res = bridgeClient.pingResult(timeoutMs = 1_500)
            if (res.getOrDefault(false)) {
                return true
            }
            res.exceptionOrNull()?.message?.let { lastErr = it }
            delay(backoffMs)
            backoffMs = (backoffMs * 2L).coerceAtMost(200L)
        }
        if (lastErr != null) {
            appendLog("[bridge] ping error: $lastErr")
        }
        return false
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch(Dispatchers.IO) {
            while (isActive && !stopRequested.get()) {
                delay(5_000)
                if (stopRequested.get()) break
                val proc = process
                val alive = proc != null && proc.isAlive
                if (!alive) {
                    handleUnexpectedProcessExit("Container process terminated unexpectedly (exit=${runCatching { proc?.exitValue() }.getOrNull()})")
                    break
                }
                val pingOk = bridgeClient.ping(timeoutMs = 2_500)
                bridgePingOk = pingOk
                if (pingOk && state == RuntimeLifecycleState.DEGRADED && (acpClient?.totalBadLines ?: 0) == 0) {
                    updateState(RuntimeLifecycleState.HEALTHY, "DSH ACP healthy")
                }
            }
        }
    }

    private fun handleUnexpectedProcessExit(reason: String) {
        if (!restartScheduled.compareAndSet(false, true)) return
        scope.launch {
            startStopMutex.withLock {
                if (stopRequested.get()) {
                    restartScheduled.set(false)
                    return@withLock
                }
                appendLog("[watchdog] Unexpected exit: $reason")

                // Notify any active turns that tool execution outcome is unknown
                val interruptedSessions = activePrompts.values.distinct()
                activePrompts.clear()
                updateWakeLock()
                cancelAllPendingPermissions()
                for (sid in interruptedSessions) {
                    sink.onInterruptedTurnUnknown(
                        sid,
                        "TOOL_OUTCOME_UNKNOWN: Runtime exited during active turn ($reason). Check workspace before retrying."
                    )
                }

                cleanupProcessHandles()

                val settings = settingsStore.current()
                if (!settings.autoRestartRuntime) {
                    updateState(RuntimeLifecycleState.ERROR, "Runtime crashed ($reason)")
                    restartScheduled.set(false)
                    return@withLock
                }

                val now = System.currentTimeMillis()
                while (restartTimestamps.isNotEmpty() && now - restartTimestamps.first() > 60_000L) {
                    restartTimestamps.removeFirst()
                }
                if (restartTimestamps.size >= MAX_CRASHES_PER_MINUTE) {
                    updateState(
                        RuntimeLifecycleState.ERROR,
                        "Crash loop detected (>=$MAX_CRASHES_PER_MINUTE crashes in 60s): $reason"
                    )
                    restartScheduled.set(false)
                    return@withLock
                }
                restartTimestamps.addLast(now)
                val count = crashRestartCount.incrementAndGet()
                val backoffMs = (1_000L shl (count - 1).coerceAtMost(3)).coerceAtMost(8_000L)
                updateState(RuntimeLifecycleState.STARTING, "Restarting (#$count) after crash: $reason")
                delay(backoffMs)
                if (!stopRequested.get()) {
                    doStartLocked()
                }
                restartScheduled.set(false)
            }
        }
    }

    private fun doStopLocked(reason: String) {
        updateState(RuntimeLifecycleState.STOPPING, reason)
        watchdogJob?.cancel()
        watchdogJob = null
        updateCollectorJob?.cancel()
        updateCollectorJob = null
        bridgeClient.stopEventSubscription()
        cancelAllPendingPermissions()
        activePrompts.clear()
        updateWakeLock()
        cleanupProcessHandles()
        releaseSingleWriterLock()
        if (bridgeSockFile.exists()) runCatching { bridgeSockFile.delete() }
        updateState(RuntimeLifecycleState.STOPPED, reason)
    }

    private fun cleanupProcessHandles() {
        runCatching { acpClient?.close() }
        acpClient = null
        val p = process
        process = null
        if (p != null) {
            runCatching { p.destroy() }
            if (p.isAlive) runCatching { p.destroyForcibly() }
        }
        if (pidFile.exists()) runCatching { pidFile.delete() }
    }

    private suspend fun handleAcpPermission(req: PermissionRequest): PermissionOutcome {
        val requestId = "perm-${System.nanoTime()}"
        val deferred = CompletableDeferred<PermissionOutcome>()
        pendingPermissions[requestId] = deferred
        val optionsJson = buildJsonObject {
            putJsonArray("options") {
                for (opt in req.options) {
                    add(buildJsonObject {
                        put("optionId", opt.optionId)
                        put("name", opt.name)
                        if (opt.kind != null) put("kind", opt.kind)
                    })
                }
            }
        }["options"].toString()
        sink.onPermissionRequested(requestId, req.sessionId, req.toolCallId, req.title, optionsJson)
        return try {
            deferred.await()
        } finally {
            pendingPermissions.remove(requestId)
        }
    }

    fun replyPermission(requestId: String, optionId: String, cancelled: Boolean) {
        pendingPermissions.remove(requestId)?.complete(PermissionOutcome(optionId, cancelled))
    }

    private fun cancelAllPendingPermissions() {
        pendingPermissions.values.forEach {
            it.complete(PermissionOutcome(optionId = "", cancelled = true))
        }
        pendingPermissions.clear()
    }

    private fun deviceBridgeMcpConfig(): List<McpServerConfig> {
        // On-demand MCP attachment: skip spawning a relay process per session when Device Bridge is disabled.
        if (!settingsStore.current().bridgeEnabled) {
            return emptyList()
        }
        val nativeRelay = File(launcher.rootfs, "opt/dshn/dshn-mcp-relay")
        val hostSock = File(launcher.bridgeDir, "device-mcp.sock").absolutePath
        return if (nativeRelay.isFile && nativeRelay.canExecute()) {
            listOf(
                McpServerConfig.Stdio(
                    name = "dshn-device-bridge",
                    command = "/opt/dshn/dshn-mcp-relay",
                    args = emptyList(),
                    env = mapOf(
                        "DSHN_DEVICE_MCP_SOCK" to "/run/dshn-bridge/device-mcp.sock",
                        "DSHN_DEVICE_MCP_HOST_SOCK" to hostSock
                    )
                )
            )
        } else {
            listOf(
                McpServerConfig.Stdio(
                    name = "dshn-device-bridge",
                    command = "/opt/node24/bin/node",
                    args = listOf("/opt/dshn/dshn-mcp-relay.mjs"),
                    env = mapOf("DSHN_DEVICE_MCP_SOCK" to "/run/dshn-bridge/device-mcp.sock")
                )
            )
        }
    }

    suspend fun newSession(cwd: String): String {
        val c = requireHealthyClient()
        val effectiveCwd = cwd.ifBlank { "/root" }
        val res = c.newSession(effectiveCwd, mcpServers = deviceBridgeMcpConfig())
        return buildJsonObject {
            put("sessionId", res.sessionId)
            put("configOptions", Json.parseToJsonElement(json.encodeToString(res.configOptions)))
        }.toString()
    }

    suspend fun resumeSession(sessionId: String, cwd: String): String {
        val c = requireHealthyClient()
        val effectiveCwd = cwd.ifBlank { "/root" }
        val res = c.resumeSession(sessionId, effectiveCwd, mcpServers = deviceBridgeMcpConfig())
        return buildJsonObject {
            put("sessionId", res.sessionId)
            put("configOptions", Json.parseToJsonElement(json.encodeToString(res.configOptions)))
        }.toString()
    }

    suspend fun closeSession(sessionId: String): String {
        if (activePrompts.values.contains(sessionId)) {
            throw IllegalStateException("SESSION_OCCUPIED: Session $sessionId has an active turn")
        }
        val c = requireHealthyClient()
        c.closeSession(sessionId)
        return buildJsonObject { put("closed", true); put("sessionId", sessionId) }.toString()
    }

    suspend fun setConfigOption(sessionId: String, configId: String, value: String): String {
        val c = requireHealthyClient()
        val updated = c.setConfigOption(sessionId, configId, value)
        return json.encodeToString(updated)
    }

    fun sendPromptAsync(requestId: String, sessionId: String, text: String) {
        scope.launch(Dispatchers.IO) {
            val c = acpClient
            if (c == null || c.isClosed) {
                sink.onPromptCompleted(requestId, sessionId, null, "Runtime not connected")
                return@launch
            }
            activePrompts[requestId] = sessionId
            updateWakeLock()
            try {
                val res = c.prompt(sessionId, text)
                sink.onPromptCompleted(requestId, sessionId, res.stopReason, null)
            } catch (t: Throwable) {
                val rawMsg = t.message ?: "Prompt failed"
                val formatted = if ((t is AcpException && t.isContentRefused) || AcpClient.isContentRefusedMessage(rawMsg)) {
                    "[CONTENT_REFUSED] $rawMsg"
                } else {
                    rawMsg
                }
                sink.onPromptCompleted(requestId, sessionId, null, formatted)
            } finally {
                activePrompts.remove(requestId)
                updateWakeLock()
            }
        }
    }

    fun cancelPrompt(sessionId: String) {
        runCatching { acpClient?.cancel(sessionId) }
    }

    suspend fun listSessionsFromBridge(): String {
        val items = bridgeClient.listSessions()
        return json.encodeToString(items)
    }

    suspend fun readSurfaceFromBridge(sessionId: String): String {
        val obj = bridgeClient.readSurfaceJson(sessionId)
        return LargePayloadXfer.encode(ipcXferDir, "surface", obj.toString())
    }

    suspend fun listCommandsFromBridge(sessionId: String?): String {
        val cmds = bridgeClient.listCommands(sessionId)
        return json.encodeToString(cmds)
    }

    suspend fun executeCommandFromBridge(sessionId: String, line: String): String {
        val res = bridgeClient.executeCommand(sessionId, line)
        return res.toString()
    }

    suspend fun inspectContextFromBridge(sessionId: String?): String {
        val obj = bridgeClient.inspectContextJson(sessionId)
        return LargePayloadXfer.encode(ipcXferDir, "ctxinspect", obj.toString())
    }

    suspend fun listPluginsFromBridge(locale: String): String {
        val obj = bridgeClient.listPluginsJson(locale)
        return LargePayloadXfer.encode(ipcXferDir, "plugins", obj.toString())
    }

    suspend fun setPluginEnabledFromBridge(name: String, enabled: Boolean): String {
        val res = bridgeClient.setPluginEnabledJson(name, enabled)
        return res.toString()
    }

    suspend fun installPluginFromBridge(spec: String, enabled: Boolean): String {
        val res = bridgeClient.installPluginJson(spec, enabled)
        return res.toString()
    }

    suspend fun removePluginFromBridge(name: String): String {
        val res = bridgeClient.removePluginJson(name)
        return res.toString()
    }

    suspend fun installPnpmFromBridge(): String {
        val res = bridgeClient.installPnpmJson()
        return res.toString()
    }

    suspend fun replyQuestion(requestId: String, answersJson: String, cancelled: Boolean) {
        val answers = if (cancelled || answersJson.isBlank()) {
            emptyList()
        } else {
            runCatching {
                json.decodeFromString<List<UserQuestionAnswer>>(answersJson)
            }.getOrDefault(emptyList())
        }
        runCatching { bridgeClient.answerQuestion(requestId, answers, cancelled) }
    }

    fun getStatusJson(): String {
        val m = envManager.loadManifest()
        val s = settingsStore.current()
        val snapshot = RuntimeStatusSnapshot(
            state = state.name,
            detail = detail,
            restartCount = crashRestartCount.get(),
            activePrompts = activePrompts.size,
            occupiedSessions = activePrompts.values.distinct(),
            dshVersion = m.dshVersion,
            nodeVersion = m.nodeVersion,
            rootfsReady = m.rootfsReady && launcher.isReady(),
            bridgePingOk = bridgePingOk,
            permissionMode = s.permissionMode.envValue,
            imagePromptSupported = acpClient?.agentCapabilities?.imagePrompt == true
        )
        return json.encodeToString(snapshot)
    }

    fun getRecentLogsJson(maxLines: Int): String {
        val lines = synchronized(logLock) { logRing.toList().takeLast(maxLines.coerceIn(1, 500)) }
        return json.encodeToString(lines)
    }

    fun clearLogs() {
        synchronized(logLock) {
            logRing.clear()
            if (logFile.exists()) runCatching { logFile.writeText("") }
        }
    }

    fun reloadSettings() {
        settingsStore.ensureContainerApiKeySynced()
    }

    private fun requireHealthyClient(): AcpClient {
        val c = acpClient
        check(c != null && !c.isClosed) { "DSH runtime is not running (state=$state)" }
        return c
    }

    private fun updateWakeLock() {
        val needLock = activePrompts.isNotEmpty() && settingsStore.current().wakeLockDuringTurn
        runCatching {
            if (needLock && !wakeLock.isHeld) {
                wakeLock.acquire(10 * 60 * 1000L)
            } else if (!needLock && wakeLock.isHeld) {
                wakeLock.release()
            }
        }
    }

    private fun acquireSingleWriterLock(): Boolean {
        if (fileLock?.isValid == true) return true
        return try {
            val raf = RandomAccessFile(lockFile, "rw")
            val lock = raf.channel.tryLock()
            if (lock == null) {
                raf.close()
                false
            } else {
                lockChannelFile = raf
                fileLock = lock
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun releaseSingleWriterLock() {
        runCatching { fileLock?.release() }
        runCatching { lockChannelFile?.close() }
        fileLock = null
        lockChannelFile = null
    }

    private fun cleanupOrphanPid() {
        if (!pidFile.exists()) return
        val pid = runCatching { pidFile.readText().trim().toInt() }.getOrNull()
        runCatching { pidFile.delete() }
        if (pid == null || pid <= 0 || pid == AndroidProcess.myPid()) return
        val cmdline = runCatching {
            File("/proc/$pid/cmdline").readText(Charsets.UTF_8).replace('\u0000', ' ')
        }.getOrNull() ?: return
        if (cmdline.contains("libbxroot") || cmdline.contains("/opt/node24/bin/dsh")) {
            appendLog("[supervisor] Killing orphaned runtime pid=$pid")
            runCatching { Os.kill(pid, OsConstants.SIGKILL) }
        }
    }

    private fun extractHostPid(proc: Process): Long? =
        runCatching { proc.javaClass.getMethod("pid").invoke(proc) as? Long }.getOrNull()

    private fun updateState(newState: RuntimeLifecycleState, newDetail: String) {
        state = newState
        detail = newDetail
        sink.onStateChanged(newState, newDetail, crashRestartCount.get())
    }

    private fun appendLog(line: String) {
        val clean = line.replace(Regex("""sk-[A-Za-z0-9_-]{12,}"""), "[REDACTED_KEY]")
        synchronized(logLock) {
            if (logRing.size >= 220) logRing.removeFirst()
            logRing.addLast(clean)
            runCatching {
                if (logFile.exists() && logFile.length() > MAX_RUNTIME_LOG_BYTES) {
                    val kept = logRing.toList().takeLast(120).joinToString("\n") + "\n"
                    logFile.writeText(kept, Charsets.UTF_8)
                } else {
                    logFile.appendText(clean + "\n", Charsets.UTF_8)
                }
            }
        }
    }

    private fun serializeSessionUpdate(u: SessionUpdate): String = when (u) {
        is SessionUpdate.AgentMessageChunk -> buildJsonObject {
            put("type", "agent_message_chunk")
            put("sessionId", u.sessionId)
            if (u.messageId != null) put("messageId", u.messageId)
            put("text", u.text)
        }.toString()
        is SessionUpdate.AgentThoughtChunk -> buildJsonObject {
            put("type", "agent_thought_chunk")
            put("sessionId", u.sessionId)
            if (u.messageId != null) put("messageId", u.messageId)
            put("text", u.text)
        }.toString()
        is SessionUpdate.ToolCall -> buildJsonObject {
            put("type", "tool_call")
            put("sessionId", u.sessionId)
            put("toolCallId", u.toolCallId)
            put("title", u.title)
            if (u.kind != null) put("kind", u.kind)
            put("status", u.status)
            if (u.rawInput != null) put("rawInput", u.rawInput)
        }.toString()
        is SessionUpdate.ToolCallUpdate -> buildJsonObject {
            put("type", "tool_call_update")
            put("sessionId", u.sessionId)
            put("toolCallId", u.toolCallId)
            put("status", u.status)
            if (u.content != null) {
                put("content", u.content)
                val extracted = AcpClient.extractToolUpdateContentText(u.content)
                if (extracted.isNotEmpty()) put("contentText", extracted)
            }
        }.toString()
        is SessionUpdate.UsageUpdate -> buildJsonObject {
            put("type", "usage_update")
            put("sessionId", u.sessionId)
            put("used", u.used)
            put("size", u.size)
        }.toString()
        is SessionUpdate.ConfigOptionUpdate -> buildJsonObject {
            put("type", "config_option_update")
            put("sessionId", u.sessionId)
            put("configOptions", Json.parseToJsonElement(json.encodeToString(u.configOptions)))
        }.toString()
        is SessionUpdate.Other -> buildJsonObject {
            put("type", "other")
            put("sessionId", u.sessionId)
            put("raw", u.raw)
        }.toString()
    }

    companion object {
        private const val TAG = "DSHN-Supervisor"
        private const val MAX_CRASHES_PER_MINUTE = 3
        private const val MAX_RUNTIME_LOG_BYTES = 512 * 1024L
    }
}
