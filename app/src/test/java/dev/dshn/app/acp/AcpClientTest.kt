package dev.dshn.app.acp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PipedInputStream
import java.io.PipedOutputStream

class AcpClientTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun initialize_newSessionWithStdioMcp_and_sessionUpdates() = runBlocking {
        withTimeout(15_000) {
            val clientOutPipe = PipedOutputStream()
            val serverIn = PipedInputStream(clientOutPipe)
            val serverOutPipe = PipedOutputStream()
            val clientIn = PipedInputStream(serverOutPipe)

            val degradedHit = CompletableDeferred<Int>()
            val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val client = AcpClient(
                input = clientIn,
                output = clientOutPipe,
                scope = clientScope,
                onPermissionRequest = { req ->
                    PermissionOutcome(req.options.first().optionId)
                },
                onStreamDegraded = { badCount, _ ->
                    if (!degradedHit.isCompleted) degradedHit.complete(badCount)
                }
            )

            val collected = mutableListOf<SessionUpdate>()
            val collectJob = launch {
                client.updates.take(3).toList(collected)
            }

            val serverJob = launch(Dispatchers.IO) {
                try {
                    val br = BufferedReader(InputStreamReader(serverIn, Charsets.UTF_8))
                    val req1 = json.parseToJsonElement(br.readLine()).jsonObject
                    assertEquals("initialize", req1["method"]?.jsonPrimitive?.content)
                    serverOutPipe.write(
                        """{"jsonrpc":"2.0","id":${req1["id"]},"result":{"protocolVersion":1}}"""
                            .plus("\n").toByteArray()
                    )
                    serverOutPipe.flush()

                    val req2 = json.parseToJsonElement(br.readLine()).jsonObject
                    assertEquals("session/new", req2["method"]?.jsonPrimitive?.content)
                    val mcp0 = req2["params"]!!.jsonObject["mcpServers"]!!.jsonArray[0].jsonObject
                    assertFalse("Stdio MCP must omit 'type' key for DSH", mcp0.containsKey("type"))
                    assertEquals("/opt/node24/bin/node", mcp0["command"]?.jsonPrimitive?.content)
                    serverOutPipe.write(
                        """{"jsonrpc":"2.0","id":${req2["id"]},"result":{"sessionId":"sess-1","configOptions":[{"id":"model","name":"Model","category":"model","type":"select","currentValue":"deepseek-v4-flash","options":[{"group":"Official","name":"Official","options":[{"value":"deepseek-v4-flash","name":"DeepSeek V4 Flash"}]}]}]}}"""
                            .plus("\n").toByteArray()
                    )
                    serverOutPipe.flush()

                    serverOutPipe.write(
                        """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"sess-1","update":{"sessionUpdate":"agent_thought_chunk","content":{"type":"text","text":"think1"}}}}"""
                            .plus("\n").toByteArray()
                    )
                    serverOutPipe.write(
                        """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"sess-1","update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"hello"}}}}"""
                            .plus("\n").toByteArray()
                    )
                    serverOutPipe.write(
                        """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"sess-1","update":{"sessionUpdate":"usage_update","used":1200,"size":128000}}}"""
                            .plus("\n").toByteArray()
                    )
                    serverOutPipe.write("not json 1\nnot json 2\nnot json 3\n".toByteArray())
                    serverOutPipe.flush()
                } finally {
                    serverOutPipe.close()
                }
            }

            val initRes = client.initialize()
            assertEquals("1", initRes["protocolVersion"]?.jsonPrimitive?.content)

            val sessRes = client.newSession(
                cwd = "/root",
                mcpServers = listOf(
                    McpServerConfig.Stdio(
                        name = "dshn-device-bridge",
                        command = "/opt/node24/bin/node",
                        args = listOf("/opt/dshn/dshn-mcp-relay.mjs"),
                        env = mapOf("DSHN_DEVICE_MCP_SOCK" to "/run/dshn-bridge/device-mcp.sock")
                    )
                )
            )
            assertEquals("sess-1", sessRes.sessionId)
            assertEquals(1, sessRes.configOptions.size)
            assertEquals("Official", sessRes.configOptions[0].choices[0].group)

            collectJob.join()
            serverJob.join()
            assertEquals(3, collected.size)
            assertEquals("sess-1", collected[0].sessionId)
            assertTrue(collected[0] is SessionUpdate.AgentThoughtChunk)
            assertTrue(collected[1] is SessionUpdate.AgentMessageChunk)
            assertTrue(collected[2] is SessionUpdate.UsageUpdate)

            val badCount = degradedHit.await()
            assertEquals(AcpClient.BAD_LINE_DEGRADED_THRESHOLD, badCount)
            client.close()
            clientScope.cancel()
        }
    }
}
