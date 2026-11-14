package dev.dshn.app.runtime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

sealed interface RuntimeCallbackEvent {
    data class SessionUpdateJson(val sessionId: String, val jsonUpdate: String) : RuntimeCallbackEvent
    data class PermissionRequested(
        val requestId: String,
        val sessionId: String,
        val toolCallId: String?,
        val title: String?,
        val optionsJson: String
    ) : RuntimeCallbackEvent
    data class QuestionRequested(val questionRequestJson: String) : RuntimeCallbackEvent
    data class QuestionCancelled(val requestId: String, val sessionId: String?) : RuntimeCallbackEvent
    data class PromptCompleted(
        val requestId: String,
        val sessionId: String,
        val stopReason: String?,
        val errorMessage: String?
    ) : RuntimeCallbackEvent
    data class InterruptedTurnUnknown(val sessionId: String, val message: String) : RuntimeCallbackEvent
    data class StderrLine(val line: String) : RuntimeCallbackEvent
}

data class RuntimeUiStatus(
    val bound: Boolean = false,
    val state: RuntimeLifecycleState = RuntimeLifecycleState.STOPPED,
    val detail: String = "Disconnected",
    val restartCount: Int = 0
)

/**
 * `:app`-process client binding to [RuntimeService] over AIDL with safe IO dispatch.
 */
