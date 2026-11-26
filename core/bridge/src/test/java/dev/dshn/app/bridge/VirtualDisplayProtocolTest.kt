package dev.dshn.app.bridge

import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualDisplayProtocolTest {

    @Test
    fun parsesPrefixedWireLinesAndIgnoresShellNoise() {
        assertNull(VirtualDisplayController.parseWireLine("WARNING: linker: something"))
        assertNull(VirtualDisplayController.parseWireLine(""))
        val hello = VirtualDisplayController.parseWireLine(
            """some_banner DSHN_VD:{"event":"hello","ok":true,"protocol":1,"driver":"adb","rust_encoder":true}"""
        )
        assertTrue(hello != null)
        assertEquals("hello", hello!!["event"]?.jsonPrimitive?.content)
        assertEquals(true, hello["rust_encoder"]?.jsonPrimitive?.booleanOrNull)

        val reply = VirtualDisplayController.parseWireLine(
            """DSHN_VD:{"id":7,"result":{"ok":true,"display_id":4,"width":720,"height":1280}}"""
        )
        assertTrue(reply != null)
        assertEquals(7, reply!!["id"]?.jsonPrimitive?.intOrNull)
        assertEquals(4, reply["result"]?.jsonObject?.get("display_id")?.jsonPrimitive?.intOrNull)
    }
}
