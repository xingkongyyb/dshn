package dev.dshn.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LargePayloadXferTest {

    @Rule
    @JvmField
    val tmp = TemporaryFolder()

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
