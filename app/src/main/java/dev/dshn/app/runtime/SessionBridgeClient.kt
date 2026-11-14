package dev.dshn.app.runtime

import android.net.LocalSocket
import android.net.LocalSocketAddress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

@Serializable
data class SessionSummaryItem(
    val id: String,
    val createdAt: Long = 0L,
    val cwd: String? = null,
    val parentSession: String? = null,
    val isSeeded: Boolean = false,
    val delegationDepth: Int = 0,
    val live: Boolean = false,
    val persisted: Boolean = false,
    val formatStatus: String = "ok",
    val corrupt: Boolean = false,
    val readOnly: Boolean = false,
    val title: String? = null,
    val error: String? = null
)

@Serializable
data class SlashCommandDescriptor(
    val name: String,
    val description: String = ""
)

@Serializable
data class QuestionOption(
    val label: String,
    val description: String? = null
)

@Serializable
data class UserQuestionItem(
    val id: String,
    val question: String,
    val header: String? = null,
    val detail: String? = null,
    val options: List<QuestionOption> = emptyList(),
    val multiSelect: Boolean = false
)

@Serializable
data class UserQuestionRequest(
    val requestId: String,
    val sessionId: String? = null,
    val callId: String? = null,
    val timed: Boolean = false,
    val questions: List<UserQuestionItem> = emptyList()
)

@Serializable
data class UserQuestionAnswer(
    val id: String,
    val selected: List<String>,
    val custom: String? = null
)

sealed interface BridgeEvent {
    data class QuestionRequested(val request: UserQuestionRequest) : BridgeEvent
    data class QuestionCancelled(val requestId: String, val sessionId: String?) : BridgeEvent
}

/**
 * Unix domain socket client talking to `dshn-session-bridge.mjs` running inside the ACP process.
 */
