package dev.dshn.app.runtime

import java.io.File
import java.util.UUID

/**
 * Avoids Binder's ~1MB transaction limit when passing session history surfaces
 * or large diagnostic dumps between `:runtime` and `:app`.
 */
object LargePayloadXfer {
    private const val FILE_PREFIX = "@file:"
    private const val INLINE_THRESHOLD_BYTES = 32 * 1024
    private const val STALE_AGE_MS = 10 * 60 * 1000L

    fun encode(ipcDir: File, prefix: String, payload: String): String {
        if (payload.toByteArray(Charsets.UTF_8).size <= INLINE_THRESHOLD_BYTES) {
            return payload
        }
        if (!ipcDir.exists()) ipcDir.mkdirs()
        cleanStale(ipcDir)
        val tmp = File(ipcDir, ".$prefix-${UUID.randomUUID()}.tmp")
        val target = File(ipcDir, "$prefix-${UUID.randomUUID()}.json")
        tmp.writeText(payload, Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            target.writeText(payload, Charsets.UTF_8)
            tmp.delete()
        }
        return "$FILE_PREFIX${target.absolutePath}"
    }

    fun decode(encoded: String): String {
        if (!encoded.startsWith(FILE_PREFIX)) return encoded
        val file = File(encoded.removePrefix(FILE_PREFIX))
        return try {
            file.readText(Charsets.UTF_8)
        } finally {
            runCatching { file.delete() }
        }
    }

    fun cleanStale(ipcDir: File, nowMs: Long = System.currentTimeMillis()) {
        val files = ipcDir.listFiles() ?: return
        for (f in files) {
            if (nowMs - f.lastModified() > STALE_AGE_MS) {
                runCatching { f.delete() }
            }
        }
    }
}
