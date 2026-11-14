package dev.dshn.app.bridge

import dev.dshn.app.runtime.LargePayloadXfer
import dev.dshn.app.security.AuditLogger
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DeviceBridgeAndAuditTest {

    @Rule
    @JvmField
    val tmp = TemporaryFolder()

    @Test
    fun fixedMcpToolCatalogContainsExpectedReadAndControlledTools() {
        val tools = DeviceBridgeMcpServer.toolDefinitionsJson()
        val names = tools.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(
            listOf(
                "device_info",
                "device_sensors",
                "device_screen_tree",
                "device_screenshot",
                "device_tap",
                "device_swipe",
                "device_input",
                "device_global_action",
                "device_adb_exec"
            ),
            names
        )
    }

    @Test
    fun auditLoggerScrubsApiKeysAndRotates() {
        val secDir = tmp.newFolder("security")
        val logger = AuditLogger(secDir)
        logger.log("adb", "device_adb_exec", "policy_blocked", "blocked command with sk-secret1234567890abcdef", "")
        val entries = logger.readRecent(10)
        assertEquals(1, entries.size)
        assertFalse(entries[0].summary.contains("sk-secret1234567890abcdef"))
        assertTrue(entries[0].summary.contains("[REDACTED_KEY]"))
    }

    @Test
    fun largePayloadXferSpillsAndCleansUpAfterRead() {
        val xferDir = tmp.newFolder("ipc-xfer")
        val small = LargePayloadXfer.encode(xferDir, "test", "{\"ok\":true}")
        assertFalse(small.startsWith("@file:"))
        assertEquals("{\"ok\":true}", LargePayloadXfer.decode(small))

        val huge = "x".repeat(48_000)
        val ref = LargePayloadXfer.encode(xferDir, "surface", huge)
        assertTrue(ref.startsWith("@file:"))
        val spilledFile = File(ref.removePrefix("@file:"))
        assertTrue(spilledFile.exists())
        assertEquals(huge, LargePayloadXfer.decode(ref))
        assertFalse(spilledFile.exists())
    }
}
