package dev.dshn.app.acp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList

class AcpOrderedDeliveryTest {

    @Test
    fun deliversAllTrailingChunksStrictlyBeforePromptCompletes() = runBlocking {
        val clientToServerIn = PipedInputStream(64 * 1024)
        val clientToServerOut = PipedOutputStream(clientToServerIn)
        val serverToClientIn = PipedInputStream(256 * 1024)
        val serverToClientOut = PipedOutputStream(serverToClientIn)

        val chunkCount = 1200
        val received = CopyOnWriteArrayList<String>()

        // Mock ACP server writing 1,200 rapid chunks immediately followed by the prompt RPC response.
        val serverThread = Thread {
            val reader = BufferedReader(InputStreamReader(clientToServerIn, Charsets.UTF_8))
            val line = reader.readLine() ?: return@Thread
            val req = Json.parseToJsonElement(line).jsonObject
            val reqId = req["id"]!!.jsonPrimitive.content
            val sb = StringBuilder()
            for (i in 0 until chunkCount) {
                val payload = if (i % 2 == 0) {
                    """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"s1","update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"[$i]"}}}}"""
                } else {
                    """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"s1","update":{"sessionUpdate":"agent_message_chunk","content":[{"type":"text","text":"[$i]"}]}}}"""
                }
                sb.append(payload).append('\n')
            }
            sb.append("""{"jsonrpc":"2.0","id":$reqId,"result":{"stopReason":"end_turn"}}""").append('\n')
            serverToClientOut.write(sb.toString().toByteArray(Charsets.UTF_8))
            serverToClientOut.flush()
        }.apply { start() }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val client = AcpClient(
            input = serverToClientIn,
            output = clientToServerOut,
            scope = scope,
            onPermissionRequest = { PermissionOutcome("", cancelled = true) },
            onSessionUpdate = { upd ->
                if (upd is SessionUpdate.AgentMessageChunk) {
                    received.add(upd.text)
                }
            }
        )

        try {
            val res = client.prompt("s1", "hi")
            // At the exact instant prompt() returns, every single chunk (including the tail!)
            // MUST already have been delivered in order — zero race, zero truncation.
            assertEquals("end_turn", res.stopReason)
            assertEquals(chunkCount, received.size)
            assertEquals("[0]", received.first())
            assertEquals("[${chunkCount - 1}]", received.last())
        } finally {
            client.close()
            scope.cancel()
            serverThread.join(3000)
        }
    }
}
