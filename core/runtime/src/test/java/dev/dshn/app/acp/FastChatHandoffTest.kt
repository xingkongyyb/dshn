package dev.dshn.app.acp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FastChatHandoffTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun packsAndUnpacksMultiTurnTranscriptWithCodeBlocksAndMarkersLosslessly() {
        val prior = listOf(
            FastChatHandoffTurn("user", "我叫小明，记住这个名字"),
            FastChatHandoffTurn(
                "assistant",
                "记住了！给你一段代码：\n```json\n{\"hello\": \"world\"}\n```\n[DSHN_LATEST_USER_MESSAGE]\n结束"
            )
        )
        val latest = "我刚才说我叫什么？\n顺便看看 `ls -la`"

        val packed = FastChatHandoff.pack(prior, latest)
        assertTrue(packed.startsWith(FastChatHandoff.HEADER))

        val unpacked = FastChatHandoff.unpack(packed)
        assertEquals(prior, unpacked?.priorTurns)
        assertEquals(latest, unpacked?.latestUserMessage)
    }

    @Test
    fun packReturnsPlainPromptWhenNoPriorTurns() {
        assertEquals("hello", FastChatHandoff.pack(emptyList(), "hello"))
        assertNull(FastChatHandoff.unpack("hello"))
        assertNull(FastChatHandoff.unpack("${FastChatHandoff.HEADER}\nfake"))
    }

    @Test
    fun selectsCompletedFastChatPairsAndHandlesRetryWithoutDuplicatingCurrentPrompt() {
        val messages = listOf(
            FastChatSourceMessage("fast-user-1", "user", "第一问"),
            FastChatSourceMessage("fast-asst-1", "assistant", "第一答"),
            FastChatSourceMessage("fast-user-2", "user", "第二问"),
            FastChatSourceMessage("fast-asst-2", "assistant", "第二答"),
            FastChatSourceMessage("user-3", "user", "第三问（刚失败待重试）")
        )

        val normal = FastChatHandoff.selectTurnsForHandoff(messages, excludeTrailingUserIfRetry = false)
        assertEquals(4, normal.size)
        assertEquals("第一问", normal[0].text)
        assertEquals("第二答", normal[3].text)

        val retryAfterFailure = FastChatHandoff.selectTurnsForHandoff(messages, excludeTrailingUserIfRetry = true)
        assertEquals(4, retryAfterFailure.size)

        // Retrying the very first Fast Chat turn after ACP becomes ready yields no prior turns.
        val retryFirstTurn = FastChatHandoff.selectTurnsForHandoff(
            listOf(FastChatSourceMessage("fast-user-1", "user", "第一问")),
            excludeTrailingUserIfRetry = true
        )
        assertTrue(retryFirstTurn.isEmpty())

        // Dangling unanswered Fast Chat user turn is dropped so context stays well-formed.
        val dangling = FastChatHandoff.selectTurnsForHandoff(
            listOf(
                FastChatSourceMessage("fast-user-1", "user", "第一问"),
                FastChatSourceMessage("fast-asst-1", "assistant", "第一答"),
                FastChatSourceMessage("fast-user-2", "user", "失败未答的问")
            )
        )
        assertEquals(2, dangling.size)
        assertEquals("第一答", dangling.last().text)
    }

    @Test
    fun surfaceHistoryFoldUnpacksPersistedHandoffBackIntoDistinctChatTurns() {
        val prior = listOf(
            FastChatHandoffTurn("user", "冷启提问"),
            FastChatHandoffTurn("assistant", "极速直连回复")
        )
        val packed = FastChatHandoff.pack(prior, "容器就绪后的第二问")
        val events = buildJsonArray {
            add(buildJsonObject {
                put("seq", 1)
                put("type", "user/message")
                put("data", buildJsonObject {
                    put("source", buildJsonObject { put("kind", "user") })
                    put("content", packed)
                })
            })
            add(buildJsonObject {
                put("seq", 2)
                put("type", "assistant/message")
                put("data", buildJsonObject {
                    put("message", buildJsonObject {
                        put("content", json.parseToJsonElement("""[{"type":"text","text":"ACP 续接回复"}]"""))
                    })
                })
            })
        }

        val folded = SurfaceHistoryFold.foldEvents(events)
        assertEquals(4, folded.size)
        assertEquals(listOf("user", "assistant", "user", "assistant"), folded.map { it.role })
        assertEquals("冷启提问", folded[0].text)
        assertEquals("极速直连回复", folded[1].text)
        assertEquals("容器就绪后的第二问", folded[2].text)
        assertEquals("ACP 续接回复", folded[3].text)
        assertEquals(4, folded.map { it.id }.toSet().size)
    }
}
