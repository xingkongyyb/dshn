package dev.dshn.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

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
            writeText("""{"version":"0.2.2-rc.5"}""")
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
        assertEquals("0.2.2-rc.5", manifest.dshVersion)
        assertTrue(EnvManager.isNodeVersionCompatible("v24.19.0"))
        assertFalse(EnvManager.isNodeVersionCompatible("v24.8.0"))
        assertTrue(EnvManager.isDshVersionAtTarget("0.2.2-rc.5"))
        assertFalse(EnvManager.isDshVersionAtTarget("0.2.2-rc.1"))
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

        // Also validate compressed zstd generation filename detection (DSH 0.2.2-rc.5 default zstd format)
        snapSess.writeText("""{"id":"s1","formatVersion":4}""" + "\n")
        val futureZstd = File(env.snapshotsDir, "$snapId/dsh-data/sessions/proj/s1/session.v99.jsonl.zstd")
        futureZstd.parentFile!!.mkdirs()
        futureZstd.writeBytes(byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte()))
        val badZstdRestore = env.restoreSnapshot(snapId)
        assertTrue(badZstdRestore.isFailure)
    }

    @Test
    fun upgradesDshTransactionallyWithPreUpgradeSnapshot() {
        val filesDir = tmp.newFolder("files-upgrade")
        val rootfs = setupFakeRootfs(filesDir)
        val pkgJson = File(rootfs, "opt/node24/lib/node_modules/@deepseek-ai/dsh/package.json")
        pkgJson.writeText("""{"version":"0.2.2-rc.1"}""")

        val env = EnvManager(
            filesDir = filesDir,
            assetOpener = { path -> "// asset:$path\n".toByteArray() },
            commandRunner = { _, _ -> ContainerLauncher.Result(0, "PTY_LOAD_OK\nv24.19.0", "") },
            longCommandRunner = { exe, args, _ ->
                assertEquals("/opt/node24/bin/npm", exe)
                assertTrue(args.contains("@deepseek-ai/dsh@0.2.2-rc.5"))
                assertTrue(args.any { it.startsWith("--allow-scripts=") })
                pkgJson.writeText("""{"version":"0.2.2-rc.5"}""")
                ContainerLauncher.Result(0, "added 1 package", "")
            }
        )

        val res = env.upgradeDshToVersion("0.2.2-rc.5")
        assertTrue(res.isSuccess)
        val report = res.getOrThrow()
        assertEquals("0.2.2-rc.1", report.previousVersion)
        assertEquals("0.2.2-rc.5", report.installedVersion)
        assertTrue(report.selfTestPassed)
        assertTrue(!report.snapshotId.isNullOrBlank())
    }

    @Test
    fun installsBundledEnvironmentAtomicallyAndPreservesUserState() {
        val filesDir = tmp.newFolder("files-bundled")
        // Pre-seed an existing ~/.dsh with api_key and session to verify reinstall preserves them
        val oldDsh = File(filesDir, "linux/ubuntu/root/.dsh/sessions").apply { mkdirs() }
        File(filesDir, "linux/ubuntu/root/.dsh/api_key").writeText("sk-preserve-on-reinstall")
        File(oldDsh, "existing.jsonl").writeText("""{"id":"keep"}""" + "\n")

        val ubuntuTgz = buildSimpleTarGz(
            "bin/sh" to ("#!/bin/sh\n" to 0x1ED),
            "etc/resolv.conf" to ("" to 0x1A4)
        )
        val nodeTgz = buildSimpleTarGz(
            "node-v24.22.3-linux-arm64/bin/node" to ("NODE-ELF" to 0x1ED),
            "node-v24.22.3-linux-arm64/include/node/node_version.h" to (
                "#define NODE_MAJOR_VERSION 24\n#define NODE_MINOR_VERSION 22\n#define NODE_PATCH_VERSION 3\n" to 0x1A4
            )
        )
        val dshTgz = buildSimpleTarGz(
            "package.json" to ("""{"name":"@deepseek-ai/dsh","version":"0.2.2-rc.5"}""" to 0x1A4),
            "lib/bin.js" to ("#!/usr/bin/env node\nconsole.log('ok');\n" to 0x1ED)
        )
        val caPem = "-----BEGIN CERTIFICATE-----\nTEST\n-----END CERTIFICATE-----\n".toByteArray()

        val manifestJson = """
            {
              "schemaVersion": 1,
              "bundleVersion": "0.3.0-test",
              "ubuntuVersion": "24.04.5",
              "nodeVersion": "v24.22.3",
              "dshVersion": "0.2.2-rc.5",
              "minFreeSpaceMb": 1,
              "caCertAssetPath": "env/ca-certificates.crt",
              "archives": [
                {
                  "id": "ubuntu",
                  "labelZh": "Ubuntu",
                  "labelEn": "Ubuntu",
                  "assetPath": "env/ubuntu.tar.gz",
                  "targetSubdir": "",
                  "stripComponents": 0,
                  "sizeBytes": ${ubuntuTgz.size},
                  "sha256": "${sha256(ubuntuTgz)}"
                },
                {
                  "id": "node",
                  "labelZh": "Node",
                  "labelEn": "Node",
                  "assetPath": "env/node.tar.gz",
                  "targetSubdir": "opt/node24",
                  "stripComponents": 1,
                  "sizeBytes": ${nodeTgz.size},
                  "sha256": "${sha256(nodeTgz)}"
                },
                {
                  "id": "dsh",
                  "labelZh": "DSH",
                  "labelEn": "DSH",
                  "assetPath": "env/dsh.tar.gz",
                  "targetSubdir": "opt/node24/lib/node_modules/@deepseek-ai/dsh",
                  "stripComponents": 0,
                  "sizeBytes": ${dshTgz.size},
                  "sha256": "${sha256(dshTgz)}"
                }
              ],
              "symlinks": [
                {
                  "linkPath": "opt/node24/bin/dsh",
                  "target": "../lib/node_modules/@deepseek-ai/dsh/lib/bin.js"
                }
              ]
            }
        """.trimIndent().toByteArray(Charsets.UTF_8)

        val assets = mapOf(
            EnvManager.BUNDLED_ENV_MANIFEST_ASSET to manifestJson,
            "env/ubuntu.tar.gz" to ubuntuTgz,
            "env/node.tar.gz" to nodeTgz,
            "env/dsh.tar.gz" to dshTgz,
            "env/ca-certificates.crt" to caPem,
            "dshn/dshn-bridge.patch.yml" to "# patch\n".toByteArray(),
            "dshn/dshn-dns-preload.cjs" to "// dns\n".toByteArray(),
            "dshn/dshn-session-bridge.mjs" to "// bridge\n".toByteArray(),
            "dshn/dshn-mcp-relay.mjs" to "// relay\n".toByteArray(),
            "dshn/dshn-pty-runner.mjs" to "// pty\n".toByteArray()
        )
        val createdLinks = mutableListOf<Pair<String, String>>()
        val fakePosix = object : TarGzExtractor.PosixFsOps {
            override fun symlink(target: String, linkFile: File) {
                createdLinks += linkFile.name to target
                linkFile.parentFile?.mkdirs()
                linkFile.writeText("LINK:$target")
            }
            override fun chmod(file: File, mode: Int) {}
        }

        val env = EnvManager(
            filesDir = filesDir,
            assetOpener = { p -> assets[p] ?: error("missing $p") },
            posixOps = fakePosix
        )

        assertTrue(env.hasBundledEnvironment())
        val progressStages = mutableListOf<Int>()
        val result = env.installBundledEnvironment { p -> progressStages += p.stageIndex }
        assertTrue("Expected success but got: ${result.exceptionOrNull()}", result.isSuccess)

        val m = result.getOrThrow()
        assertTrue(m.rootfsReady)
        assertEquals("0.3.0-test", m.bundledEnvVersion)
        assertEquals("v24.22.3", m.nodeVersion)
        assertEquals("0.2.2-rc.5", m.dshVersion)
        assertTrue(progressStages.contains(1) && progressStages.contains(4))
        assertTrue(createdLinks.any { it.first == "dsh" && it.second == "../lib/node_modules/@deepseek-ai/dsh/lib/bin.js" })
        assertTrue(File(env.rootfs, "etc/ssl/certs/ca-certificates.crt").exists())
        assertTrue(File(env.rootfs, "etc/resolv.conf").readText().contains("223.5.5.5"))
        assertTrue(File(env.rootfs, "root/.bashrc").readText().contains("/opt/node24/bin"))
        assertTrue(File(env.rootfs, "opt/dshn/dshn-session-bridge.mjs").exists())
        // Verify existing user data preserved across reinstall
        assertEquals("sk-preserve-on-reinstall", File(env.rootfs, "root/.dsh/api_key").readText())
        assertTrue(File(env.rootfs, "root/.dsh/sessions/existing.jsonl").exists())
    }

    private fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    private fun buildSimpleTarGz(vararg entries: Pair<String, Pair<String, Int>>): ByteArray {
        val raw = ByteArrayOutputStream()
        GZIPOutputStream(raw).use { gz ->
            for ((path, spec) in entries) {
                val data = spec.first.toByteArray(Charsets.UTF_8)
                val h = ByteArray(512)
                path.toByteArray(Charsets.UTF_8).copyInto(h, 0)
                spec.second.toString(8).padStart(7, '0').toByteArray(Charsets.US_ASCII).copyInto(h, 100)
                "0000000".toByteArray(Charsets.US_ASCII).copyInto(h, 108)
                "0000000".toByteArray(Charsets.US_ASCII).copyInto(h, 116)
                data.size.toString(8).padStart(11, '0').toByteArray(Charsets.US_ASCII).copyInto(h, 124)
                "14521000000".toByteArray(Charsets.US_ASCII).copyInto(h, 136)
                for (i in 148 until 156) h[i] = ' '.code.toByte()
                h[156] = '0'.code.toByte()
                "ustar\u000000".toByteArray(Charsets.US_ASCII).copyInto(h, 257)
                var sum = 0
                for (b in h) sum += (b.toInt() and 0xFF)
                sum.toString(8).padStart(6, '0').toByteArray(Charsets.US_ASCII).copyInto(h, 148)
                h[154] = 0
                h[155] = ' '.code.toByte()
                gz.write(h)
                gz.write(data)
                val rem = data.size % 512
                if (rem != 0) gz.write(ByteArray(512 - rem))
            }
            gz.write(ByteArray(1024))
        }
        return raw.toByteArray()
    }
}
