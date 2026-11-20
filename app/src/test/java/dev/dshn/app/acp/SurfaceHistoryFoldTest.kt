package dev.dshn.app.acp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfaceHistoryFoldTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun foldsUserAssistantAndPairsToolOutcomeUnknown() {
        val eventsJson = """
        [
          {"seq": 1, "type": "user/message", "data": {"content": "Check uptime"}},
          {"seq": 2, "type": "assistant/message", "data": {"message": {"content": [
            {"type": "reasoning", "text": "Need shell"},
            {"type": "text", "text": "Running check"},
            {"type": "tool-call", "id": "tc-1", "name": "bash", "arguments": {"cmd": "uptime"}}
          ]}}},
          {"seq": 3, "type": "tool/result", "data": {"message": {"toolCallId": "tc-1", "content": "TOOL_OUTCOME_UNKNOWN: runtime died", "isError": true}}}
        ]
        """.trimIndent()

        val turns = SurfaceHistoryFold.foldEvents(json.parseToJsonElement(eventsJson).jsonArray)
        assertEquals(2, turns.size)
        assertEquals("user", turns[0].role)
        assertEquals("Check uptime", turns[0].text)

        val asst = turns[1]
        assertEquals("assistant", asst.role)
        assertEquals("Need shell", asst.thinking)
        assertEquals("Running check", asst.text)
        assertTrue(asst.interruptedUnknown)
        assertEquals(1, asst.tools.size)
        assertEquals("bash", asst.tools[0].name)
        assertTrue(asst.tools[0].isError)
        assertTrue(asst.tools[0].outcomeUnknown)
    }

    @Test
    fun foldsNormalToolCompletion() {
        val eventsJson = """
        [
          {"seq": 1, "type": "assistant/message", "data": {"message": {"content": [
            {"type": "tool-call", "id": "tc-2", "name": "read_file", "arguments": "{\"path\":\"/root/a\"}"}
          ]}}},
          {"seq": 2, "type": "tool/result", "data": {"message": {"toolCallId": "tc-2", "content": [{"type": "text", "text": "ok"}], "isError": false}}}
        ]
        """.trimIndent()

        val turns = SurfaceHistoryFold.foldEvents(json.parseToJsonElement(eventsJson).jsonArray)
        assertEquals(1, turns.size)
        assertFalse(turns[0].interruptedUnknown)
        assertEquals("ok", turns[0].tools[0].result)
    }

    @Test
    fun skipsSyntheticRuntimeContextUserMessages() {
        val eventsJson = """
        [
          {"seq": 8, "type": "user/message", "data": {"source": {"kind": "user"}, "content": [{"type": "text", "text": "Reply with only DSHN_E2E_O"}]}},
          {"seq": 9, "type": "user/message", "data": {"source": {"kind": "runtime-context", "form": "snapshot"}, "content": [{"type": "text", "text": "Current runtime context..."}]}},
          {"seq": 14, "type": "assistant/message", "data": {"message": {"content": [{"type": "text", "text": "DSHN_E2E_O"}]}}}
        ]
        """.trimIndent()

        val turns = SurfaceHistoryFold.foldEvents(json.parseToJsonElement(eventsJson).jsonArray)
        assertEquals(2, turns.size)
        assertEquals("user", turns[0].role)
        assertEquals("Reply with only DSHN_E2E_O", turns[0].text)
        assertEquals("assistant", turns[1].role)
        assertEquals("DSHN_E2E_O", turns[1].text)
    }

    @Test
    fun foldsRetrySourceImageBlocksAndContentRefusedTurnEnd() {
        val eventsJson = """
        [
          {"seq": 1, "type": "user/message", "data": {"source": {"kind": "retry"}, "content": [{"type": "text", "text": "Analyze image"}, {"type": "image", "attachment": {"mediaType": "image/png"}}]}},
          {"seq": 2, "type": "turn/end", "data": {"turn": 1, "reason": {"kind": "error", "error": {"code": "CONTENT_REFUSED", "message": "DeepSeek refused to answer under its content safety review"}}}}
        ]
        """.trimIndent()

        val turns = SurfaceHistoryFold.foldEvents(json.parseToJsonElement(eventsJson).jsonArray)
        assertEquals(2, turns.size)
        assertEquals("user", turns[0].role)
        assertTrue(turns[0].text.contains("Analyze image"))
        assertTrue(turns[0].text.contains("[Image: image/png]"))
        assertEquals("system", turns[1].role)
        assertTrue(turns[1].text.contains("[CONTENT_REFUSED]"))
    }

    @Test
    fun assignsUniqueIdsWhenSeqMissingOrDuplicated() {
        val eventsJson = """
        [
          {"type": "user/message", "data": {"content": "First missing seq"}},
          {"type": "assistant/message", "data": {"message": {"content": [{"type": "text", "text": "Reply 1"}]}}},
          {"seq": "bad", "type": "user/message", "data": {"content": "Second unparseable seq"}},
          {"seq": 0, "type": "assistant/message", "data": {"message": {"content": [{"type": "text", "text": "Reply 2"}]}}},
          {"seq": 5, "type": "command/done", "data": {"text": "cmd 1"}},
          {"seq": 5, "type": "command/done", "data": {"text": "cmd 2"}}
        ]
        """.trimIndent()

        val turns = SurfaceHistoryFold.foldEvents(json.parseToJsonElement(eventsJson).jsonArray)
        assertEquals(6, turns.size)
        val uniqueIds = turns.map { it.id }.toSet()
        assertEquals(turns.size, uniqueIds.size)
    }
}
