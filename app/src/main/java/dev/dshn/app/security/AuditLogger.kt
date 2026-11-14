package dev.dshn.app.security

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class AuditEntry(
    val timestampMs: Long,
    val category: String,
    val tool: String,
    val decision: String,
    val summary: String,
    val detail: String = ""
)

/**
 * Bounded JSONL audit trail written outside container rootfs under filesDir/security/.
 */
class AuditLogger(private val securityDir: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()
    private val logFile = File(securityDir, "audit.jsonl")

    fun log(category: String, tool: String, decision: String, summary: String, detail: String = "") {
        val entry = AuditEntry(
            timestampMs = System.currentTimeMillis(),
            category = category,
            tool = tool,
            decision = decision,
            summary = scrub(summary, 240),
            detail = scrub(detail, 512)
        )
        val line = json.encodeToString(entry) + "\n"
        synchronized(lock) {
            if (!securityDir.exists()) {
                securityDir.mkdirs()
                securityDir.setReadable(false, false)
                securityDir.setReadable(true, true)
                securityDir.setWritable(true, true)
                securityDir.setExecutable(true, true)
            }
            if (logFile.exists() && logFile.length() > MAX_LOG_BYTES) {
                rotateLocked()
            }
            logFile.appendText(line, Charsets.UTF_8)
        }
    }

    fun readRecent(limit: Int = 100): List<AuditEntry> {
        synchronized(lock) {
            if (!logFile.exists()) return emptyList()
            val lines = runCatching { logFile.readLines(Charsets.UTF_8) }.getOrDefault(emptyList())
            return lines.takeLast(limit).asReversed().mapNotNull { line ->
                if (line.isBlank()) null else runCatching { json.decodeFromString<AuditEntry>(line) }.getOrNull()
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            if (logFile.exists()) logFile.delete()
        }
    }

    private fun rotateLocked() {
        val lines = runCatching { logFile.readLines(Charsets.UTF_8) }.getOrDefault(emptyList())
        val kept = lines.takeLast(KEEP_LINES_ON_ROTATE).joinToString("\n") + if (lines.isNotEmpty()) "\n" else ""
        val tmp = File(securityDir, "audit.jsonl.tmp")
        tmp.writeText(kept, Charsets.UTF_8)
        if (!tmp.renameTo(logFile)) {
            logFile.writeText(kept, Charsets.UTF_8)
            tmp.delete()
        }
    }

    private fun scrub(raw: String, maxLen: Int): String {
        val scrubbed = raw
            .replace(Regex("""sk-[A-Za-z0-9_-]{12,}"""), "[REDACTED_KEY]")
            .replace(Regex("""[\r\n]+"""), " ")
            .trim()
        return if (scrubbed.length <= maxLen) scrubbed else scrubbed.take(maxLen - 3) + "..."
    }

    companion object {
        private const val MAX_LOG_BYTES = 512 * 1024L
        private const val KEEP_LINES_ON_ROTATE = 250
    }
}
