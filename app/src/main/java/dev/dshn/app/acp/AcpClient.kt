package dev.dshn.app.acp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** ACP session/update payload tagged with its owning sessionId. */
sealed interface SessionUpdate {
    val sessionId: String

    data class AgentMessageChunk(
        override val sessionId: String,
        val messageId: String?,
        val text: String
    ) : SessionUpdate

    data class AgentThoughtChunk(
        override val sessionId: String,
        val messageId: String?,
        val text: String
    ) : SessionUpdate

    data class ToolCall(
        override val sessionId: String,
        val toolCallId: String,
        val title: String,
        val kind: String?,
        val status: String,
        val rawInput: JsonElement?
    ) : SessionUpdate

    data class ToolCallUpdate(
        override val sessionId: String,
        val toolCallId: String,
        val status: String,
        val content: JsonElement?
    ) : SessionUpdate

    data class UsageUpdate(
        override val sessionId: String,
        val used: Long,
        val size: Long
    ) : SessionUpdate

    data class ConfigOptionUpdate(
        override val sessionId: String,
        val configOptions: List<AcpConfigOption>
    ) : SessionUpdate

    data class Other(
        override val sessionId: String,
        val raw: JsonObject
    ) : SessionUpdate
}

@Serializable
data class AcpConfigChoice(
    val value: String,
    val name: String,
    val description: String = "",
    val group: String = ""
)

@Serializable
data class AcpConfigOption(
    val id: String,
    val name: String,
    val category: String = "",
    val type: String = "select",
    val currentValue: String = "",
    val choices: List<AcpConfigChoice> = emptyList()
)

sealed interface McpServerConfig {
    val name: String

    /**
     * Note: DSH `dsh-acp` validates `!('type' in server)` and `isAbsolute(server.command)`
     * for stdio MCP servers, and `"type" == "http"` for HTTP MCP servers.
     */
    data class Stdio(
        override val name: String,
        val command: String,
        val args: List<String> = emptyList(),
        val env: Map<String, String> = emptyMap()
    ) : McpServerConfig

    data class Http(
        override val name: String,
        val url: String,
        val headers: Map<String, String> = emptyMap()
    ) : McpServerConfig
}

/** Server-initiated approval request (`session/request_permission`). */
data class PermissionRequest(
    val sessionId: String,
    val toolCallId: String?,
    val title: String?,
    val options: List<PermissionOption>,
)

data class PermissionOption(val optionId: String, val name: String, val kind: String?)
data class PermissionOutcome(val optionId: String, val cancelled: Boolean = false)

data class NewSessionResult(
    val sessionId: String,
    val configOptions: List<AcpConfigOption>,
    val rawConfigOptions: JsonElement?
)

data class PromptResult(val stopReason: String?)

/**
 * Pure Kotlin ACP (Agent Client Protocol) client over newline-delimited JSON-RPC 2.0 stdio.
 */