class RuntimeController(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    @Volatile private var service: IRuntimeService? = null
    @Volatile private var serviceReady = CompletableDeferred<IRuntimeService>()

    private val _status = MutableStateFlow(RuntimeUiStatus())
    val status: StateFlow<RuntimeUiStatus> = _status.asStateFlow()

    private val _events = MutableSharedFlow<RuntimeCallbackEvent>(extraBufferCapacity = 512)
    val events: SharedFlow<RuntimeCallbackEvent> = _events

    private val callback = object : IRuntimeCallback.Stub() {
        override fun onStateChanged(state: String, detail: String, restartCount: Int) {
            val parsed = runCatching { RuntimeLifecycleState.valueOf(state) }.getOrDefault(RuntimeLifecycleState.STOPPED)
            _status.value = _status.value.copy(
                bound = true,
                state = parsed,
                detail = detail,
                restartCount = restartCount
            )
        }

        override fun onSessionUpdate(sessionId: String, jsonUpdate: String) {
            _events.tryEmit(RuntimeCallbackEvent.SessionUpdateJson(sessionId, jsonUpdate))
        }

        override fun onPermissionRequested(
            requestId: String,
            sessionId: String,
            toolCallId: String?,
            title: String?,
            optionsJson: String
        ) {
            _events.tryEmit(RuntimeCallbackEvent.PermissionRequested(requestId, sessionId, toolCallId, title, optionsJson))
        }

        override fun onQuestionRequested(questionRequestJson: String) {
            _events.tryEmit(RuntimeCallbackEvent.QuestionRequested(questionRequestJson))
        }

        override fun onQuestionCancelled(requestId: String, sessionId: String?) {
            _events.tryEmit(RuntimeCallbackEvent.QuestionCancelled(requestId, sessionId))
        }

        override fun onPromptCompleted(
            requestId: String,
            sessionId: String,
            stopReason: String?,
            errorMessage: String?
        ) {
            _events.tryEmit(RuntimeCallbackEvent.PromptCompleted(requestId, sessionId, stopReason, errorMessage))
        }

        override fun onInterruptedTurnUnknown(sessionId: String, message: String) {
            _events.tryEmit(RuntimeCallbackEvent.InterruptedTurnUnknown(sessionId, message))
        }

        override fun onStderrLine(line: String) {
            _events.tryEmit(RuntimeCallbackEvent.StderrLine(line))
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = IRuntimeService.Stub.asInterface(binder)
            service = svc
            runCatching { svc.registerCallback(callback) }
            if (!serviceReady.isCompleted) serviceReady.complete(svc)
            _status.value = _status.value.copy(bound = true)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            serviceReady = CompletableDeferred()
            _status.value = _status.value.copy(
                bound = false,
                state = RuntimeLifecycleState.ERROR,
                detail = ":runtime process disconnected"
            )
        }
    }

    fun bind() {
        val intent = Intent(context, RuntimeService::class.java)
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    fun unbind() {
        runCatching { service?.unregisterCallback(callback) }
        runCatching { context.unbindService(connection) }
        service = null
    }

    private suspend fun requireService(): IRuntimeService {
        service?.let { return it }
        bind()
        return withTimeout(10_000) { serviceReady.await() }
    }

    suspend fun startRuntime() = withContext(Dispatchers.IO) {
        val intent = Intent(context, RuntimeService::class.java)
        context.startForegroundService(intent)
        requireService().startRuntime()
    }

    suspend fun stopRuntime() = withContext(Dispatchers.IO) {
        service?.stopRuntime()
    }

    suspend fun restartRuntime() = withContext(Dispatchers.IO) {
        val intent = Intent(context, RuntimeService::class.java)
        context.startForegroundService(intent)
        requireService().restartRuntime()
    }

    suspend fun newSession(cwd: String): JsonObject = withContext(Dispatchers.IO) {
        val raw = requireService().newSession(cwd)
        json.parseToJsonElement(raw).jsonObject
    }

    suspend fun resumeSession(sessionId: String, cwd: String): JsonObject = withContext(Dispatchers.IO) {
        val raw = requireService().resumeSession(sessionId, cwd)
        json.parseToJsonElement(raw).jsonObject
    }

    suspend fun closeSession(sessionId: String) = withContext(Dispatchers.IO) {
        requireService().closeSession(sessionId)
    }

    suspend fun setConfigOption(sessionId: String, configId: String, value: String): String = withContext(Dispatchers.IO) {
        requireService().setConfigOption(sessionId, configId, value)
    }

    suspend fun listSessionsFromBridge(): List<SessionSummaryItem> = withContext(Dispatchers.IO) {
        val raw = requireService().listSessionsFromBridge()
        json.decodeFromString(raw)
    }

    suspend fun readSessionSurfaceFromBridge(sessionId: String): JsonObject = withContext(Dispatchers.IO) {
        val encoded = requireService().readSessionSurfaceFromBridge(sessionId)
        val raw = LargePayloadXfer.decode(encoded)
        json.parseToJsonElement(raw).jsonObject
    }

    suspend fun listCommandsFromBridge(sessionId: String?): List<SlashCommandDescriptor> = withContext(Dispatchers.IO) {
        val raw = requireService().listCommandsFromBridge(sessionId)
        json.decodeFromString(raw)
    }

    suspend fun executeCommandFromBridge(sessionId: String, line: String): JsonObject = withContext(Dispatchers.IO) {
        val raw = requireService().executeCommandFromBridge(sessionId, line)
        json.parseToJsonElement(raw).jsonObject
    }

    suspend fun sendPrompt(requestId: String, sessionId: String, text: String) = withContext(Dispatchers.IO) {
        requireService().sendPrompt(requestId, sessionId, text)
    }

    suspend fun cancelPrompt(sessionId: String) = withContext(Dispatchers.IO) {
        service?.cancelPrompt(sessionId)
    }

    suspend fun replyPermission(requestId: String, optionId: String, cancelled: Boolean) = withContext(Dispatchers.IO) {
        service?.replyPermission(requestId, optionId, cancelled)
    }

    suspend fun replyQuestion(requestId: String, answers: List<UserQuestionAnswer>, cancelled: Boolean) =
        withContext(Dispatchers.IO) {
            val encoded = json.encodeToString(answers)
            service?.replyQuestion(requestId, encoded, cancelled)
        }

    suspend fun getStatusSnapshot(): RuntimeStatusSnapshot? = withContext(Dispatchers.IO) {
        val raw = service?.getStatusJson() ?: return@withContext null
        runCatching { json.decodeFromString<RuntimeStatusSnapshot>(raw) }.getOrNull()
    }

    suspend fun getRecentLogs(maxLines: Int = 200): List<String> = withContext(Dispatchers.IO) {
        val raw = service?.getRecentLogsJson(maxLines) ?: return@withContext emptyList()
        runCatching { json.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())
    }

    suspend fun clearLogs() = withContext(Dispatchers.IO) {
        service?.clearLogs()
    }

    suspend fun reloadSettings() = withContext(Dispatchers.IO) {
        service?.reloadSettings()
    }
}
