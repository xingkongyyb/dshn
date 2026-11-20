package dev.dshn.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

class TarGzExtractorTest {

    private class RecordingPosixOps : TarGzExtractor.PosixFsOps {
        val symlinks = mutableListOf<Pair<String, String>>()
        val modes = mutableMapOf<String, Int>()

        override fun symlink(target: String, linkFile: File) {
            symlinks += linkFile.name to target
            linkFile.parentFile?.mkdirs()
            // Write a marker file when running on Windows JVM without symlink privileges
            runCatching {
                Files.deleteIfExists(linkFile.toPath())
                Files.createSymbolicLink(linkFile.toPath(), java.nio.file.Paths.get(target))
            }.onFailure {
                linkFile.writeText("SYMLINK->$target", Charsets.UTF_8)
            }
        }

        override fun chmod(file: File, mode: Int) {
            modes[file.name] = mode and 0x1FF
        }
    }

    @Test
    fun extractsFilesLongPathsStripComponentsSymlinksAndHardlinksWithSha256() {
        val longRelPath = "node-v24.22.3-linux-arm64/lib/node_modules/@deepseek-ai/dsh-very-long-package-name-for-pax-header-testing/subdir/deep-entry-file.mjs"
        assertTrue(longRelPath.length > 100)

        val tgz = buildTarGz {
            addDir("node-v24.22.3-linux-arm64/bin/", 0x1ED)
            addFile("node-v24.22.3-linux-arm64/bin/node", "ELF-MOCK".toByteArray(), 0x1ED)
            addSymlink("node-v24.22.3-linux-arm64/bin/npm", "../lib/node_modules/npm/bin/npm-cli.js")
            addHardlink("node-v24.22.3-linux-arm64/bin/node-hard", "node-v24.22.3-linux-arm64/bin/node")
            addFilePax(longRelPath, "export const ok = true;\n".toByteArray(), 0x1A4)
            addFileGnuLongName(
                "node-v24.22.3-linux-arm64/lib/node_modules/@deepseek-ai/gnu-long-name-test-directory-exceeding-one-hundred-characters/file.txt",
                "gnu-long-ok".toByteArray(),
                0x124 // 0444 read-only in tar -> should become owner-writable 0644
            )
        }
        val expectedSha = sha256Hex(tgz)
        val outDir = Files.createTempDirectory("dshn-tar-test").toFile()
        val ops = RecordingPosixOps()
        try {
            val stats = TarGzExtractor.extract(
                gzipStream = ByteArrayInputStream(tgz),
                destDir = outDir,
                stripComponents = 1,
                expectedSha256 = expectedSha,
                posixOps = ops
            )

            assertEquals(expectedSha, stats.sha256Hex)
            assertEquals("ELF-MOCK", File(outDir, "bin/node").readText())
            assertEquals("ELF-MOCK", File(outDir, "bin/node-hard").readText())
            assertEquals(1, stats.hardlinksCopied)
            assertEquals(1, stats.symlinksCreated)
            assertTrue(ops.symlinks.any { it.first == "npm" && it.second == "../lib/node_modules/npm/bin/npm-cli.js" })
            assertEquals(0x1ED, ops.modes["node"]) // 0755 preserved
            assertEquals(0x1A4, ops.modes["file.txt"]) // 0444 promoted to 0644 (owner rw)

            val paxFile = File(outDir, longRelPath.substringAfter('/'))
            assertTrue(paxFile.exists())
            assertEquals("export const ok = true;\n", paxFile.readText())

            val gnuFile = File(
                outDir,
                "lib/node_modules/@deepseek-ai/gnu-long-name-test-directory-exceeding-one-hundred-characters/file.txt"
            )
            assertTrue(gnuFile.exists())
            assertEquals("gnu-long-ok", gnuFile.readText())
        } finally {
            outDir.deleteRecursively()
        }
    }

