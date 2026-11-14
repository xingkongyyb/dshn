package dev.dshn.app.acp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

sealed interface HistoryBlock {
    data class Text(val text: String, val thinking: String = "") : HistoryBlock
    data class ToolUse(
        val id: String,
        val name: String,
        val arguments: String,
        val result: String? = null,
        val isError: Boolean = false,
        val outcomeUnknown: Boolean = false
    ) : HistoryBlock
}

data class HistoryTurn(
    val id: String,
    val role: String, // "user", "assistant", "system"
    val seq: Long = 0,
    val text: String = "",
    val thinking: String = "",
    val tools: List<HistoryBlock.ToolUse> = emptyList(),
    val interruptedUnknown: Boolean = false
)

/**
 * Converts `sessionQuery.readSurface()` events into UI transcript turns while tracking
 * paired tool calls/results and unknown tool outcomes (`TOOL_OUTCOME_UNKNOWN`).
 */
object SurfaceHistoryFold {
    fun foldEvents(events: JsonArray): List<HistoryTurn> {
        val turns = mutableListOf<HistoryTurn>()
        val toolIndex = mutableMapOf<String, Pair<Int, Int>>() // toolCallId -> (turnIdx, toolIdx)

        for (elem in events) {
            val ev = runCatching { elem.jsonObject }.getOrNull() ?: continue
            val seq = ev["seq"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
            val type = ev["type"]?.jsonPrimitive?.contentOrNull ?: continue
            val data = runCatching { ev["data"]?.jsonObject }.getOrNull() ?: continue

            when (type) {
                "user/message" -> {
                    val sourceKind = runCatching { data["source"]?.jsonObject }
                        .getOrNull()?.get("kind")?.jsonPrimitive?.contentOrNull
                    // DSH injects synthetic runtime-context snapshots under user/message with source.kind != "user".
                    if (sourceKind != null && sourceKind != "user") {
                        continue
                    }
                    val contentText = extractContentText(data["content"])
                    if (contentText.isNotEmpty()) {
                        turns += HistoryTurn(
                            id = "hist-user-$seq",
                            role = "user",
                            seq = seq,
                            text = contentText
                        )
                    }
                }

                "assistant/message" -> {
                    val msgObj = runCatching { data["message"]?.jsonObject }.getOrNull() ?: data
                    val content = runCatching { msgObj["content"]?.jsonArray }.getOrNull()
                    if (content != null) {
                        val textBuf = StringBuilder()
                        val thinkBuf = StringBuilder()
                        val tools = mutableListOf<HistoryBlock.ToolUse>()
                        for (blockElem in content) {
                            val block = runCatching { blockElem.jsonObject }.getOrNull() ?: continue
                            when (block["type"]?.jsonPrimitive?.contentOrNull) {
                                "reasoning", "thinking" -> {
                                    val t = block["text"]?.jsonPrimitive?.contentOrNull
                                        ?: block["thinking"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                    if (t.isNotEmpty()) thinkBuf.append(t)
                                }
                                "text" -> {
                                    val t = block["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                    if (t.isNotEmpty()) textBuf.append(t)
                                }
                                "tool-call", "tool_call" -> {
                                    val callId = block["id"]?.jsonPrimitive?.contentOrNull
                                        ?: block["toolCallId"]?.jsonPrimitive?.contentOrNull
                                        ?: "call-$seq-${tools.size}"
                                    val name = block["name"]?.jsonPrimitive?.contentOrNull ?: "tool"
                                    val rawArgs = block["arguments"] ?: block["input"]
                                    val argsStr = when (rawArgs) {
                                        is JsonPrimitive -> rawArgs.content
                                        null -> ""
                                        else -> rawArgs.toString()
                                    }
                                    tools += HistoryBlock.ToolUse(
                                        id = callId,
                                        name = name,
                                        arguments = argsStr
                                    )
                                }
                            }
                        }
                        val turnIdx = turns.size
                        turns += HistoryTurn(
                            id = "hist-assistant-$seq",
                            role = "assistant",
                            seq = seq,
                            text = textBuf.toString(),
                            thinking = thinkBuf.toString(),
                            tools = tools
                        )
                        tools.forEachIndexed { idx, tool ->
                            toolIndex[tool.id] = turnIdx to idx
                        }
                    } else {
                        val fallbackText = extractContentText(msgObj["content"])
                        if (fallbackText.isNotEmpty()) {
                            turns += HistoryTurn(
                                id = "hist-assistant-$seq",
                                role = "assistant",
                                seq = seq,
                                text = fallbackText
                            )
                        }
                    }
                }

                "tool/result" -> {
                    val msgObj = runCatching { data["message"]?.jsonObject }.getOrNull() ?: data
                    val callId = msgObj["toolCallId"]?.jsonPrimitive?.contentOrNull
                        ?: data["toolCallId"]?.jsonPrimitive?.contentOrNull
                        ?: continue
                    val isError = msgObj["isError"]?.jsonPrimitive?.booleanOrNull
                        ?: (data["error"] != null)
                    val resultText = extractContentText(msgObj["content"]).ifEmpty {
                        data["error"]?.toString().orEmpty()
                    }
                    val outcomeUnknown = resultText.contains("TOOL_OUTCOME_UNKNOWN") ||
                        data["outcome"]?.jsonPrimitive?.contentOrNull == "unknown"

                    val loc = toolIndex[callId]
                    if (loc != null) {
                        val (tIdx, kIdx) = loc
                        val turn = turns[tIdx]
                        val updatedTools = turn.tools.toMutableList()
                        updatedTools[kIdx] = updatedTools[kIdx].copy(
                            result = resultText,
                            isError = isError,
                            outcomeUnknown = outcomeUnknown
                        )
                        turns[tIdx] = turn.copy(
                            tools = updatedTools,
                            interruptedUnknown = turn.interruptedUnknown || outcomeUnknown
                        )
                    } else if (outcomeUnknown) {
                        turns += HistoryTurn(
                            id = "hist-system-$seq",
                            role = "system",
                            seq = seq,
                            text = "Tool execution outcome unknown after interruption ($callId).",
                            interruptedUnknown = true
                        )
                    }
                }

                "command/done" -> {
                    val text = data["text"]?.jsonPrimitive?.contentOrNull
                    if (!text.isNullOrBlank()) {
                        turns += HistoryTurn(
                            id = "hist-cmd-$seq",
                            role = "system",
                            seq = seq,
                            text = text
                        )
                    }
                }
            }
        }
        return turns
    }

    fun extractContentText(elem: JsonElement?): String {
        if (elem == null) return ""
        return when (elem) {
            is JsonPrimitive -> elem.contentOrNull.orEmpty()
            is JsonArray -> elem.joinToString("\n") { item ->
                when (item) {
                    is JsonPrimitive -> item.contentOrNull.orEmpty()
                    is JsonObject -> item["text"]?.jsonPrimitive?.contentOrNull
                        ?: item["content"]?.let { extractContentText(it) }
                        ?: item.toString()
                    else -> item.toString()
                }
            }
            is JsonObject -> elem["text"]?.jsonPrimitive?.contentOrNull ?: elem.toString()
        }
    }
}
