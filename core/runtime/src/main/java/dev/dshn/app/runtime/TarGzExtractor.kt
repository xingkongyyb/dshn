package dev.dshn.app.runtime

import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * Pure-Kotlin streaming `.tar.gz` extractor for Android & JVM unit tests.
 *
 * Features:
 * - Single-pass streaming GZIP decompression + optional SHA-256 verification (drains compressed
 *   trailer so the digest matches `sha256sum` byte-for-byte).
 * - Supports USTAR (`prefix + name`), GNU LongName/LongLink (`'L'`/`'K'`), POSIX PAX extended
 *   headers (`'x'`/`'g'`), octal and base-256 numeric fields, and `stripComponents`.
 * - Safe for Android non-root app UIDs:
 *   - Enforces owner `rwx` on directories (`| 0700`) and owner `rw` on regular files (`| 0600`)
 *     while preserving all executable bits (`0111`).
 *   - Converts tar hardlinks (`'1'`) to file copies (Android SELinux blocks `link(2)` for apps).
 *   - Safely skips char/block device nodes and FIFOs (`'3'`/`'4'`/`'6'`).
 *   - Rejects path traversal (`..` segments, NUL bytes) and never writes through leaf symlinks.
 */
object TarGzExtractor {

    interface PosixFsOps {
        fun symlink(target: String, linkFile: File)
        fun chmod(file: File, mode: Int)
    }

    object DefaultPosixFsOps : PosixFsOps {
        private val androidOsSupported: Boolean = runCatching {
            android.system.Os.getpid()
            true
        }.getOrDefault(false)

        override fun symlink(target: String, linkFile: File) {
            deleteQuietlyNoFollow(linkFile)
            val viaOs = if (androidOsSupported) {
                runCatching {
                    android.system.Os.symlink(target, linkFile.absolutePath)
                    true
                }.getOrDefault(false)
            } else {
                false
            }
            if (!viaOs) {
                Files.createSymbolicLink(linkFile.toPath(), Paths.get(target))
            }
        }

        override fun chmod(file: File, mode: Int) {
            val clean = mode and 0x1FF
            val viaOs = if (androidOsSupported) {
                runCatching {
                    android.system.Os.chmod(file.absolutePath, clean)
                    true
                }.getOrDefault(false)
            } else {
                false
            }
            if (!viaOs) {
                file.setReadable((clean and 0x100) != 0, (clean and 0x044) == 0)
                file.setWritable((clean and 0x080) != 0, (clean and 0x022) == 0)
                file.setExecutable((clean and 0x040) != 0, (clean and 0x011) == 0)
            }
        }
    }

    data class ExtractStats(
        val entriesExtracted: Int,
        val filesExtracted: Int,
        val symlinksCreated: Int,
        val hardlinksCopied: Int,
        val compressedBytesRead: Long,
        val sha256Hex: String
    )

    private data class DeferredHardlink(
        val targetPath: File,
        val sourcePath: File,
        val mode: Int
    )

