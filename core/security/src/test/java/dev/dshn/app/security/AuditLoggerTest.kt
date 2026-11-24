package dev.dshn.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AuditLoggerTest {

    @Rule
    @JvmField
    val tmp = TemporaryFolder()

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
}
