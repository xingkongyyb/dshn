package dev.dshn.app.security

import org.junit.Assert.assertTrue
import org.junit.Test

class AdbArgvPolicyTest {

    @Test
    fun allowsSafeAllowlistedCommands() {
        val allowedCases = listOf(
            listOf("pm", "list", "packages", "-3"),
            listOf("pm", "path", "dev.dshn.app"),
            listOf("dumpsys", "display"),
            listOf("cmd", "display", "get-displays"),
            listOf("wm", "size"),
            listOf("settings", "get", "global", "overlay_display_devices"),
            listOf("settings", "put", "global", "overlay_display_devices", "720x1280/240"),
            listOf("am", "start", "-n", "dev.dshn.app/.MainActivity"),
            listOf("input", "tap", "540", "1200"),
            listOf("input", "text", "hello_world"),
            listOf("screencap", "-p"),
            listOf("getprop", "ro.product.model")
        )
        for (argv in allowedCases) {
            val res = AdbArgvPolicy.evaluate(argv)
            assertTrue("Expected Allowed for $argv but got $res", res is AdbArgvPolicy.PolicyDecision.Allowed)
        }
    }

    @Test
    fun blocksDangerousBinariesAndInjection() {
        val blockedCases = listOf(
            emptyList(),
            listOf("sh", "-c", "id"),
            listOf("/system/bin/sh", "-c", "ls"),
            listOf("bash", "-c", "whoami"),
            listOf("toybox", "id"),
            listOf("su", "-c", "id"),
            listOf("pm", "clear", "dev.dshn.app"),
            listOf("dumpsys", "wifi"),
            listOf("settings", "put", "global", "adb_enabled", "1"),
            listOf("settings", "put", "secure", "enabled_accessibility_services", "x"),
            listOf("am", "start", "-n", "dev.dshn.app/.MainActivity;rm -rf /"),
            listOf("input", "text", "foo && rm -rf /"),
            listOf("input", "text", "$(whoami)"),
            listOf("getprop", "ro.build.id\nid")
        )
        for (argv in blockedCases) {
            val res = AdbArgvPolicy.evaluate(argv)
            assertTrue("Expected Blocked for $argv but got $res", res is AdbArgvPolicy.PolicyDecision.Blocked)
            val msg = (res as AdbArgvPolicy.PolicyDecision.Blocked).reason
            assertTrue("Missing [POLICY_BLOCKED] prefix in $msg", msg.startsWith("[POLICY_BLOCKED]"))
        }
    }
}
