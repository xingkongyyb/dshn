package dev.dshn.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LocalPluginImporterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun stagesTgzTarballIntoContainerLocalPluginsDir() {
        val rootfs = tmp.newFolder("ubuntu")
        val gzBytes = ByteArrayOutputStream().use { baos ->
            GZIPOutputStream(baos).use { it.write("dummy-tar-payload".toByteArray()) }
            baos.toByteArray()
        }
        val res = LocalPluginImporter.stageLocalPluginStream(
            rootfs = rootfs,
            displayName = "my-custom-plugin-1.0.0.tgz",
            input = ByteArrayInputStream(gzBytes)
        )
        assertTrue(res.isSuccess)
        val staged = res.getOrThrow()
        assertEquals("tarball", staged.kind)
        assertTrue(staged.containerPath.startsWith("/root/.dsh/local-plugins/my-custom-plugin-1.0.0-"))
        assertTrue(staged.containerPath.endsWith(".tgz"))
        assertTrue(staged.hostFile.isFile)
    }

    @Test
    fun stagesZipAndUnwrapsSingleTopLevelPackageFolder() {
        val rootfs = tmp.newFolder("ubuntu")
        val zipBytes = ByteArrayOutputStream().use { baos ->
            ZipOutputStream(baos).use { zos ->
                zos.putNextEntry(ZipEntry("dsh-plugin-demo-main/"))
                zos.closeEntry()
                zos.putNextEntry(ZipEntry("dsh-plugin-demo-main/package.json"))
                zos.write("""{"name":"dsh-plugin-demo","version":"1.0.0","dsh":{"bundle":{}}}""".toByteArray())
                zos.closeEntry()
                zos.putNextEntry(ZipEntry("dsh-plugin-demo-main/index.js"))
                zos.write("export function apply() {}".toByteArray())
                zos.closeEntry()
            }
            baos.toByteArray()
        }
        val res = LocalPluginImporter.stageLocalPluginStream(
            rootfs = rootfs,
            displayName = "dsh-plugin-demo-main.zip",
            input = ByteArrayInputStream(zipBytes)
        )
        assertTrue(res.isSuccess)
        val staged = res.getOrThrow()
        assertEquals("directory", staged.kind)
        assertTrue(staged.containerPath.endsWith("/dsh-plugin-demo-main"))
        assertTrue(File(staged.hostFile, "package.json").isFile)
    }

    @Test
    fun rejectsZipSlipPathTraversal() {
        val rootfs = tmp.newFolder("ubuntu")
        val zipBytes = ByteArrayOutputStream().use { baos ->
            ZipOutputStream(baos).use { zos ->
                zos.putNextEntry(ZipEntry("../escape.txt"))
                zos.write("bad".toByteArray())
                zos.closeEntry()
            }
            baos.toByteArray()
        }
        val res = LocalPluginImporter.stageLocalPluginStream(
            rootfs = rootfs,
            displayName = "evil.zip",
            input = ByteArrayInputStream(zipBytes)
        )
        assertTrue(res.isFailure)
    }

    @Test
    fun rejectsUnsupportedOrNonPackageFiles() {
        val rootfs = tmp.newFolder("ubuntu")
        val plainRes = LocalPluginImporter.stageLocalPluginStream(
            rootfs = rootfs,
            displayName = "notes.txt",
            input = ByteArrayInputStream("hello world".toByteArray())
        )
        assertTrue(plainRes.isFailure)

        val zipWithoutPkg = ByteArrayOutputStream().use { baos ->
            ZipOutputStream(baos).use { zos ->
                zos.putNextEntry(ZipEntry("readme.md"))
                zos.write("# no package".toByteArray())
                zos.closeEntry()
            }
            baos.toByteArray()
        }
        val zipRes = LocalPluginImporter.stageLocalPluginStream(
            rootfs = rootfs,
            displayName = "nopkg.zip",
            input = ByteArrayInputStream(zipWithoutPkg)
        )
        assertTrue(zipRes.isFailure)
    }
}
