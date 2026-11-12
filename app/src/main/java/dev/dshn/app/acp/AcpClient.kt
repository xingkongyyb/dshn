package dev.dshn.app.acp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** ACP 会话更新（session/update 通知的 update 字段）。 */
sealed interface SessionUpdate {
    data class AgentMessageChunk(val messageId: String?, val text: String) : SessionUpdate
    data class AgentThoughtChunk(val messageId: String?, val text: String) : SessionUpdate
    data class ToolCall(val toolCallId: String, val title: String, val status: String, val rawInput: JsonElement?) : SessionUpdate
    data class ToolCallUpdate(val toolCallId: String, val status: String, val content: JsonElement?) : SessionUpdate
    data class UsageUpdate(val used: Long, val size: Long) : SessionUpdate
    data class Other(val raw: JsonObject) : SessionUpdate
}

/** 服务端审批请求（session/request_permission）。 */
data class PermissionRequest(
    val sessionId: String,
    val toolCallId: String?,
    val title: String?,
    val options: List<PermissionOption>,
)
data class PermissionOption(val optionId: String, val name: String, val kind: String?)
data class PermissionOutcome(val optionId: String)

data class NewSessionResult(val sessionId: String, val configOptions: JsonElement?)
data class PromptResult(val stopReason: String?)

/**
 * 纯 Kotlin ACP（Agent Client Protocol）客户端，stdio 换行分帧 JSON-RPC 2.0。
 * 无 Android 依赖，可在 CI 对真 DSH 跑一致性测试。
 */
