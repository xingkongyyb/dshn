package dev.dshn.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EnvManagerTest {

    @Rule
    @JvmField
    val tmp = TemporaryFolder()

    private fun setupFakeRootfs(filesDir: File): File {
        val rootfs = File(filesDir, "linux/ubuntu")
        File(rootfs, "bin/sh").apply { parentFile!!.mkdirs(); writeText("#!/bin/sh\n") }
        File(rootfs, "opt/node24/bin/node").apply { parentFile!!.mkdirs(); writeText("#!/bin/sh\n") }
        File(rootfs, "opt/node24/lib/node_modules/@deepseek-ai/dsh/package.json").apply {
            parentFile!!.mkdirs()
            writeText("""{"version":"0.2.2-rc.1"}""")
        }
        File(rootfs, "etc/resolv.conf").apply {
            parentFile!!.mkdirs()
            writeText("nameserver 127.0.0.53\n")
        }
        File(rootfs, "root/.dsh/sessions").mkdirs()
        File(rootfs, "root/.dsh/api_key").writeText("sk-live-keep-me")
        return rootfs
    }

    @Test
    fun syncsAssets_snapshots_andRejectsIncompatibleRollback() {
        val filesDir = tmp.newFolder("files")
        val rootfs = setupFakeRootfs(filesDir)

        val env = EnvManager(
            filesDir = filesDir,
            assetOpener = { path -> "// asset:$path\n".toByteArray() },
            commandRunner = { _, _ -> ContainerLauncher.Result(0, "PTY_LOAD_OK\nv24.19.0", "") }
        )

        val manifest = env.ensureRuntimeAssets()
        assertTrue(manifest.rootfsReady)
        assertEquals("0.2.2-rc.1", manifest.dshVersion)
        assertTrue(File(rootfs, "opt/dshn/dshn-bridge.patch.yml").exists())
        val resolv = File(rootfs, "etc/resolv.conf").readText()
        assertTrue(resolv.contains("223.5.5.5"))

        // Write valid v1 session and snapshot it
        val sessFile = File(rootfs, "root/.dsh/sessions/s1.jsonl")
        sessFile.writeText("""{"id":"s1","formatVersion":1}""" + "\n")
        val snapRes = env.createSnapshot("baseline")
        assertTrue(snapRes.isSuccess)
        val snapId = snapRes.getOrThrow().id
        // API key must NOT be copied into snapshot directory
        assertFalse(File(env.snapshotsDir, "$snapId/dsh-data/api_key").exists())

        // Mutate live session, then restore snapshot & confirm api_key preserved
        sessFile.writeText("""{"id":"mutated"}""" + "\n")
        val restoreOk = env.restoreSnapshot(snapId)
        assertTrue(restoreOk.isSuccess)
        assertTrue(sessFile.readText().contains("formatVersion"))
        assertEquals("sk-live-keep-me", File(rootfs, "root/.dsh/api_key").readText())

        // Tamper snapshot with incompatible formatVersion 99 -> restore MUST fail without losing live .dsh
        val snapSess = File(env.snapshotsDir, "$snapId/dsh-data/sessions/s1.jsonl")
        snapSess.writeText("""{"id":"s1","formatVersion":99}""" + "\n")
        val badRestore = env.restoreSnapshot(snapId)
        assertTrue(badRestore.isFailure)
        assertTrue(sessFile.readText().contains("formatVersion\":1"))
    }
}