class SessionBridgeClient(
    private val socketFile: File,
    private val scope: CoroutineScope
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val nextId = AtomicLong(1)

    private var eventJob: Job? = null
    @Volatile private var eventSocket: LocalSocket? = null
    @Volatile private var eventOut: OutputStream? = null
    private val pendingOnEventSock = ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()

    private val _events = MutableSharedFlow<BridgeEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<BridgeEvent> = _events

    fun startEventSubscription() {
        if (eventJob?.isActive == true) return
        eventJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                if (!socketFile.exists()) {
                    delay(1000)
                    continue
                }
                val sock = LocalSocket(LocalSocket.SOCKET_STREAM)
                try {
                    sock.connect(LocalSocketAddress(socketFile.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
                    eventSocket = sock
                    eventOut = sock.outputStream
                    val subReq = buildJsonObject {
                        put("id", nextId.getAndIncrement())
                        put("method", "events.subscribe")
                        put("params", buildJsonObject {})
                    }
                    sendLine(sock.outputStream, subReq)

                    val reader = BufferedReader(InputStreamReader(sock.inputStream, Charsets.UTF_8))
                    while (isActive) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank()) continue
                        val obj = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
                        val id = obj["id"]?.jsonPrimitive?.longOrNull
                        val ev = obj["event"]?.jsonPrimitive?.contentOrNull
                        if (id != null) {
                            pendingOnEventSock.remove(id)?.complete(obj)
                        } else if (ev != null) {
                            handlePushEvent(ev, obj["params"]?.jsonObject ?: buildJsonObject {})
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    eventSocket = null
                    eventOut = null
                    pendingOnEventSock.values.forEach { it.cancel() }
                    pendingOnEventSock.clear()
                    runCatching { sock.close() }
                }
                delay(1200)
            }
        }
    }

    fun stopEventSubscription() {
        eventJob?.cancel()
        eventJob = null
        runCatching { eventSocket?.close() }
        eventSocket = null
        eventOut = null
    }

    private fun handlePushEvent(event: String, params: JsonObject) {
        when (event) {
            "question.request" -> {
                val qArr = runCatching { params["questions"]?.jsonArray }.getOrNull().orEmpty()
                val questions = qArr.mapNotNull { elem ->
                    val q = runCatching { elem.jsonObject }.getOrNull() ?: return@mapNotNull null
                    val opts = runCatching { q["options"]?.jsonArray }.getOrNull().orEmpty().mapNotNull { o ->
                        val oo = runCatching { o.jsonObject }.getOrNull() ?: return@mapNotNull null
                        QuestionOption(
                            label = oo["label"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                            description = oo["description"]?.jsonPrimitive?.contentOrNull
                        )
                    }
                    UserQuestionItem(
                        id = q["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                        question = q["question"]?.jsonPrimitive?.contentOrNull ?: "",
                        header = q["header"]?.jsonPrimitive?.contentOrNull,
                        detail = q["detail"]?.jsonPrimitive?.contentOrNull,
                        options = opts,
                        multiSelect = q["multiSelect"]?.jsonPrimitive?.booleanOrNull ?: false
                    )
                }
                val req = UserQuestionRequest(
                    requestId = params["requestId"]?.jsonPrimitive?.contentOrNull ?: return,
                    sessionId = params["sessionId"]?.jsonPrimitive?.contentOrNull,
                    callId = params["callId"]?.jsonPrimitive?.contentOrNull,
                    timed = params["timed"]?.jsonPrimitive?.booleanOrNull ?: false,
                    questions = questions
                )
                _events.tryEmit(BridgeEvent.QuestionRequested(req))
            }
            "question.cancelled" -> {
                val rid = params["requestId"]?.jsonPrimitive?.contentOrNull ?: return
                _events.tryEmit(BridgeEvent.QuestionCancelled(rid, params["sessionId"]?.jsonPrimitive?.contentOrNull))
            }
        }
    }

    suspend fun answerQuestion(
        requestId: String,
        answers: List<UserQuestionAnswer>,
        cancelled: Boolean = false
    ): JsonObject = call(
        method = "questions.answer",
        params = buildJsonObject {
            put("requestId", requestId)
            put("cancelled", cancelled)
            putJsonArray("answers") {
                for (a in answers) {
                    add(buildJsonObject {
                        put("id", a.id)
                        putJsonArray("selected") { a.selected.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
                        if (a.custom != null) put("custom", a.custom)
                    })
                }
            }
        }
    )

    suspend fun ping(timeoutMs: Long = 3_000): Boolean = runCatching {
        val r = call("ping", buildJsonObject {}, timeoutMs = timeoutMs)
        r["ok"]?.jsonPrimitive?.booleanOrNull == true
    }.getOrDefault(false)

    suspend fun pingResult(timeoutMs: Long = 3_000): Result<Boolean> = runCatching {
        val r = call("ping", buildJsonObject {}, timeoutMs = timeoutMs)
        r["ok"]?.jsonPrimitive?.booleanOrNull == true
    }

    suspend fun listSessions(): List<SessionSummaryItem> {
        val r = call("sessions.list", buildJsonObject {}, timeoutMs = 15_000)
        val arr = r["sessions"]?.jsonArray ?: return emptyList()
        return arr.mapNotNull { elem ->
            runCatching { json.decodeFromJsonElement(SessionSummaryItem.serializer(), elem) }.getOrNull()
        }
    }

    suspend fun readSurfaceJson(sessionId: String): JsonObject =
        call("sessions.surface", buildJsonObject { put("sessionId", sessionId) }, timeoutMs = 30_000)

    suspend fun listCommands(sessionId: String?): List<SlashCommandDescriptor> {
        val r = call("commands.list", buildJsonObject {
            if (sessionId != null) put("sessionId", sessionId)
        })
        val arr = r["commands"]?.jsonArray ?: return emptyList()
        return arr.mapNotNull { elem ->
            val obj = runCatching { elem.jsonObject }.getOrNull() ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            SlashCommandDescriptor(
                name = name,
                description = obj["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
            )
        }
    }

    suspend fun executeCommand(sessionId: String, line: String): JsonObject =
        call("commands.execute", buildJsonObject {
            put("sessionId", sessionId)
            put("line", line)
        }, timeoutMs = 60_000)

    suspend fun call(
        method: String,
        params: JsonObject,
        timeoutMs: Long = 10_000
    ): JsonObject = withContext(Dispatchers.IO) {
        if (!socketFile.exists()) {
            throw IllegalStateException("Session bridge socket missing: ${socketFile.name}")
        }
        val sock = LocalSocket(LocalSocket.SOCKET_STREAM)
        try {
            sock.connect(LocalSocketAddress(socketFile.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
            // On Android LocalSocket, SO_TIMEOUT must be configured after connect() creates the fd.
            sock.soTimeout = timeoutMs.toInt()
            val id = nextId.getAndIncrement()
            sendLine(sock.outputStream, buildJsonObject {
                put("id", id)
                put("method", method)
                put("params", params)
            })
            val reader = BufferedReader(InputStreamReader(sock.inputStream, Charsets.UTF_8))
            while (true) {
                val line = reader.readLine() ?: throw IllegalStateException("EOF waiting for $method")
                if (line.isBlank()) continue
                val resp = json.parseToJsonElement(line).jsonObject
                if (resp["id"]?.jsonPrimitive?.longOrNull != id) continue
                resp["error"]?.let { err ->
                    val eo = err.jsonObject
                    throw IllegalStateException(
                        "${eo["code"]?.jsonPrimitive?.contentOrNull ?: "ERROR"}: ${eo["message"]?.jsonPrimitive?.contentOrNull}"
                    )
                }
                return@withContext resp["result"]?.jsonObject ?: buildJsonObject {}
            }
            @Suppress("UNREACHABLE_CODE")
            buildJsonObject {}
        } catch (ise: IllegalStateException) {
            throw ise
        } catch (t: Throwable) {
            // Wrap checked IO/socket errors in IllegalStateException so AIDL Binder preserves the message.
            throw IllegalStateException("Session bridge $method failed: ${t.message ?: t::class.java.simpleName}", t)
        } finally {
            runCatching { sock.close() }
        }
    }

    private fun sendLine(out: OutputStream, obj: JsonObject) {
        synchronized(out) {
            out.write((obj.toString() + "\n").toByteArray(Charsets.UTF_8))
            out.flush()
        }
    }
}