    @Test
    fun rejectsPathTraversalAndSha256Mismatch() {
        val badTar = buildTarGz {
            addFile("../escape.txt", "nope".toByteArray(), 0x1A4)
        }
        val outDir = Files.createTempDirectory("dshn-tar-sec").toFile()
        try {
            try {
                TarGzExtractor.extract(ByteArrayInputStream(badTar), outDir)
                fail("Expected traversal to throw IOException")
            } catch (e: IOException) {
                assertTrue(e.message.orEmpty().contains("traversal"))
            }
            assertFalse(File(outDir.parentFile, "escape.txt").exists())

            val goodTar = buildTarGz {
                addFile("hello.txt", "world".toByteArray(), 0x1A4)
            }
            try {
                TarGzExtractor.extract(
                    gzipStream = ByteArrayInputStream(goodTar),
                    destDir = outDir,
                    expectedSha256 = "0000000000000000000000000000000000000000000000000000000000000000"
                )
                fail("Expected SHA-256 mismatch to throw IOException")
            } catch (e: IOException) {
                assertTrue(e.message.orEmpty().contains("SHA-256 mismatch"))
            }
        } finally {
            outDir.deleteRecursively()
        }
    }

    private fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    private fun buildTarGz(block: TarBuilder.() -> Unit): ByteArray {
        val raw = ByteArrayOutputStream()
        GZIPOutputStream(raw).use { gz ->
            val builder = TarBuilder(gz)
            builder.block()
            gz.write(ByteArray(1024))
        }
        return raw.toByteArray()
    }

    private class TarBuilder(private val out: java.io.OutputStream) {
        fun addDir(name: String, mode: Int) {
            out.write(header(name, 0, mode, '5'))
        }

        fun addFile(name: String, data: ByteArray, mode: Int) {
            out.write(header(name, data.size.toLong(), mode, '0'))
            out.write(data)
            pad(data.size)
        }

        fun addSymlink(name: String, target: String) {
            out.write(header(name, 0, 0x1FF, '2', target))
        }

        fun addHardlink(name: String, target: String) {
            out.write(header(name, 0, 0x1ED, '1', target))
        }

        fun addFilePax(path: String, data: ByteArray, mode: Int) {
            val body = " path=$path\n"
            var len = body.toByteArray(Charsets.UTF_8).size + 1
            while (true) {
                val rec = "$len$body".toByteArray(Charsets.UTF_8)
                if (rec.size == len) {
                    out.write(header("PaxHeader/entry", rec.size.toLong(), 0x1A4, 'x'))
                    out.write(rec)
                    pad(rec.size)
                    break
                }
                len = rec.size
            }
            addFile(path.take(99), data, mode)
        }

        fun addFileGnuLongName(path: String, data: ByteArray, mode: Int) {
            val nameBytes = path.toByteArray(Charsets.UTF_8) + byteArrayOf(0)
            out.write(header("././@LongLink", nameBytes.size.toLong(), 0x1A4, 'L'))
            out.write(nameBytes)
            pad(nameBytes.size)
            addFile(path.take(99), data, mode)
        }

        private fun pad(size: Int) {
            val rem = size % 512
            if (rem != 0) out.write(ByteArray(512 - rem))
        }

        private fun header(name: String, size: Long, mode: Int, type: Char, link: String = ""): ByteArray {
            val h = ByteArray(512)
            name.toByteArray(Charsets.UTF_8).copyInto(h, 0, 0, minOf(name.length, 100))
            writeOctal(h, 100, 8, mode.toLong())
            writeOctal(h, 108, 8, 0)
            writeOctal(h, 116, 8, 0)
            writeOctal(h, 124, 12, size)
            writeOctal(h, 136, 12, 1700000000L)
            for (i in 148 until 156) h[i] = ' '.code.toByte()
            h[156] = type.code.toByte()
            if (link.isNotEmpty()) {
                val lb = link.toByteArray(Charsets.UTF_8)
                lb.copyInto(h, 157, 0, minOf(lb.size, 100))
            }
            "ustar\u000000".toByteArray(Charsets.US_ASCII).copyInto(h, 257)
            var sum = 0
            for (b in h) sum += (b.toInt() and 0xFF)
            val chk = sum.toString(8).padStart(6, '0')
            chk.toByteArray(Charsets.US_ASCII).copyInto(h, 148)
            h[154] = 0
            h[155] = ' '.code.toByte()
            return h
        }

        private fun writeOctal(h: ByteArray, off: Int, len: Int, v: Long) {
            val s = v.toString(8).padStart(len - 1, '0')
            s.toByteArray(Charsets.US_ASCII).copyInto(h, off)
            h[off + len - 1] = 0
        }
    }
}
