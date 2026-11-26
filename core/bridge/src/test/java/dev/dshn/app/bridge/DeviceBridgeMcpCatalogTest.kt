package dev.dshn.app.bridge

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceBridgeMcpCatalogTest {

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
                "device_adb_exec",
                "device_root_status",
                "device_root_exec",
                "device_virtual_display_open",
                "device_virtual_display_action"
            ),
            names
        )
    }
}