class AcpClient(
    input: InputStream,
    private val output: OutputStream,
    private val scope: CoroutineScope,
    private val onPermissionRequest: suspend (PermissionRequest) -> PermissionOutcome,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val reader = BufferedReader(InputStreamReader(input))
    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()
    private val _updates = MutableSharedFlow<SessionUpdate>(extraBufferCapacity = 256)
    val updates: SharedFlow<SessionUpdate> = _updates
    @Volatile private var closed = false

    init {
        scope.launch(Dispatchers.IO) { readLoop() }
    }

    private fun readLoop() {
        try {
            while (!closed) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val msg = try { json.parseToJsonElement(line).jsonObject } catch (_: Exception) { continue }
                dispatch(msg)
            }
        } catch (_: Exception) {
        } finally {
            closed = true
            pending.values.forEach { it.cancel() }
            pending.clear()
        }
    }

    private fun dispatch(msg: JsonObject) {
        val id = msg["id"]?.jsonPrimitive?.content?.toLongOrNull()
        val method = msg["method"]?.jsonPrimitive?.content
        when {
            // 响应：结算挂起请求
            id != null && method == null -> {
                pending.remove(id)?.complete(msg)
            }
            // 服务端请求：审批
            id != null && method == "session/request_permission" -> {
                scope.launch(Dispatchers.IO) { handlePermission(id, msg["params"]?.jsonObject) }
            }
            id != null && method != null -> {
                // 其他服务端请求：回空结果（探测兼容）
                send(buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", buildJsonObject {}) })
            }
            // 通知：session/update
            method == "session/update" -> {
                val params = msg["params"]?.jsonObject ?: return
                val update = params["update"]?.jsonObject ?: return
                _updates.tryEmit(parseUpdate(update))
            }
        }
    }

    private fun parseUpdate(u: JsonObject): SessionUpdate {
        val type = u["sessionUpdate"]?.jsonPrimitive?.content
        val contentText = u["content"]?.jsonObject?.get("text")?.jsonPrimitive?.content
        return when (type) {
            "agent_message_chunk" -> SessionUpdate.AgentMessageChunk(u["messageId"]?.jsonPrimitive?.content, contentText ?: "")
            "agent_thought_chunk" -> SessionUpdate.AgentThoughtChunk(u["messageId"]?.jsonPrimitive?.content, contentText ?: "")
            "tool_call" -> SessionUpdate.ToolCall(
                u["toolCallId"]?.jsonPrimitive?.content ?: "",
                u["title"]?.jsonPrimitive?.content ?: "",
                u["status"]?.jsonPrimitive?.content ?: "",
                u["rawInput"],
            )
            "tool_call_update" -> SessionUpdate.ToolCallUpdate(
                u["toolCallId"]?.jsonPrimitive?.content ?: "",
                u["status"]?.jsonPrimitive?.content ?: "",
                u["content"],
            )
            "usage_update" -> SessionUpdate.UsageUpdate(
                u["used"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0,
                u["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0,
            )
            else -> SessionUpdate.Other(u)
        }
    }

    private suspend fun handlePermission(id: Long, params: JsonObject?) {
        val p = params ?: return
        val toolCall = p["toolCall"]?.jsonObject
        val options = (p["options"]?.jsonArray ?: emptyList()).map {
            val o = it.jsonObject
            PermissionOption(
                o["optionId"]?.jsonPrimitive?.content ?: "",
                o["name"]?.jsonPrimitive?.content ?: "",
                o["kind"]?.jsonPrimitive?.content,
            )
        }
        val req = PermissionRequest(
            p["sessionId"]?.jsonPrimitive?.content ?: "",
            toolCall?.get("toolCallId")?.jsonPrimitive?.content,
            toolCall?.get("title")?.jsonPrimitive?.content,
            options,
        )
        val outcome = try { onPermissionRequest(req) } catch (_: Exception) { PermissionOutcome(options.firstOrNull()?.optionId ?: "") }
        send(buildJsonObject {
            put("jsonrpc", "2.0"); put("id", id)
            put("result", buildJsonObject {
                put("outcome", buildJsonObject { put("outcome", "selected"); put("optionId", outcome.optionId) })
            })
        })
    }

    private fun send(obj: JsonObject) {
        val line = obj.toString() + "\n"
        synchronized(output) { output.write(line.toByteArray()); output.flush() }
    }

    private suspend fun request(method: String, params: JsonObject, timeoutMs: Long = 120_000): JsonObject {
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<JsonObject>()
        pending[id] = deferred
        send(buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params) })
        val resp = withTimeout(timeoutMs) { deferred.await() }
        resp["error"]?.let { throw AcpException(it.jsonObject["message"]?.jsonPrimitive?.content ?: "ACP error") }
        return resp["result"]?.jsonObject ?: buildJsonObject {}
    }

    private fun notify(method: String, params: JsonObject) {
        send(buildJsonObject { put("jsonrpc", "2.0"); put("method", method); put("params", params) })
    }

    suspend fun initialize(): JsonObject = request("initialize", buildJsonObject {
        put("protocolVersion", 1)
        put("clientCapabilities", buildJsonObject {})
    })

    suspend fun newSession(cwd: String): NewSessionResult {
        val r = request("session/new", buildJsonObject { put("cwd", cwd); putJsonArray("mcpServers") {} })
        return NewSessionResult(r["sessionId"]?.jsonPrimitive?.content ?: "", r["configOptions"])
    }

    suspend fun prompt(sessionId: String, text: String): PromptResult {
        val r = request("session/prompt", buildJsonObject {
            put("sessionId", sessionId)
            putJsonArray("prompt") { add(buildJsonObject { put("type", "text"); put("text", text) }) }
        }, timeoutMs = 600_000)
        return PromptResult(r["stopReason"]?.jsonPrimitive?.content)
    }

    fun cancel(sessionId: String) = notify("session/cancel", buildJsonObject { put("sessionId", sessionId) })

    suspend fun listSessions(): JsonObject = request("session/list", buildJsonObject {})
    suspend fun closeSession(sessionId: String) { request("session/close", buildJsonObject { put("sessionId", sessionId) }) }
    suspend fun setConfigOption(sessionId: String, configId: String, value: String): JsonObject =
        request("session/set_config_option", buildJsonObject { put("sessionId", sessionId); put("configId", configId); put("value", value) })

    fun close() { closed = true; runCatching { reader.close() } }
}

class AcpException(message: String) : Exception(message)