class AcpClient(
    input: InputStream,
    private val output: OutputStream,
    private val scope: CoroutineScope,
    private val onPermissionRequest: suspend (PermissionRequest) -> PermissionOutcome,
    private val onStreamDegraded: ((badLines: Int, sample: String) -> Unit)? = null,
    private val onStreamClosed: ((Throwable?) -> Unit)? = null
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()
    private val _updates = MutableSharedFlow<SessionUpdate>(extraBufferCapacity = 512)
    val updates: SharedFlow<SessionUpdate> = _updates

    private val consecutiveBadLines = AtomicInteger(0)
    @Volatile var totalBadLines: Int = 0
        private set
    @Volatile var isClosed: Boolean = false
        private set

    init {
        scope.launch(Dispatchers.IO) { readLoop() }
    }

    private fun readLoop() {
        var exitError: Throwable? = null
        try {
            while (!isClosed) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val msg = try {
                    json.parseToJsonElement(line).jsonObject
                } catch (e: Exception) {
                    totalBadLines += 1
                    val badCount = consecutiveBadLines.incrementAndGet()
                    if (badCount >= BAD_LINE_DEGRADED_THRESHOLD) {
                        onStreamDegraded?.invoke(badCount, line.take(160))
                    }
                    continue
                }
                consecutiveBadLines.set(0)
                dispatch(msg)
            }
        } catch (t: Throwable) {
            if (!isClosed) exitError = t
        } finally {
            isClosed = true
            pending.values.forEach { it.cancel() }
            pending.clear()
            onStreamClosed?.invoke(exitError)
        }
    }

    private fun dispatch(msg: JsonObject) {
        val id = msg["id"]?.jsonPrimitive?.content?.toLongOrNull()
        val method = msg["method"]?.jsonPrimitive?.content
        when {
            id != null && method == null -> {
                pending.remove(id)?.complete(msg)
            }
            id != null && method == "session/request_permission" -> {
                scope.launch(Dispatchers.IO) { handlePermission(id, msg["params"]?.jsonObject) }
            }
            id != null && method != null -> {
                send(buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", id)
                    put("result", buildJsonObject {})
                })
            }
            method == "session/update" -> {
                val params = msg["params"]?.jsonObject ?: return
                val sessionId = params["sessionId"]?.jsonPrimitive?.content ?: ""
                val update = params["update"]?.jsonObject ?: return
                _updates.tryEmit(parseUpdate(sessionId, update))
            }
        }
    }

    private fun parseUpdate(sessionId: String, u: JsonObject): SessionUpdate {
        val type = u["sessionUpdate"]?.jsonPrimitive?.content
        val contentText = u["content"]?.jsonObject?.get("text")?.jsonPrimitive?.content
        return when (type) {
            "agent_message_chunk" -> SessionUpdate.AgentMessageChunk(
                sessionId = sessionId,
                messageId = u["messageId"]?.jsonPrimitive?.content,
                text = contentText ?: ""
            )
            "agent_thought_chunk" -> SessionUpdate.AgentThoughtChunk(
                sessionId = sessionId,
                messageId = u["messageId"]?.jsonPrimitive?.content,
                text = contentText ?: ""
            )
            "tool_call" -> SessionUpdate.ToolCall(
                sessionId = sessionId,
                toolCallId = u["toolCallId"]?.jsonPrimitive?.content ?: "",
                title = u["title"]?.jsonPrimitive?.content ?: "",
                kind = u["kind"]?.jsonPrimitive?.content,
                status = u["status"]?.jsonPrimitive?.content ?: "",
                rawInput = u["rawInput"],
            )
            "tool_call_update" -> SessionUpdate.ToolCallUpdate(
                sessionId = sessionId,
                toolCallId = u["toolCallId"]?.jsonPrimitive?.content ?: "",
                status = u["status"]?.jsonPrimitive?.content ?: "",
                content = u["content"],
            )
            "usage_update" -> SessionUpdate.UsageUpdate(
                sessionId = sessionId,
                used = u["used"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                size = u["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
            )
            "config_option_update" -> SessionUpdate.ConfigOptionUpdate(
                sessionId = sessionId,
                configOptions = parseConfigOptions(u["configOptions"])
            )
            else -> SessionUpdate.Other(sessionId = sessionId, raw = u)
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
            sessionId = p["sessionId"]?.jsonPrimitive?.content ?: "",
            toolCallId = toolCall?.get("toolCallId")?.jsonPrimitive?.content,
            title = toolCall?.get("title")?.jsonPrimitive?.content,
            options = options,
        )
        val outcome = try {
            onPermissionRequest(req)
        } catch (_: Exception) {
            PermissionOutcome(optionId = "", cancelled = true)
        }
        send(buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", buildJsonObject {
                put("outcome", buildJsonObject {
                    if (outcome.cancelled || outcome.optionId.isBlank()) {
                        put("outcome", "cancelled")
                    } else {
                        put("outcome", "selected")
                        put("optionId", outcome.optionId)
                    }
                })
            })
        })
    }

    private fun send(obj: JsonObject) {
        val bytes = (obj.toString() + "\n").toByteArray(Charsets.UTF_8)
        synchronized(output) {
            output.write(bytes)
            output.flush()
        }
    }

    private suspend fun request(method: String, params: JsonObject, timeoutMs: Long = 120_000): JsonObject {
        if (isClosed) throw AcpException("ACP stream closed")
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<JsonObject>()
        pending[id] = deferred
        try {
            send(buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                put("params", params)
            })
        } catch (t: Throwable) {
            pending.remove(id)
            throw AcpException("ACP write failed: ${t.message}")
        }
        val resp = withTimeout(timeoutMs) { deferred.await() }
        resp["error"]?.let {
            throw AcpException(it.jsonObject["message"]?.jsonPrimitive?.content ?: "ACP error")
        }
        return resp["result"]?.jsonObject ?: buildJsonObject {}
    }

    private fun notify(method: String, params: JsonObject) {
        if (isClosed) return
        send(buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
            put("params", params)
        })
    }

    suspend fun initialize(timeoutMs: Long = 60_000): JsonObject = request(
        "initialize",
        buildJsonObject {
            put("protocolVersion", 1)
            put("clientCapabilities", buildJsonObject {})
        },
        timeoutMs = timeoutMs
    )

    suspend fun newSession(cwd: String, mcpServers: List<McpServerConfig> = emptyList()): NewSessionResult {
        val r = request("session/new", buildJsonObject {
            put("cwd", cwd)
            putJsonArray("mcpServers") {
                mcpServers.forEach { add(encodeMcpServer(it)) }
            }
        })
        val rawCfg = r["configOptions"]
        return NewSessionResult(
            sessionId = r["sessionId"]?.jsonPrimitive?.content ?: "",
            configOptions = parseConfigOptions(rawCfg),
            rawConfigOptions = rawCfg
        )
    }

    suspend fun resumeSession(
        sessionId: String,
        cwd: String,
        mcpServers: List<McpServerConfig> = emptyList()
    ): NewSessionResult {
        val r = request("session/resume", buildJsonObject {
            put("sessionId", sessionId)
            put("cwd", cwd)
            putJsonArray("mcpServers") {
                mcpServers.forEach { add(encodeMcpServer(it)) }
            }
        })
        val rawCfg = r["configOptions"]
        return NewSessionResult(
            sessionId = r["sessionId"]?.jsonPrimitive?.content ?: sessionId,
            configOptions = parseConfigOptions(rawCfg),
            rawConfigOptions = rawCfg
        )
    }

    suspend fun prompt(sessionId: String, text: String): PromptResult {
        val r = request("session/prompt", buildJsonObject {
            put("sessionId", sessionId)
            putJsonArray("prompt") {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", text)
                })
            }
        }, timeoutMs = 600_000)
        return PromptResult(r["stopReason"]?.jsonPrimitive?.content)
    }

    fun cancel(sessionId: String) = notify("session/cancel", buildJsonObject { put("sessionId", sessionId) })

    suspend fun listSessions(cwd: String? = null, cursor: String? = null): JsonObject =
        request("session/list", buildJsonObject {
            if (cwd != null) put("cwd", cwd)
            if (cursor != null) put("cursor", cursor)
        })

    suspend fun closeSession(sessionId: String) {
        request("session/close", buildJsonObject { put("sessionId", sessionId) })
    }

    suspend fun setConfigOption(sessionId: String, configId: String, value: String): List<AcpConfigOption> {
        val r = request("session/set_config_option", buildJsonObject {
            put("sessionId", sessionId)
            put("configId", configId)
            put("value", value)
        })
        return parseConfigOptions(r["configOptions"])
    }

    fun close() {
        isClosed = true
        pending.values.forEach { it.cancel() }
        pending.clear()
        runCatching { reader.close() }
        runCatching { output.close() }
    }

    companion object {
        const val BAD_LINE_DEGRADED_THRESHOLD = 3

        fun encodeMcpServer(server: McpServerConfig): JsonObject = when (server) {
            is McpServerConfig.Stdio -> buildJsonObject {
                put("name", server.name)
                put("command", server.command)
                putJsonArray("args") { server.args.forEach { add(it) } }
                putJsonArray("env") {
                    server.env.forEach { (k, v) ->
                        add(buildJsonObject {
                            put("name", k)
                            put("value", v)
                        })
                    }
                }
            }
            is McpServerConfig.Http -> buildJsonObject {
                put("type", "http")
                put("name", server.name)
                put("url", server.url)
                putJsonArray("headers") {
                    server.headers.forEach { (k, v) ->
                        add(buildJsonObject {
                            put("name", k)
                            put("value", v)
                        })
                    }
                }
            }
        }

        fun parseConfigOptions(raw: JsonElement?): List<AcpConfigOption> {
            val arr = runCatching { raw?.jsonArray }.getOrNull() ?: return emptyList()
            return arr.mapNotNull { elem ->
                val obj = runCatching { elem.jsonObject }.getOrNull() ?: return@mapNotNull null
                val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val choices = mutableListOf<AcpConfigChoice>()
                val rawOptions = runCatching { obj["options"]?.jsonArray }.getOrNull().orEmpty()
                for (entry in rawOptions) {
                    val o = runCatching { entry.jsonObject }.getOrNull() ?: continue
                    val nested = runCatching { o["options"]?.jsonArray }.getOrNull()
                    if (nested != null) {
                        val groupName = o["name"]?.jsonPrimitive?.content
                            ?: o["group"]?.jsonPrimitive?.content
                            ?: ""
                        for (sub in nested) {
                            val so = runCatching { sub.jsonObject }.getOrNull() ?: continue
                            val v = so["value"]?.jsonPrimitive?.content ?: continue
                            choices += AcpConfigChoice(
                                value = v,
                                name = so["name"]?.jsonPrimitive?.content ?: v,
                                description = so["description"]?.jsonPrimitive?.content ?: "",
                                group = groupName
                            )
                        }
                    } else {
                        val v = o["value"]?.jsonPrimitive?.content ?: continue
                        choices += AcpConfigChoice(
                            value = v,
                            name = o["name"]?.jsonPrimitive?.content ?: v,
                            description = o["description"]?.jsonPrimitive?.content ?: "",
                            group = ""
                        )
                    }
                }
                AcpConfigOption(
                    id = id,
                    name = obj["name"]?.jsonPrimitive?.content ?: id,
                    category = obj["category"]?.jsonPrimitive?.content ?: "",
                    type = obj["type"]?.jsonPrimitive?.content ?: "select",
                    currentValue = obj["currentValue"]?.jsonPrimitive?.content ?: "",
                    choices = choices
                )
            }
        }
    }
}

class AcpException(message: String) : Exception(message)
