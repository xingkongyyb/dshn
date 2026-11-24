package dev.dshn.feature.terminal

import dev.dshn.app.bridge.HostRootShellSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnsiTerminalBufferTest {

    @Test
    fun ansiTerminalBufferStripsEscapesAndUpdatesLines() {
        val buf = AnsiTerminalBuffer(cols = 40, rows = 10)
        buf.append("\u001B[32mhello\u001B[0m world\r\nsecond\b\b line")
        val rendered = buf.renderText()
        assertTrue(rendered.contains("hello world"))
        assertTrue(rendered.contains("seco line"))

        buf.append("\u001B[2J\u001B[Hfresh")
        assertEquals("fresh", buf.renderText().trim())
    }

    @Test
    fun hostRootLfNormalizationRendersWithoutAnsiStaircaseDrift() {
        val (part1, endedCr1) = HostRootShellSession.normalizeLfToCrLf("line1\nline2\r", prevEndedWithCr = false)
        val (part2, _) = HostRootShellSession.normalizeLfToCrLf("\nline3\n", prevEndedWithCr = endedCr1)

        val buf = AnsiTerminalBuffer(cols = 60, rows = 10)
        buf.append(HostRootShellSession.formatLocalEcho("date\n"))
        buf.append(part1)
        buf.append(part2)
        val lines = buf.renderText().lines().filter { it.isNotEmpty() }
        assertEquals(listOf("# date", "line1", "line2", "line3"), lines)
    }
}
