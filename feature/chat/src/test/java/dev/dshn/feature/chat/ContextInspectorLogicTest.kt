package dev.dshn.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ContextInspectorLogicTest {

    @Test
    fun formatCompactTokensFormatsRangesCleanly() {
        assertEquals("0", formatCompactTokens(0L))
        assertEquals("842", formatCompactTokens(842L))
        assertEquals("1.50k", formatCompactTokens(1500L))
        assertEquals("32.5k", formatCompactTokens(32500L))
        assertEquals("1.25M", formatCompactTokens(1_250_000L))
    }

    @Test
    fun formatCacheHitPercentHandlesNullBoundsAndValues() {
        assertEquals("--", formatCacheHitPercent(null))
        assertEquals("--", formatCacheHitPercent(Double.NaN))
        assertEquals("0.0%", formatCacheHitPercent(0.0))
        assertEquals("87.5%", formatCacheHitPercent(0.875))
        assertEquals("100.0%", formatCacheHitPercent(1.2))
    }
}
