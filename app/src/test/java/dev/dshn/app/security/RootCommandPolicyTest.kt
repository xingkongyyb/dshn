package dev.dshn.app.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootCommandPolicyTest {

    @Test
    fun allowlistPermitsSafeInspectionAndFrameworkCommands() {
        val cases = listOf(
            listOf("id"),
            listOf("whoami"),
            listOf("getenforce"),
            listOf("uname", "-a"),
            listOf("getprop", "ro.build.version.release"),
            listOf("dumpsys", "thermalservice"),
            listOf("cat", "/proc/loadavg"),
            listOf("cat", "/sys/devices/system/cpu/cpufreq/policy0/scaling_governor"),
            listOf("/system/bin/device_config", "put", "activity_manager", "max_phantom_processes", "2147483647"),
            listOf("pm", "disable-user", "--user", "0", "com.example.pkg"),
            listOf("setenforce", "0"),
            listOf("sysctl", "-w", "kernel.sched_util_clamp_min=512"),
            listOf("ip", "addr", "show")
        )
        for (argv in cases) {
            val res = RootCommandPolicy.evaluate(argv, RootPolicyMode.ALLOWLIST_ONLY)
            assertTrue("Expected Allowed for $argv but got $res", res is RootCommandPolicy.PolicyDecision.Allowed)
        }

        // Mutating commands must require mandatory confirmation even in allowlist mode
        val setenforce = RootCommandPolicy.evaluate(listOf("setenforce", "0"), RootPolicyMode.ALLOWLIST_ONLY)
            as RootCommandPolicy.PolicyDecision.Allowed
        assertTrue(setenforce.requiresMandatoryConfirmation)

        val readOnlyId = RootCommandPolicy.evaluate(listOf("id"), RootPolicyMode.ALLOWLIST_ONLY)
            as RootCommandPolicy.PolicyDecision.Allowed
        assertFalse(readOnlyId.requiresMandatoryConfirmation)
    }

    @Test
    fun allowlistBlocksShellWrappersInjectionAndRestrictedPaths() {
        val blockedCases = listOf(
            emptyList(),
            listOf("sh", "-c", "id"),
            listOf("bash", "-c", "whoami"),
            listOf("su", "-c", "id"),
            listOf("rm", "-rf", "/"),
            listOf("rm", "-rf", "/data"),
            listOf("dd", "if=/dev/zero", "of=/dev/block/sda"),
            listOf("reboot"),
            listOf("cat", "/data/data/dev.dshn.app/files/security/secrets.enc"),
            listOf("cat", "/data/data/dev.dshn.app/files/security/audit.jsonl"),
            listOf("cat", "/proc/../etc/passwd"),
            listOf("ls", "/data/adb"),
            listOf("getprop", "ro.build.id;id"),
            listOf("getprop", "$(id)"),
            listOf("getprop", "a\nb")
        )
        for (argv in blockedCases) {
            val res = RootCommandPolicy.evaluate(argv, RootPolicyMode.ALLOWLIST_ONLY)
            assertTrue("Expected Blocked for $argv but got $res", res is RootCommandPolicy.PolicyDecision.Blocked)
            val reason = (res as RootCommandPolicy.PolicyDecision.Blocked).reason
            assertTrue("Missing [POLICY_BLOCKED] prefix: $reason", reason.startsWith("[POLICY_BLOCKED]"))
        }
    }

    @Test
    fun customModeRequiresMandatoryConfirmationAndKeepsHardSafetyBlocks() {
        val customAllowed = RootCommandPolicy.evaluate(
            listOf("mount", "-o", "remount,ro", "/vendor"),
            RootPolicyMode.CUSTOM_WITH_CONFIRM
        )
        assertTrue(customAllowed is RootCommandPolicy.PolicyDecision.Allowed)
        assertTrue((customAllowed as RootCommandPolicy.PolicyDecision.Allowed).requiresMandatoryConfirmation)

        // Hard safety blocks MUST still apply in CUSTOM_WITH_CONFIRM
        val hardBlocked = listOf(
            listOf("cat", "/data/data/dev.dshn.app/files/security/secrets.enc"),
            listOf("cat", "/data/data/dev.dshn.app/files/security/adb_identity.enc"),
            listOf("rm", "/data/data/dev.dshn.app/files/security/audit.jsonl"),
            listOf("rm", "-rf", "/"),
            listOf("rm", "-rf", "/system"),
            listOf("dd", "if=/dev/zero", "of=/dev/block/by-name/boot"),
            listOf("mkfs.ext4", "/dev/block/sda1"),
            listOf("reboot"),
            listOf("sh", "-c", "id"),
            listOf("id", ";", "whoami")
        )
        for (argv in hardBlocked) {
            val res = RootCommandPolicy.evaluate(argv, RootPolicyMode.CUSTOM_WITH_CONFIRM)
            assertTrue("Expected Hard Block in custom mode for $argv but got $res", res is RootCommandPolicy.PolicyDecision.Blocked)
        }
    }
}