    fun extract(
        gzipStream: InputStream,
        destDir: File,
        stripComponents: Int = 0,
        expectedSha256: String? = null,
        posixOps: PosixFsOps = DefaultPosixFsOps,
        onProgress: ((compressedBytesRead: Long, entriesExtracted: Int) -> Unit)? = null
    ): ExtractStats {
        destDir.mkdirs()
        val digestStream = CountingDigestInputStream(BufferedInputStream(gzipStream, 64 * 1024))
        var entries = 0
        var files = 0
        var symlinks = 0
        var hardlinks = 0
        val deferredHardlinks = mutableListOf<DeferredHardlink>()
        val ensuredDirs = HashSet<String>(4096)
        ensuredDirs.add(destDir.absolutePath)
        val ioBuf = ByteArray(128 * 1024)
        var lastReportBytes = 0L

        digestStream.use { countedRaw ->
            val nonClosing = object : FilterInputStream(countedRaw) {
                override fun close() { /* closed by outer digestStream.use */ }
            }
            GZIPInputStream(nonClosing, 64 * 1024).use { tarIn ->
                val header = ByteArray(512)
                var zeroBlocks = 0
                var globalPax = emptyMap<String, String>()
                var nextPax = emptyMap<String, String>()
                var gnuLongName: String? = null
                var gnuLongLink: String? = null

                while (true) {
                    val read = readFullyOrEof(tarIn, header, 512)
                    if (read == 0) break
                    if (read < 512) throw IOException("Truncated tar header ($read bytes)")

                    if (isAllZero(header)) {
                        zeroBlocks++
                        if (zeroBlocks >= 2) break
                        continue
                    }
                    zeroBlocks = 0

                    val typeFlag = header[156].toInt().toChar()
                    val headerSize = parseTarNumber(header, 124, 12)
                    val headerMode = parseTarNumber(header, 100, 8).toInt()
                    val rawName = buildUstarName(header)
                    val rawLinkName = readTarString(header, 157, 100)

                    val effectivePax = if (globalPax.isEmpty()) nextPax else globalPax + nextPax
                    val entrySize = effectivePax["size"]?.toLongOrNull() ?: headerSize
                    val entryName = effectivePax["path"] ?: gnuLongName ?: rawName
                    val entryLinkName = effectivePax["linkpath"] ?: gnuLongLink ?: rawLinkName

                    when (typeFlag) {
                        'L' -> {
                            val data = readExactBytes(tarIn, entrySize.toInt())
                            skipPadding(tarIn, entrySize)
                            gnuLongName = decodeNullTerminatedUtf8(data)
                            continue
                        }
                        'K' -> {
                            val data = readExactBytes(tarIn, entrySize.toInt())
                            skipPadding(tarIn, entrySize)
                            gnuLongLink = decodeNullTerminatedUtf8(data)
                            continue
                        }
                        'x' -> {
                            val data = readExactBytes(tarIn, entrySize.toInt())
                            skipPadding(tarIn, entrySize)
                            nextPax = parsePaxRecords(data)
                            continue
                        }
                        'g' -> {
                            val data = readExactBytes(tarIn, entrySize.toInt())
                            skipPadding(tarIn, entrySize)
                            globalPax = globalPax + parsePaxRecords(data)
                            continue
                        }
                    }

                    // Consume per-entry metadata overrides
                    nextPax = emptyMap()
                    gnuLongName = null
                    gnuLongLink = null

                    val normalized = normalizeRelativePath(entryName, stripComponents)
                    if (normalized == null) {
                        skipFully(tarIn, entrySize)
                        skipPadding(tarIn, entrySize)
                        continue
                    }

                    val targetFile = if (normalized.isEmpty()) destDir else File(destDir, normalized)
                    when (typeFlag) {
                        '5' -> {
                            skipFully(tarIn, entrySize)
                            skipPadding(tarIn, entrySize)
                            if (targetFile != destDir) {
                                if (Files.isSymbolicLink(targetFile.toPath())) {
                                    // Keep existing merged-usr symlink (e.g. bin -> usr/bin) if archive has both
                                } else {
                                    targetFile.mkdirs()
                                    val dirMode = (if (headerMode > 0) headerMode else 0x1ED) or 0x1C0
                                    posixOps.chmod(targetFile, dirMode)
                                    ensuredDirs.add(targetFile.absolutePath)
                                }
                            }
                            entries++
                        }
                        '0', '\u0000' -> {
                            ensureWritableParent(targetFile.parentFile, posixOps, ensuredDirs)
                            deleteQuietlyNoFollow(targetFile)
                            FileOutputStream(targetFile).use { out ->
                                copyExact(tarIn, out, entrySize, ioBuf)
                            }
                            skipPadding(tarIn, entrySize)
                            val fileMode = (if (headerMode > 0) headerMode else 0x1A4) or 0x180
                            posixOps.chmod(targetFile, fileMode)
                            entries++
                            files++
                        }
                        '2' -> {
                            skipFully(tarIn, entrySize)
                            skipPadding(tarIn, entrySize)
                            if (targetFile != destDir) {
                                ensureWritableParent(targetFile.parentFile, posixOps, ensuredDirs)
                                posixOps.symlink(entryLinkName, targetFile)
                                entries++
                                symlinks++
                            }
                        }
                        '1' -> {
                            skipFully(tarIn, entrySize)
                            skipPadding(tarIn, entrySize)
                            val linkRel = normalizeRelativePath(entryLinkName, stripComponents)
                            if (linkRel != null && linkRel.isNotEmpty() && targetFile != destDir) {
                                val srcFile = File(destDir, linkRel)
                                val fileMode = (if (headerMode > 0) headerMode else 0x1A4) or 0x180
                                if (srcFile.exists() && !Files.isSymbolicLink(srcFile.toPath())) {
                                    copyHardlinkTarget(srcFile, targetFile, fileMode, posixOps, ioBuf, ensuredDirs)
                                    entries++
                                    hardlinks++
                                } else {
                                    deferredHardlinks += DeferredHardlink(targetFile, srcFile, fileMode)
                                }
                            }
                        }
                        // Safely ignore device nodes, FIFOs, and unknown vendor metadata entries
                        else -> {
                            skipFully(tarIn, entrySize)
                            skipPadding(tarIn, entrySize)
                        }
                    }

                    if (onProgress != null && countedRaw.bytesRead - lastReportBytes >= 256 * 1024) {
                        lastReportBytes = countedRaw.bytesRead
                        onProgress(countedRaw.bytesRead, entries)
                    }
                }
                // Drain remaining tar padding so GZIPInputStream verifies the gzip CRC32 trailer
                while (tarIn.read(ioBuf) >= 0) {
                    // drain
                }
            }
            // Drain any remaining bytes on the compressed stream for exact SHA-256 match
            while (countedRaw.read(ioBuf) >= 0) {
                // drain
            }
        }

        for (hl in deferredHardlinks) {
            if (hl.sourcePath.exists()) {
                copyHardlinkTarget(hl.sourcePath, hl.targetPath, hl.mode, posixOps, ioBuf, ensuredDirs)
                entries++
                hardlinks++
            }
        }

        val actualSha256 = digestStream.digestHex()
        if (!expectedSha256.isNullOrBlank() && !actualSha256.equals(expectedSha256.trim(), ignoreCase = true)) {
            throw IOException("SHA-256 mismatch: expected=$expectedSha256 actual=$actualSha256")
        }

        onProgress?.invoke(digestStream.bytesRead, entries)
        return ExtractStats(
            entriesExtracted = entries,
            filesExtracted = files,
            symlinksCreated = symlinks,
            hardlinksCopied = hardlinks,
            compressedBytesRead = digestStream.bytesRead,
            sha256Hex = actualSha256
        )
    }

