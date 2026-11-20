package dev.dshn.app.bridge

import dev.dshn.app.security.AuditLogger
import dev.dshn.app.security.RootCommandPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@Serializable
enum class RootAuthStatus {
    UNKNOWN,
    NO_SU_BINARY,
    NOT_AUTHORIZED,
    AUTHORIZED,
    DENIED,
    REVOKED,
    ERROR
}

@Serializable
data class RootAuthState(
    val status: RootAuthStatus = RootAuthStatus.UNKNOWN,
    val suBinaryPath: String? = null,
    val suVersion: String = "",
    val suManagerHint: String = "",
    val uidOutput: String = "",
    val selinuxMode: String = "",
    val lastVerifiedAtMs: Long = 0L,
    val lastError: String? = null
)

data class RootExecResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val durationMs: Long,
    val timedOut: Boolean = false
)

/**
 * Standard Android `su` root authorization and command execution manager.
 *
 * Works exclusively through user-consented superuser managers (Magisk, KernelSU, SukiSU, APatch,
 * or standard `su` binaries). Never performs unauthorized privilege escalation.
 */
class RootAuthManager(
    private val auditLogger: AuditLogger,
    private val fileExistsCheck: (String) -> Boolean = { path ->
        val f = File(path)
        f.exists() && f.isFile
    },
    private val processRunner: (List<String>, Long) -> RootExecResult = ::defaultProcessRunner,
    private val clockMs: () -> Long = System::currentTimeMillis
) {
    private val _state = MutableStateFlow(RootAuthState())
    val state: StateFlow<RootAuthState> = _state.asStateFlow()
    private val authEpoch = AtomicLong(0L)

    fun detectSuBinary(customPath: String? = null): String? {
        val trimmedCustom = customPath?.trim()
        if (!trimmedCustom.isNullOrEmpty() && fileExistsCheck(trimmedCustom)) {
            return trimmedCustom
        }
        for (candidate in STANDARD_SU_PATHS) {
            if (fileExistsCheck(candidate)) return candidate
        }
        val pathEnv = System.getenv("PATH").orEmpty()
        for (dir in pathEnv.split(':')) {
            if (dir.isBlank()) continue
            val candidate = "${dir.trimEnd('/')}/su"
            if (fileExistsCheck(candidate)) return candidate
        }
        return null
    }

    /**
     * Non-intrusive check for `su` binary presence without triggering a superuser prompt.
     */
    fun probeEnvironment(customPath: String? = null): RootAuthState {
        val su = detectSuBinary(customPath)
        val prev = _state.value
        val next = if (su == null) {
            RootAuthState(
                status = RootAuthStatus.NO_SU_BINARY,
                suBinaryPath = null,
                lastError = "No su binary detected on device"
            )
        } else if (prev.status == RootAuthStatus.AUTHORIZED && prev.suBinaryPath == su) {
            prev
        } else {
            val verRes = runCatching { processRunner(listOf(su, "-v"), 3L) }.getOrNull()
            val verText = listOfNotNull(verRes?.stdout?.trim(), verRes?.stderr?.trim())
                .firstOrNull { it.isNotEmpty() }
                .orEmpty()
                .lineSequence()
                .firstOrNull()
                .orEmpty()
            RootAuthState(
                status = if (prev.status == RootAuthStatus.REVOKED) RootAuthStatus.REVOKED else RootAuthStatus.NOT_AUTHORIZED,
                suBinaryPath = su,
                suVersion = verText,
                suManagerHint = inferManagerHint(su, verText),
                lastError = null
            )
        }
        _state.value = next
        return next
    }

    /**
     * Explicitly invokes `<su> -c "id ..."` so the device's superuser manager prompts the user
     * to grant or deny root authorization to DSHN.
     */
    suspend fun requestAuthorization(
        customPath: String? = null,
        timeoutSec: Long = 15L
    ): Result<RootAuthState> = withContext(Dispatchers.IO) {
        val requestEpoch = authEpoch.get()
        val su = detectSuBinary(customPath)
        if (su == null) {
            val noSu = RootAuthState(
                status = RootAuthStatus.NO_SU_BINARY,
                suBinaryPath = null,
                lastError = "No su binary found. Install Magisk, KernelSU, SukiSU, or APatch."
            )
            if (authEpoch.get() == requestEpoch) {
                _state.value = noSu
            }
            auditLogger.log("root", "root_auth", "unavailable", "Root auth requested but no su binary found")
            return@withContext Result.failure(IllegalStateException(noSu.lastError))
        }

        val verRes = runCatching { processRunner(listOf(su, "-v"), 3L) }.getOrNull()
        val verText = listOfNotNull(verRes?.stdout?.trim(), verRes?.stderr?.trim())
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
            .lineSequence()
            .firstOrNull()
            .orEmpty()
        val managerHint = inferManagerHint(su, verText)
        if (authEpoch.get() == requestEpoch && _state.value.suBinaryPath != su) {
            _state.value = _state.value.copy(
                suBinaryPath = su,
                suVersion = verText,
                suManagerHint = managerHint
            )
        }

        val checkCmd = "id && (getenforce 2>/dev/null || echo Unknown)"
        val res = runCatching { processRunner(listOf(su, "-c", checkCmd), timeoutSec) }.getOrElse { err ->
            if (authEpoch.get() != requestEpoch) {
                auditLogger.log("root", "root_auth", "revoked_during_request", "Root request discarded after concurrent revoke")
                return@withContext Result.failure(SecurityException("Root authorization was revoked while request was pending"))
            }
            val errState = RootAuthState(
                status = RootAuthStatus.ERROR,
                suBinaryPath = su,
                suVersion = verText,
                suManagerHint = managerHint,
                lastError = err.message ?: err.javaClass.simpleName
            )
            _state.value = errState
            auditLogger.log("root", "root_auth", "error", "Failed to invoke su ($su)", errState.lastError.orEmpty())
            return@withContext Result.failure(err)
        }

        if (authEpoch.get() != requestEpoch) {
            auditLogger.log("root", "root_auth", "revoked_during_request", "Root grant discarded because authorization was revoked concurrently")
            return@withContext Result.failure(SecurityException("Root authorization was revoked while request was pending"))
        }

        val lines = res.stdout.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val idLine = lines.firstOrNull { it.contains("uid=") }.orEmpty()
        val isUidZero = Regex("""\buid=0\b""").containsMatchIn(idLine)
        val selinux = lines.lastOrNull { it != idLine }.orEmpty()

        if (!res.timedOut && res.exitCode == 0 && isUidZero) {
            val authorized = RootAuthState(
                status = RootAuthStatus.AUTHORIZED,
                suBinaryPath = su,
                suVersion = verText,
                suManagerHint = managerHint,
                uidOutput = idLine,
                selinuxMode = selinux,
                lastVerifiedAtMs = clockMs(),
                lastError = null
            )
            if (authEpoch.get() != requestEpoch) {
                auditLogger.log("root", "root_auth", "revoked_during_request", "Root grant discarded because authorization was revoked concurrently")
                return@withContext Result.failure(SecurityException("Root authorization was revoked while request was pending"))
            }
            _state.value = authorized
            auditLogger.log(
                "root",
                "root_auth",
                "authorized",
                "Root granted via $managerHint ($su)",
                "$idLine; selinux=$selinux"
            )
            Result.success(authorized)
        } else {
            val reason = when {
                res.timedOut -> "Root authorization request timed out after ${timeoutSec}s"
                res.stderr.isNotBlank() -> res.stderr.trim().take(240)
                idLine.isNotBlank() -> "Unexpected uid response: $idLine"
                else -> "Superuser denied root request (exit=${res.exitCode})"
            }
            val denied = RootAuthState(
                status = RootAuthStatus.DENIED,
                suBinaryPath = su,
                suVersion = verText,
                suManagerHint = managerHint,
                uidOutput = idLine,
                selinuxMode = selinux,
                lastVerifiedAtMs = 0L,
                lastError = reason
            )
            if (authEpoch.get() == requestEpoch) {
                _state.value = denied
            }
            auditLogger.log("root", "root_auth", "denied", "Root authorization denied ($su)", reason)
            Result.failure(SecurityException(reason))
        }
    }

    fun revokeAuthorization(reason: String = "Revoked by user") {
        authEpoch.incrementAndGet()
        val prev = _state.value
        _state.value = prev.copy(
            status = if (prev.suBinaryPath != null) RootAuthStatus.REVOKED else RootAuthStatus.NO_SU_BINARY,
            uidOutput = "",
            lastVerifiedAtMs = 0L,
            lastError = reason
        )
        auditLogger.log("root", "root_auth", "revoked", reason)
    }

    /**
     * Checks whether root is currently authorized and within any configured session TTL.
     * `ttlMinutes <= 0` means valid until manually revoked.
     */
    fun isAuthorized(ttlMinutes: Int = 0): Boolean {
        val cur = _state.value
        if (cur.status != RootAuthStatus.AUTHORIZED || cur.suBinaryPath.isNullOrBlank()) {
            return false
        }
        if (ttlMinutes > 0 && cur.lastVerifiedAtMs > 0L) {
            val elapsed = clockMs() - cur.lastVerifiedAtMs
            if (elapsed > ttlMinutes * 60_000L) {
                _state.value = cur.copy(
                    status = RootAuthStatus.NOT_AUTHORIZED,
                    lastError = "Root session TTL (${ttlMinutes}m) expired; please re-authorize"
                )
                auditLogger.log("root", "root_auth", "expired", "Root authorization expired after ${ttlMinutes}m")
                return false
            }
        }
        return true
    }

    suspend fun executeAllowed(
        allowed: RootCommandPolicy.PolicyDecision.Allowed,
        timeoutSec: Long = 30L,
        ttlMinutes: Int = 0
    ): RootExecResult = withContext(Dispatchers.IO) {
        if (!isAuthorized(ttlMinutes)) {
            throw SecurityException("[ROOT_UNAUTHORIZED] Root authorization is not active or has expired")
        }
        val su = _state.value.suBinaryPath
            ?: throw IllegalStateException("[UNAVAILABLE] su binary path missing")
        val boundedTimeout = timeoutSec.coerceIn(2L, 120L)
        processRunner(listOf(su, "-c", allowed.shellCommand), boundedTimeout)
    }

    fun startInteractiveRootShell(ttlMinutes: Int = 0): Process {
        if (!isAuthorized(ttlMinutes)) {
            throw SecurityException("Root authorization is required before starting a host root shell")
        }
        val su = _state.value.suBinaryPath
            ?: throw IllegalStateException("su binary path missing")
        auditLogger.log("root", "root_terminal", "allowed_once", "Started interactive host root shell ($su)")
        return ProcessBuilder(su).redirectErrorStream(false).start()
    }

    companion object {
        const val MAX_CAPTURE_BYTES = 64 * 1024

        val STANDARD_SU_PATHS = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
            "/data/adb/ksu/bin/su",
            "/data/adb/ap/bin/su",
            "/vendor/bin/su"
        )

        fun inferManagerHint(suPath: String, versionLine: String): String {
            val combined = "$suPath $versionLine".lowercase()
            return when {
                combined.contains("sukisu") -> "SukiSU"
                combined.contains("kernelsu") || combined.contains("/ksu/") -> "KernelSU"
                combined.contains("apatch") || combined.contains("/ap/") -> "APatch"
                combined.contains("magisk") -> "Magisk"
                combined.contains("supersu") -> "SuperSU"
                Regex("""\bksu\b""").containsMatchIn(combined) -> "KernelSU"
                else -> "Generic su"
            }
        }

        private fun defaultProcessRunner(command: List<String>, timeoutSec: Long): RootExecResult {
            val startMs = System.currentTimeMillis()
            val proc = ProcessBuilder(command).redirectErrorStream(false).start()
            val stdoutBuf = StringBuilder()
            val stderrBuf = StringBuilder()

            fun drainBounded(stream: java.io.InputStream, dest: StringBuilder) {
                runCatching {
                    val buf = ByteArray(4096)
                    var total = 0
                    var truncated = false
                    while (true) {
                        val n = stream.read(buf)
                        if (n < 0) break
                        if (total < MAX_CAPTURE_BYTES) {
                            val take = minOf(n, MAX_CAPTURE_BYTES - total)
                            dest.append(String(buf, 0, take, Charsets.UTF_8))
                            total += take
                        } else if (!truncated) {
                            truncated = true
                            dest.append("\n...[output truncated at ${MAX_CAPTURE_BYTES / 1024}KB]...")
                        }
                    }
                }
            }

            val tOut = Thread { drainBounded(proc.inputStream, stdoutBuf) }
            val tErr = Thread { drainBounded(proc.errorStream, stderrBuf) }
            tOut.start()
            tErr.start()

            val finished = proc.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
            }
            tOut.join(2_000)
            tErr.join(2_000)

            val code = if (finished) proc.exitValue() else -1
            return RootExecResult(
                exitCode = code,
                stdout = stdoutBuf.toString(),
                stderr = stderrBuf.toString(),
                durationMs = System.currentTimeMillis() - startMs,
                timedOut = !finished
            )
        }
    }
}
