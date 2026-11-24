package dev.dshn.feature.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

class HostPortParserTest {

    @Test
    fun parseHostPortHandlesValidAndMalformedEndpoints() {
        assertEquals("127.0.0.1" to 5555, parseHostPort("127.0.0.1:5555"))
        assertEquals("192.168.1.42" to 41234, parseHostPort(" 192.168.1.42:41234 "))
        assertEquals("::1" to 5555, parseHostPort("[::1]:5555"))

        assertEquals(-1, parseHostPort("127.0.0.1: 5555").second)
        assertEquals(-1, parseHostPort("fe80::1:5555").second)
        assertEquals(-1, parseHostPort("127.0.0.1:abc").second)
        assertEquals(-1, parseHostPort("127.0.0.1:").second)
        assertEquals(-1, parseHostPort(":5555").second)
        assertEquals(-1, parseHostPort("localhost").second)
    }
}