    private fun copyHardlinkTarget(
        src: File,
        dst: File,
        mode: Int,
        posixOps: PosixFsOps,
        buf: ByteArray,
        ensuredDirs: HashSet<String>
    ) {
        ensureWritableParent(dst.parentFile, posixOps, ensuredDirs)
        deleteQuietlyNoFollow(dst)
        src.inputStream().use { input ->
            FileOutputStream(dst).use { out ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
            }
        }
        posixOps.chmod(dst, mode)
    }

    private fun ensureWritableParent(dir: File?, posixOps: PosixFsOps, ensuredDirs: HashSet<String>) {
        if (dir == null) return
        val key = dir.absolutePath
        if (ensuredDirs.contains(key)) return
        if (!dir.exists()) {
            dir.mkdirs()
        }
        if (!Files.isSymbolicLink(dir.toPath()) && dir.isDirectory) {
            if (!dir.canWrite() || !dir.canExecute()) {
                posixOps.chmod(dir, 0x1ED) // 0755
            }
            ensuredDirs.add(key)
        }
    }

    private fun deleteQuietlyNoFollow(file: File) {
        val p = file.toPath()
        if (Files.exists(p, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                file.deleteRecursively()
            } else {
                Files.deleteIfExists(p)
            }
        }
    }

    /**
     * Normalizes a tar path, strips [stripComponents] leading segments, and rejects path traversal.
     * Returns `null` if the entry should be skipped (e.g. stripped away completely).
     * Returns `""` for the root directory `.` when `stripComponents == 0`.
     */
    internal fun normalizeRelativePath(raw: String, stripComponents: Int): String? {
        if (raw.contains('\u0000')) throw IOException("Invalid NUL byte in tar entry path")
        var cleaned = raw.replace('\\', '/')
        while (cleaned.startsWith("/")) cleaned = cleaned.substring(1)
        val rawParts = cleaned.split('/').filter { it.isNotEmpty() && it != "." }
        for (part in rawParts) {
            if (part == "..") throw IOException("Path traversal blocked in tar entry: $raw")
        }
        if (rawParts.size < stripComponents) return null
        val kept = rawParts.drop(stripComponents)
        if (kept.isEmpty()) return if (stripComponents == 0) "" else null
        return kept.joinToString("/")
    }

