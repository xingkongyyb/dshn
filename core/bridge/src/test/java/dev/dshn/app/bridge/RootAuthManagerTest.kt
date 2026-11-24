package dev.dshn.app.bridge

import dev.dshn.app.security.AuditLogger
import dev.dshn.app.security.RootCommandPolicy
import dev.dshn.app.security.RootPolicyMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RootAuthManagerTest {

    @Rule
    @JvmField
    val tmp = TemporaryFolder()

    @Test
    fun detectsSu_authorizes_executes_expiresTtl_andRevokes() = runBlocking {
        val audit = AuditLogger(tmp.newFolder("sec"))
        var nowMs = 1_000_000L

        val manager = RootAuthManager(
            auditLogger = audit,
            fileExistsCheck = { path -> path == "/data/adb/ksu/bin/su" },
            processRunner = { cmd, _ ->
                when {
                    cmd == listOf("/data/adb/ksu/bin/su", "-v") ->
                        RootExecResult(0, "1.2.0:KernelSU", "", 10L)
                    cmd.size == 3 && cmd[1] == "-c" && cmd[2].startsWith("id &&") ->
                        RootExecResult(0, "uid=0(root) gid=0(root) groups=0(root)\nPermissive\n", "", 25L)
                    cmd.size == 3 && cmd[1] == "-c" && cmd[2] == "'getenforce'" ->
                        RootExecResult(0, "Permissive\n", "", 15L)
                    else ->
                        RootExecResult(1, "", "unexpected command: $cmd", 5L)
                }
            },
            clockMs = { nowMs }
        )

        val probed = manager.probeEnvironment()
        assertEquals(RootAuthStatus.NOT_AUTHORIZED, probed.status)
        assertEquals("/data/adb/ksu/bin/su", probed.suBinaryPath)
        assertEquals("KernelSU", probed.suManagerHint)
        assertFalse(manager.isAuthorized(ttlMinutes = 30))

        val authRes = manager.requestAuthorization()
        assertTrue(authRes.isSuccess)
        val authed = authRes.getOrThrow()
        assertEquals(RootAuthStatus.AUTHORIZED, authed.status)
        assertEquals("Permissive", authed.selinuxMode)
        assertTrue(manager.isAuthorized(ttlMinutes = 30))

        val allowed = RootCommandPolicy.evaluate(listOf("getenforce"), RootPolicyMode.ALLOWLIST_ONLY)
            as RootCommandPolicy.PolicyDecision.Allowed
        val exec = manager.executeAllowed(allowed, timeoutSec = 10L, ttlMinutes = 30)
        assertEquals(0, exec.exitCode)
        assertEquals("Permissive", exec.stdout.trim())

        nowMs += 31 * 60_000L
        assertFalse(manager.isAuthorized(ttlMinutes = 30))
        assertEquals(RootAuthStatus.NOT_AUTHORIZED, manager.state.value.status)

        assertTrue(manager.requestAuthorization().isSuccess)
        assertTrue(manager.isAuthorized(ttlMinutes = 30))
        manager.revokeAuthorization("User locked root")
        assertFalse(manager.isAuthorized(ttlMinutes = 0))
        assertEquals(RootAuthStatus.REVOKED, manager.state.value.status)

        val logs = audit.readRecent(20)
        assertTrue(logs.any { it.decision == "authorized" })
        assertTrue(logs.any { it.decision == "expired" })
        assertTrue(logs.any { it.decision == "revoked" })
    }

    @Test
    fun deniedRootRequestTransitionsToDeniedAndRejectsExec() = runBlocking {
        val audit = AuditLogger(tmp.newFolder("sec-deny"))
        val manager = RootAuthManager(
            auditLogger = audit,
            fileExistsCheck = { path -> path == "/system/bin/su" },
            processRunner = { cmd, _ ->
                if (cmd.contains("-v")) RootExecResult(0, "27.0:MAGISKSU", "", 5L)
                else RootExecResult(13, "", "Permission denied", 20L)
            }
        )

        val res = manager.requestAuthorization()
        assertTrue(res.isFailure)
        assertEquals(RootAuthStatus.DENIED, manager.state.value.status)
        assertEquals("Magisk", manager.state.value.suManagerHint)
        assertFalse(manager.isAuthorized())
    }

    @Test
    fun missingSuBinaryReportsNoSuBinary() = runBlocking {
        val audit = AuditLogger(tmp.newFolder("sec-nosu"))
        val manager = RootAuthManager(
            auditLogger = audit,
            fileExistsCheck = { false }
        )

        val probed = manager.probeEnvironment()
        assertEquals(RootAuthStatus.NO_SU_BINARY, probed.status)
        assertTrue(manager.requestAuthorization().isFailure)
        assertEquals(RootAuthStatus.NO_SU_BINARY, manager.state.value.status)
    }

    @Test
    fun concurrentRevokeDuringAuthorizationDiscardsGrant() = runBlocking {
        val audit = AuditLogger(tmp.newFolder("sec-race"))
        lateinit var manager: RootAuthManager
        manager = RootAuthManager(
            auditLogger = audit,
            fileExistsCheck = { path -> path == "/system/bin/su" },
            processRunner = { cmd, _ ->
                if (cmd.contains("-v")) {
                    RootExecResult(0, "27.0:MAGISKSU", "", 5L)
                } else {
                    manager.revokeAuthorization("Locked while prompt pending")
                    RootExecResult(0, "uid=0(root)\nEnforcing\n", "", 30L)
                }
            }
        )

        val res = manager.requestAuthorization()
        assertTrue(res.isFailure)
        assertFalse(manager.isAuthorized())
        assertEquals(RootAuthStatus.REVOKED, manager.state.value.status)
        val logs = audit.readRecent(10)
        assertTrue(logs.any { it.decision == "revoked_during_request" })
    }
}
