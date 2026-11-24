package dev.dshn.app.navigation

import dev.dshn.feature.bridge.DeviceBridgeRoute
import dev.dshn.feature.bridge.DeviceLabRoute
import dev.dshn.feature.chat.ChatRoute
import dev.dshn.feature.settings.AboutRoute
import dev.dshn.feature.settings.SettingsBackgroundRoute
import dev.dshn.feature.settings.SettingsDiagnosticsRoute
import dev.dshn.feature.settings.SettingsEnvironmentRoute
import dev.dshn.feature.settings.SettingsGraph
import dev.dshn.feature.settings.SettingsHomeRoute
import dev.dshn.feature.settings.SettingsModelRoute
import dev.dshn.feature.settings.SettingsPermissionRoute
import dev.dshn.feature.setup.SetupRoute
import dev.dshn.feature.terminal.TerminalRoute
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationRoutesTest {

    private val json = Json

    @Test
    fun setupRouteSerializesReopenedFlagCorrectly() {
        val initial = SetupRoute()
        assertFalse(initial.reopened)
        val reopened = SetupRoute(reopened = true)
        assertTrue(reopened.reopened)

        val encodedInitial = json.encodeToString(initial)
        val encodedReopened = json.encodeToString(reopened)
        assertEquals(initial, json.decodeFromString<SetupRoute>(encodedInitial))
        assertEquals(reopened, json.decodeFromString<SetupRoute>(encodedReopened))
    }

    @Test
    fun topLevelAndNestedSettingsRoutesRoundTripSerialization() {
        assertEquals(ChatRoute, json.decodeFromString<ChatRoute>(json.encodeToString(ChatRoute)))
        assertEquals(TerminalRoute, json.decodeFromString<TerminalRoute>(json.encodeToString(TerminalRoute)))
        assertEquals(DeviceBridgeRoute, json.decodeFromString<DeviceBridgeRoute>(json.encodeToString(DeviceBridgeRoute)))
        assertEquals(DeviceLabRoute, json.decodeFromString<DeviceLabRoute>(json.encodeToString(DeviceLabRoute)))
        assertEquals(SettingsGraph, json.decodeFromString<SettingsGraph>(json.encodeToString(SettingsGraph)))
        assertEquals(SettingsHomeRoute, json.decodeFromString<SettingsHomeRoute>(json.encodeToString(SettingsHomeRoute)))
        assertEquals(SettingsModelRoute, json.decodeFromString<SettingsModelRoute>(json.encodeToString(SettingsModelRoute)))
        assertEquals(SettingsPermissionRoute, json.decodeFromString<SettingsPermissionRoute>(json.encodeToString(SettingsPermissionRoute)))
        assertEquals(SettingsBackgroundRoute, json.decodeFromString<SettingsBackgroundRoute>(json.encodeToString(SettingsBackgroundRoute)))
        assertEquals(SettingsEnvironmentRoute, json.decodeFromString<SettingsEnvironmentRoute>(json.encodeToString(SettingsEnvironmentRoute)))
        assertEquals(SettingsDiagnosticsRoute, json.decodeFromString<SettingsDiagnosticsRoute>(json.encodeToString(SettingsDiagnosticsRoute)))
        assertEquals(AboutRoute, json.decodeFromString<AboutRoute>(json.encodeToString(AboutRoute)))
    }
}
