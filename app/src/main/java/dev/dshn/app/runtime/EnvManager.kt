package dev.dshn.app.runtime

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

@Serializable
data class EnvManifest(
    val schemaVersion: Int = 1,
    val assetBundleHash: String = "",
    val dshVersion: String = "",
    val nodeVersion: String = "",
    val rootfsReady: Boolean = false,
    val lastSyncedEpochMs: Long = 0L,
    val lastSelfTestEpochMs: Long = 0L,
    val lastSelfTestPassed: Boolean = false,
    val pendingSnapshotRestore: String? = null,
    val pendingBackupDir: String? = null
)

@Serializable
data class EnvSnapshotInfo(
    val id: String,
    val createdAtMs: Long,
    val dshVersion: String,
    val sessionCount: Int,
    val path: String
)

@Serializable
data class SelfTestCheck(
    val name: String,
    val passed: Boolean,
    val detail: String
)

@Serializable
data class SelfTestReport(
    val timestampMs: Long,
    val allPassed: Boolean,
    val checks: List<SelfTestCheck>
)

/**
 * Manages Ubuntu/DSH environment lifecycle, atomic asset staging (`opt/dshn/`),
 * transactional `~/.dsh` snapshots & rollback with format checks, and container self-tests.
 */
class EnvManager(
    filesDir: File,
    private val assetOpener: ((String) -> ByteArray)? = null,
    private val commandRunner: ((String, Array<String>) -> ContainerLauncher.Result)? = null
) {
    constructor(context: Context, launcher: ContainerLauncher = ContainerLauncher(context)) : this(
        filesDir = context.filesDir,
        assetOpener = { assetPath -> context.assets.open(assetPath).use { it.readBytes() } },
        commandRunner = { exe, args -> launcher.runCapture(exe, *args, timeoutSec = 25) }
    )

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val rootFilesDir = File(filesDir.absolutePath.replaceFirst("/data/user/0/", "/data/data/"))
    val rootfs: File = File(rootFilesDir, "linux/ubuntu")
    val envDir: File = File(rootFilesDir, "env").apply { mkdirs() }
    val snapshotsDir: File = File(envDir, "snapshots").apply { mkdirs() }
    private val manifestFile = File(envDir, "env-manifest.json")
    private val dshStateDir = File(rootfs, "root/.dsh")

    init {
        recoverInterruptedTransaction()
    }

    fun loadManifest(): EnvManifest {
        if (!manifestFile.exists()) return EnvManifest(rootfsReady = isRootfsReady())
        return runCatching {
            json.decodeFromString<EnvManifest>(manifestFile.readText(Charsets.UTF_8))
        }.getOrDefault(EnvManifest(rootfsReady = isRootfsReady()))
    }

    fun isRootfsReady(): Boolean = File(rootfs, "bin/sh").exists() && File(rootfs, "opt/node24/bin/node").exists()

    fun ensureRuntimeAssets(): EnvManifest {
        val current = loadManifest()
        if (!isRootfsReady()) {
            val updated = current.copy(rootfsReady = false)
            saveManifest(updated)
            return updated
        }

        val optDshn = File(rootfs, "opt/dshn").apply { mkdirs() }
        val opener = assetOpener
        val digest = MessageDigest.getInstance("SHA-256")
        if (opener != null) {
            for ((assetName, executable) in BUNDLED_ASSETS) {
                val bytes = opener("dshn/$assetName")
                digest.update(assetName.toByteArray(Charsets.UTF_8))
                digest.update(bytes)
                val target = File(optDshn, assetName)
                if (!target.exists() || !target.readBytes().contentEquals(bytes)) {
                    writeFsyncAtomic(target, bytes)
                }
                if (executable) {
                    target.setExecutable(true, false)
                    target.setReadable(true, false)
                } else {
                    target.setExecutable(false, false)
                    target.setReadable(true, false)
                }
            }
        }
        ensureResolvConf()
        val bundleHash = digest.digest().joinToString("") { "%02x".format(it) }
        val dshPkgVer = readDshPackageVersion()
        val nodePkgVer = readNodePackageVersion()
        val updated = current.copy(
            assetBundleHash = bundleHash,
            dshVersion = dshPkgVer.ifEmpty { current.dshVersion },
            nodeVersion = nodePkgVer.ifEmpty { current.nodeVersion },
            rootfsReady = true,
            lastSyncedEpochMs = System.currentTimeMillis()
        )
        saveManifest(updated)
        return updated
    }

    fun readNodePackageVersion(): String {
        val nodeLink = File(rootfs, "opt/node24")
        if (!nodeLink.exists()) return ""
        val canonicalName = runCatching { nodeLink.canonicalFile.name }.getOrDefault("")
        Regex("""v\d+\.\d+\.\d+""").find(canonicalName)?.value?.let { return it }
        val header = File(rootfs, "opt/node24/include/node/node_version.h")
        if (header.exists()) {
            val text = runCatching { header.readText(Charsets.UTF_8) }.getOrDefault("")
            val maj = Regex("""NODE_MAJOR_VERSION\s+(\d+)""").find(text)?.groupValues?.get(1)
            val min = Regex("""NODE_MINOR_VERSION\s+(\d+)""").find(text)?.groupValues?.get(1)
            val pat = Regex("""NODE_PATCH_VERSION\s+(\d+)""").find(text)?.groupValues?.get(1)
            if (maj != null && min != null && pat != null) return "v$maj.$min.$pat"
        }
        return ""
    }

    fun readDshPackageVersion(): String {
        val pkg = File(rootfs, "opt/node24/lib/node_modules/@deepseek-ai/dsh/package.json")
        if (!pkg.exists()) return ""
        return runCatching {
            json.parseToJsonElement(pkg.readText(Charsets.UTF_8)).jsonObject["version"]?.jsonPrimitive?.content.orEmpty()
        }.getOrDefault("")
    }

    fun createSnapshot(label: String = "manual"): Result<EnvSnapshotInfo> = runCatching {
        recoverInterruptedTransaction()
        if (!dshStateDir.exists()) dshStateDir.mkdirs()

        val ts = System.currentTimeMillis()
        val cleanLabel = label.replace(Regex("""[^A-Za-z0-9_-]"""), "_").take(24).ifEmpty { "snap" }
        val id = "${ts}_$cleanLabel"
        val staging = File(snapshotsDir, "$id.staging")
        val finalDir = File(snapshotsDir, id)
        if (staging.exists()) staging.deleteRecursively()
        staging.mkdirs()

        val dataCopy = File(staging, "dsh-data")
        copyDirectoryExcludingSecrets(dshStateDir, dataCopy)

        val sessionsDir = File(dataCopy, "sessions")
        val sessionCount = sessionsDir.listFiles()?.count { !it.name.startsWith(".") } ?: 0
        val meta = EnvSnapshotInfo(
            id = id,
            createdAtMs = ts,
            dshVersion = readDshPackageVersion(),
            sessionCount = sessionCount,
            path = finalDir.absolutePath
        )
        writeFsyncAtomic(File(staging, "snapshot.meta.json"), json.encodeToString(meta).toByteArray(Charsets.UTF_8))
        check(staging.renameTo(finalDir)) { "Failed to commit snapshot directory $id" }
        meta
    }

    fun listSnapshots(): List<EnvSnapshotInfo> {
        val dirs = snapshotsDir.listFiles() ?: return emptyList()
        return dirs.mapNotNull { dir ->
            if (!dir.isDirectory || dir.name.endsWith(".staging")) return@mapNotNull null
            val metaFile = File(dir, "snapshot.meta.json")
            if (!metaFile.exists()) return@mapNotNull null
            runCatching {
                json.decodeFromString<EnvSnapshotInfo>(metaFile.readText(Charsets.UTF_8)).copy(path = dir.absolutePath)
            }.getOrNull()
        }.sortedByDescending { it.createdAtMs }
    }

    /**
     * Transactional snapshot restore with pre-swap compatibility verification (`session.vN` check)
     * and two-phase rename + crash recovery journal in env-manifest.json.
     */
    fun restoreSnapshot(snapshotId: String): Result<EnvSnapshotInfo> = runCatching {
        recoverInterruptedTransaction()
        val snapDir = File(snapshotsDir, snapshotId)
        val snapData = File(snapDir, "dsh-data")
        require(snapData.exists() && snapData.isDirectory) { "Snapshot not found: $snapshotId" }

        val meta = json.decodeFromString<EnvSnapshotInfo>(File(snapDir, "snapshot.meta.json").readText(Charsets.UTF_8))
        validateSnapshotCompatibility(snapData)

        // Preserve existing api_key on live root/.dsh during restore
        val apiKeyFile = File(dshStateDir, "api_key")
        val preservedApiKey = if (apiKeyFile.exists()) apiKeyFile.readBytes() else null

        val stagedRestore = File(rootfs, "root/.dsh.restore-${System.currentTimeMillis()}")
        val backupDir = File(rootfs, "root/.dsh.bak-${System.currentTimeMillis()}")
        if (stagedRestore.exists()) stagedRestore.deleteRecursively()
        copyDirectoryExcludingSecrets(snapData, stagedRestore)
        if (preservedApiKey != null) {
            val restoredKey = File(stagedRestore, "api_key")
            writeFsyncAtomic(restoredKey, preservedApiKey)
            restoredKey.setReadable(false, false); restoredKey.setReadable(true, true)
            restoredKey.setWritable(false, false); restoredKey.setWritable(true, true)
        }

        val currentManifest = loadManifest()
        saveManifest(
            currentManifest.copy(
                pendingSnapshotRestore = stagedRestore.absolutePath,
                pendingBackupDir = backupDir.absolutePath
            )
        )

        if (dshStateDir.exists()) {
            check(dshStateDir.renameTo(backupDir)) { "Failed to move live .dsh to backup" }
        }
        if (!stagedRestore.renameTo(dshStateDir)) {
            if (backupDir.exists()) backupDir.renameTo(dshStateDir)
            saveManifest(currentManifest.copy(pendingSnapshotRestore = null, pendingBackupDir = null))
            error("Failed to swap restored .dsh into place; rolled back")
        }
        backupDir.deleteRecursively()
        saveManifest(currentManifest.copy(pendingSnapshotRestore = null, pendingBackupDir = null))
        meta
    }

    /**
     * Rejects snapshots containing unsupported future/incompatible session schema formats
     * without altering any files.
     */
    fun validateSnapshotCompatibility(dataDir: File) {
        val sessionsDir = File(dataDir, "sessions")
        if (!sessionsDir.exists() || !sessionsDir.isDirectory) return
        sessionsDir.walkTopDown().filter { it.isFile && (it.name.endsWith(".json") || it.name.endsWith(".jsonl")) }.forEach { file ->
            val firstLine = file.bufferedReader(Charsets.UTF_8).use { it.readLine() }?.trim() ?: return@forEach
            if (firstLine.isEmpty()) return@forEach
            val obj = runCatching { json.parseToJsonElement(firstLine).jsonObject }.getOrNull()
                ?: throw IllegalStateException("Corrupt session header in snapshot: ${file.name}")
            val formatVersion = obj["formatVersion"]?.jsonPrimitive?.content?.toIntOrNull()
                ?: obj["version"]?.jsonPrimitive?.content?.toIntOrNull()
            if (formatVersion != null && formatVersion > MAX_SUPPORTED_SESSION_FORMAT_VERSION) {
                throw IllegalStateException(
                    "Incompatible session format version v$formatVersion in ${file.name} (max supported v$MAX_SUPPORTED_SESSION_FORMAT_VERSION)"
                )
            }
        }
    }

    fun recoverInterruptedTransaction() {
        val manifest = loadManifest()
        val stagedPath = manifest.pendingSnapshotRestore
        val backupPath = manifest.pendingBackupDir
        if (stagedPath == null && backupPath == null) return

        val backupDir = backupPath?.let { File(it) }
        val stagedDir = stagedPath?.let { File(it) }
        if (!dshStateDir.exists() && backupDir != null && backupDir.exists()) {
            backupDir.renameTo(dshStateDir)
        }
        if (stagedDir != null && stagedDir.exists()) stagedDir.deleteRecursively()
        if (backupDir != null && backupDir.exists() && dshStateDir.exists()) backupDir.deleteRecursively()
        saveManifest(manifest.copy(pendingSnapshotRestore = null, pendingBackupDir = null))
    }

    fun runSelfTest(): SelfTestReport {
        val runner = commandRunner ?: return SelfTestReport(
            timestampMs = System.currentTimeMillis(),
            allPassed = false,
            checks = listOf(SelfTestCheck("runner", false, "No container runner configured"))
        )
        val checks = mutableListOf<SelfTestCheck>()

        checks += SelfTestCheck(
            name = "rootfs_layout",
            passed = isRootfsReady() && File(rootfs, "opt/dshn/dshn-session-bridge.mjs").exists(),
            detail = if (isRootfsReady()) "ubuntu rootfs + opt/dshn ready" else "rootfs or opt/dshn missing"
        )

        val nodeRes = runner("/opt/node24/bin/node", arrayOf("-v"))
        val nodeVer = nodeRes.stdout.lines().firstOrNull { it.startsWith("v") }?.trim().orEmpty()
        checks += SelfTestCheck(
            name = "node_runtime",
            passed = nodeRes.exitCode == 0 && nodeVer.isNotEmpty(),
            detail = if (nodeVer.isNotEmpty()) nodeVer else "exit=${nodeRes.exitCode} ${nodeRes.stderr.take(120)}"
        )

        val dshRes = runner("/opt/node24/bin/node", arrayOf(
            "--check", "/opt/dshn/dshn-session-bridge.mjs"
        ))
        val dshPkgVer = readDshPackageVersion()
        checks += SelfTestCheck(
            name = "dsh_and_bridge_syntax",
            passed = dshRes.exitCode == 0 && dshPkgVer.isNotEmpty(),
            detail = if (dshRes.exitCode == 0) "dsh=$dshPkgVer, bridge syntax OK" else "exit=${dshRes.exitCode} ${dshRes.stderr.take(120)}"
        )

        val ptyRes = runner("/opt/node24/bin/node", arrayOf(
            "-e",
            "import('/opt/node24/lib/node_modules/@deepseek-ai/dsh/node_modules/node-pty/lib/index.js').then(m=>{if(typeof (m.default||m).spawn!=='function')process.exit(2);console.log('PTY_LOAD_OK')})"
        ))
        checks += SelfTestCheck(
            name = "node_pty",
            passed = ptyRes.exitCode == 0 && ptyRes.stdout.contains("PTY_LOAD_OK"),
            detail = if (ptyRes.stdout.contains("PTY_LOAD_OK")) "node-pty spawn available" else "exit=${ptyRes.exitCode} ${ptyRes.stderr.take(120)}"
        )

        val dnsRes = runner("/opt/node24/bin/node", arrayOf(
            "-e",
            "import('node:dns/promises').then(d=>d.lookup('api.deepseek.com')).then(r=>{console.log(r.address)}).catch(e=>{console.error(e.message);process.exit(1)})"
        ))
        checks += SelfTestCheck(
            name = "dns_deepseek_api",
            passed = dnsRes.exitCode == 0,
            detail = if (dnsRes.exitCode == 0) "resolved ${dnsRes.stdout.trim()}" else "exit=${dnsRes.exitCode} ${dnsRes.stderr.take(120)}"
        )

        val allOk = checks.all { it.passed }
        val updated = loadManifest().copy(
            nodeVersion = nodeVer.ifEmpty { loadManifest().nodeVersion },
            dshVersion = dshPkgVer.ifEmpty { loadManifest().dshVersion },
            lastSelfTestEpochMs = System.currentTimeMillis(),
            lastSelfTestPassed = allOk
        )
        saveManifest(updated)
        return SelfTestReport(
            timestampMs = updated.lastSelfTestEpochMs,
            allPassed = allOk,
            checks = checks
        )
    }

    private fun ensureResolvConf() {
        val resolv = File(rootfs, "etc/resolv.conf")
        val currentText = runCatching { resolv.readText(Charsets.UTF_8) }.getOrDefault("")
        val needsRepair = !resolv.exists() ||
            currentText.isBlank() ||
            (currentText.contains("127.0.0.53") && !currentText.contains("223.5.5.5"))
        if (needsRepair) {
            resolv.parentFile?.mkdirs()
            writeFsyncAtomic(resolv, "nameserver 223.5.5.5\nnameserver 8.8.8.8\n".toByteArray(Charsets.UTF_8))
        }
    }

    private fun copyDirectoryExcludingSecrets(src: File, dst: File) {
        if (!src.exists()) return
        dst.mkdirs()
        val children = src.listFiles() ?: return
        for (child in children) {
            if (child.name == "api_key" || child.name.endsWith(".lock")) continue
            val target = File(dst, child.name)
            if (child.isDirectory) {
                copyDirectoryExcludingSecrets(child, target)
            } else {
                child.inputStream().use { input ->
                    FileOutputStream(target).use { out ->
                        input.copyTo(out)
                        out.fd.sync()
                    }
                }
            }
        }
    }

    private fun writeFsyncAtomic(target: File, data: ByteArray) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, ".${target.name}.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(data)
            out.flush()
            out.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            target.writeBytes(data)
            tmp.delete()
        }
    }

    private fun saveManifest(manifest: EnvManifest) {
        writeFsyncAtomic(manifestFile, json.encodeToString(manifest).toByteArray(Charsets.UTF_8))
    }

    companion object {
        const val MAX_SUPPORTED_SESSION_FORMAT_VERSION = 10

        private val BUNDLED_ASSETS = listOf(
            "dshn-bridge.patch.yml" to false,
            "dshn-session-bridge.mjs" to true,
            "dshn-mcp-relay.mjs" to true,
            "dshn-pty-runner.mjs" to true
        )
    }
}
