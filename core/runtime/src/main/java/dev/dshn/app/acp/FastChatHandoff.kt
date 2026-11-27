package dev.dshn.app.acp

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class FastChatHandoffTurn(
    val role: String,
    val text: String
)

data class FastChatSourceMessage(
    val id: String,
    val role: String,
    val text: String
)

data class UnpackedFastChatHandoff(
    val priorTurns: List<FastChatHandoffTurn>,
    val latestUserMessage: String
)

/**
 * Packs cold-start Rust Fast Chat turns into a lossless, LLM-readable handoff envelope for the
 * first DSH ACP prompt and unpacks them back into separate bubbles when reloading history via
 * [SurfaceHistoryFold], preserving DSH's session log as the single source of truth.
 */
object FastChatHandoff {
    const val USER_ID_PREFIX = "fast-user-"
    const val ASSISTANT_ID_PREFIX = "fast-asst-"
    const val HEADER = "[DSHN_FAST_CHAT_HANDOFF_V1]"
    const val LATEST_MARKER = "[DSHN_LATEST_USER_MESSAGE]"
    const val MAX_TURNS = 16
    const val MAX_TURN_CHARS = 6_000
    const val MAX_TOTAL_CHARS = 24_000

    private const val INSTRUCTION =
        "The turns below occurred via direct API while the DSH container was starting. Continue seamlessly from this context and respond only to the latest user message."

    private val compactJson = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        encodeDefaults = true
    }

    fun isFastChatMessageId(id: String): Boolean =
        id.startsWith(USER_ID_PREFIX) || id.startsWith(ASSISTANT_ID_PREFIX)

    fun selectTurnsForHandoff(
        messages: List<FastChatSourceMessage>,
        excludeTrailingUserIfRetry: Boolean = false
    ): List<FastChatHandoffTurn> {
        val sliceEnd = if (excludeTrailingUserIfRetry) {
            val lastUser = messages.indexOfLast { it.role == "user" }
            if (lastUser >= 0) lastUser else messages.size
        } else {
            messages.size
        }
        if (sliceEnd <= 0) return emptyList()

        val filtered = messages.subList(0, sliceEnd)
            .asSequence()
            .filter { isFastChatMessageId(it.id) }
            .filter { it.role == "user" || it.role == "assistant" }
            .filter { it.text.isNotBlank() }
            .map { FastChatHandoffTurn(role = it.role, text = clipTurnText(it.text.trim())) }
            .toMutableList()

        while (filtered.isNotEmpty() && filtered.first().role != "user") {
            filtered.removeAt(0)
        }
        while (filtered.isNotEmpty() && filtered.last().role == "user") {
            filtered.removeAt(filtered.lastIndex)
        }
        if (filtered.isEmpty()) return emptyList()

        val bounded = if (filtered.size > MAX_TURNS) {
            val trimmed = filtered.takeLast(MAX_TURNS).toMutableList()
            while (trimmed.isNotEmpty() && trimmed.first().role != "user") {
                trimmed.removeAt(0)
            }
            trimmed
        } else {
            filtered
        }

        var totalChars = bounded.sumOf { it.text.length }
        while (totalChars > MAX_TOTAL_CHARS && bounded.size > 2) {
            val removed = bounded.removeAt(0)
            totalChars -= removed.text.length
            while (bounded.size > 2 && bounded.first().role != "user") {
                totalChars -= bounded.removeAt(0).text.length
            }
        }
        return bounded
    }

    fun pack(priorTurns: List<FastChatHandoffTurn>, latestUserMessage: String): String {
        if (priorTurns.isEmpty()) return latestUserMessage
        val encoded = compactJson.encodeToString(
            ListSerializer(FastChatHandoffTurn.serializer()),
            priorTurns
        )
        return buildString(encoded.length + latestUserMessage.length + 256) {
            append(HEADER).append('\n')
            append(INSTRUCTION).append('\n')
            append("```json\n")
            append(encoded).append('\n')
            append("```\n")
            append(LATEST_MARKER).append('\n')
            append(latestUserMessage)
        }
    }

    fun unpack(rawUserMessage: String): UnpackedFastChatHandoff? {
        if (!rawUserMessage.startsWith(HEADER)) return null
        val lines = ArrayList<String>(6)
        var pos = 0
        val len = rawUserMessage.length
        while (lines.size < 6) {
            if (pos >= len) return null
            val nl = rawUserMessage.indexOf('\n', pos)
            if (nl < 0) return null
            val lineEnd = if (nl > pos && rawUserMessage[nl - 1] == '\r') nl - 1 else nl
            lines.add(rawUserMessage.substring(pos, lineEnd))
            pos = nl + 1
        }
        if (lines[0] != HEADER) return null
        if (lines[1].isBlank()) return null
        if (lines[2] != "```json") return null
        if (lines[4] != "```") return null
        if (lines[5] != LATEST_MARKER) return null

        val turns = runCatching {
            compactJson.decodeFromString(
                ListSerializer(FastChatHandoffTurn.serializer()),
                lines[3]
            )
        }.getOrNull() ?: return null
        if (turns.isEmpty()) return null
        if (turns.any { (it.role != "user" && it.role != "assistant") || it.text.isBlank() }) {
            return null
        }
        val latest = rawUserMessage.substring(pos)
        return UnpackedFastChatHandoff(priorTurns = turns, latestUserMessage = latest)
    }

    private fun clipTurnText(raw: String): String =
        if (raw.length <= MAX_TURN_CHARS) raw else raw.take(MAX_TURN_CHARS) + "…"
}