    internal fun parsePaxRecords(data: ByteArray): Map<String, String> {
        val out = linkedMapOf<String, String>()
        var pos = 0
        while (pos < data.size) {
            var sp = pos
            while (sp < data.size && data[sp] != ' '.code.toByte()) sp++
            if (sp >= data.size) break
            val lenStr = String(data, pos, sp - pos, Charsets.US_ASCII).trim()
            val recLen = lenStr.toIntOrNull() ?: break
            if (recLen <= 0 || pos + recLen > data.size) break
            val bodyStart = sp + 1
            var bodyEnd = pos + recLen
            if (bodyEnd > bodyStart && data[bodyEnd - 1] == '\n'.code.toByte()) bodyEnd--
            var eq = bodyStart
            while (eq < bodyEnd && data[eq] != '='.code.toByte()) eq++
            if (eq < bodyEnd) {
                val key = String(data, bodyStart, eq - bodyStart, Charsets.UTF_8)
                val value = String(data, eq + 1, bodyEnd - (eq + 1), Charsets.UTF_8)
                out[key] = value
            }
            pos += recLen
        }
        return out
    }

    private fun buildUstarName(header: ByteArray): String {
        val name = readTarString(header, 0, 100)
        val prefix = readTarString(header, 345, 155)
        return if (prefix.isNotEmpty()) "$prefix/$name" else name
    }

    private fun readTarString(buf: ByteArray, offset: Int, length: Int): String {
        var end = offset
        val max = offset + length
        while (end < max && buf[end] != 0.toByte()) end++
        return String(buf, offset, end - offset, Charsets.UTF_8)
    }

    private fun decodeNullTerminatedUtf8(data: ByteArray): String {
        var end = data.size
        while (end > 0 && data[end - 1] == 0.toByte()) end--
        return String(data, 0, end, Charsets.UTF_8)
    }

    internal fun parseTarNumber(buf: ByteArray, offset: Int, length: Int): Long {
        val first = buf[offset].toInt() and 0xFF
        if ((first and 0x80) != 0) {
            val negative = (first and 0x40) != 0
            var result = if (negative) -1L else 0L
            result = (result shl 7) or (first and 0x7F).toLong()
            for (i in 1 until length) {
                result = (result shl 8) or (buf[offset + i].toInt() and 0xFF).toLong()
            }
            return result
        }
        var start = offset
        val end = offset + length
        while (start < end && (buf[start] == ' '.code.toByte() || buf[start] == 0.toByte())) start++
        var result = 0L
        while (start < end) {
            val b = buf[start].toInt() and 0xFF
            if (b == 0 || b == ' '.code) break
            if (b < '0'.code || b > '7'.code) break
            result = (result shl 3) + (b - '0'.code)
            start++
        }
        return result
    }

    private fun isAllZero(block: ByteArray): Boolean {
        for (b in block) if (b != 0.toByte()) return false
        return true
    }

    private fun readFullyOrEof(input: InputStream, dst: ByteArray, len: Int): Int {
        var off = 0
        while (off < len) {
            val n = input.read(dst, off, len - off)
            if (n < 0) return off
            off += n
        }
        return off
    }

    private fun readExactBytes(input: InputStream, len: Int): ByteArray {
        val out = ByteArray(len)
        val n = readFullyOrEof(input, out, len)
        if (n < len) throw IOException("Unexpected EOF reading tar entry ($n/$len)")
        return out
    }

    private fun copyExact(input: InputStream, out: FileOutputStream, size: Long, buf: ByteArray) {
        var remaining = size
        while (remaining > 0) {
            val toRead = minOf(remaining, buf.size.toLong()).toInt()
            val n = input.read(buf, 0, toRead)
            if (n < 0) throw IOException("Unexpected EOF while extracting file ($remaining bytes left)")
            out.write(buf, 0, n)
            remaining -= n
        }
    }

    private fun skipFully(input: InputStream, bytes: Long) {
        var remaining = bytes
        val skipBuf = ByteArray(8192)
        while (remaining > 0) {
            val toRead = minOf(remaining, skipBuf.size.toLong()).toInt()
            val n = input.read(skipBuf, 0, toRead)
            if (n < 0) throw IOException("Unexpected EOF while skipping tar data")
            remaining -= n
        }
    }

    private fun skipPadding(input: InputStream, entrySize: Long) {
        val rem = (entrySize % 512L).toInt()
        if (rem != 0) skipFully(input, (512 - rem).toLong())
    }

    private class CountingDigestInputStream(input: InputStream) : FilterInputStream(input) {
        private val md = MessageDigest.getInstance("SHA-256")
        var bytesRead: Long = 0L
            private set

        override fun read(): Int {
            val b = super.read()
            if (b >= 0) {
                md.update(b.toByte())
                bytesRead++
            }
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) {
                md.update(b, off, n)
                bytesRead += n
            }
            return n
        }

        fun digestHex(): String = md.digest().joinToString("") { "%02x".format(it) }
    }
}
