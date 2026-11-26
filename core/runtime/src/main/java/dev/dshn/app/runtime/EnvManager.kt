package dev.dshn.app.runtime

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

@Serializable
data class EnvManifest(
    val schemaVersion: Int = 1,
    val assetBundleHash: String = "",
    val bundledEnvVersion: String = "",
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
data class BundledArchiveSpec(
    val id: String,
    val labelZh: String,
    val labelEn: String,
    val assetPath: String,
    val targetSubdir: String = "",
    val stripComponents: Int = 0,
    val sizeBytes: Long = 0L,
    val sha256: String = ""
)

@Serializable
data class BundledSymlinkSpec(
    val linkPath: String,
    val target: String
)

@Serializable
data class BundledEnvManifest(
    val schemaVersion: Int = 1,
    val bundleVersion: String = "",
    val ubuntuVersion: String = "",
    val nodeVersion: String = "",
    val dshVersion: String = "",
    val minFreeSpaceMb: Long = 900L,
    val caCertAssetPath: String = "",
    val archives: List<BundledArchiveSpec> = emptyList(),
    val symlinks: List<BundledSymlinkSpec> = emptyList()
)

data class EnvInstallProgress(
    val inProgress: Boolean = false,
    val stageIndex: Int = 0,
    val stageCount: Int = 0,
    val stageLabelZh: String = "",
    val stageLabelEn: String = "",
    val bytesDone: Long = 0L,
    val totalBytes: Long = 0L,
    val entriesExtracted: Int = 0,
    val error: String? = null
) {
    val fraction: Float
        get() = if (totalBytes > 0L) (bytesDone.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
}

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

@Serializable
data class DshUpgradeReport(
    val targetVersion: String,
    val previousVersion: String,
    val installedVersion: String,
    val snapshotId: String?,
    val selfTestPassed: Boolean,
    val logTail: String
)

/**
 * Manages Ubuntu/DSH environment lifecycle, atomic asset staging (`opt/dshn/`),
 * transactional `~/.dsh` snapshots & rollback with format checks, DSH upgrades, and container self-tests.
 */
class EnvManager(
    filesDir: File,
    private val assetOpener: ((String) -> ByteArray)? = null,
    private val commandRunner: ((String, Array<String>) -> ContainerLauncher.Result)? = null,
    private val longCommandRunner: ((String, Array<String>, Long) -> ContainerLauncher.Result)? = null,
    private val assetStreamOpener: ((String) -> InputStream)? = null,
    private val posixOps: TarGzExtractor.PosixFsOps = TarGzExtractor.DefaultPosixFsOps,
    private val assetApkSliceOpener: ((String) -> Triple<String, Long, Long>?)? = null,
    private val nativeLibDir: File? = null
) {
    constructor(context: Context, launcher: ContainerLauncher = ContainerLauncher(context)) : this(
        filesDir = context.filesDir,
        assetOpener = { assetPath -> context.assets.open(assetPath).use { it.readBytes() } },
        commandRunner = { exe, args -> launcher.runCapture(exe, *args, timeoutSec = 25) },
        longCommandRunner = { exe, args, timeoutSec -> launcher.runCapture(exe, *args, timeoutSec = timeoutSec) },
        assetStreamOpener = { assetPath -> context.assets.open(assetPath) },
        assetApkSliceOpener = { assetPath ->
            try {
                val apkPath = context.applicationInfo.sourceDir
                context.assets.openFd(assetPath).use { afd ->
                    Triple(apkPath, afd.startOffset, afd.declaredLength)
                }
            } catch (_: Throwable) {
                null
            }
        },
        nativeLibDir = launcher.nativeLibDir
    )

    private fun openAssetStream(assetPath: String): InputStream? {
        assetStreamOpener?.let { opener ->
            return runCatching { opener(assetPath) }.getOrNull()
        }
        assetOpener?.let { opener ->
            return runCatching { ByteArrayInputStream(opener(assetPath)) }.getOrNull()
        }
        return null
    }

    fun loadBundledManifest(): BundledEnvManifest? {
        val stream = openAssetStream(BUNDLED_ENV_MANIFEST_ASSET) ?: return null
        return runCatching {
            stream.use { s ->
                json.decodeFromString<BundledEnvManifest>(s.readBytes().toString(Charsets.UTF_8))
            }
        }.getOrNull()?.takeIf { it.archives.isNotEmpty() }
    }

    fun hasBundledEnvironment(): Boolean = loadBundledManifest() != null

    /**
     * Transactionally extracts the APK-bundled Ubuntu + Node.js + DSH environment into
     * `files/linux/ubuntu.staging`, verifies SHA-256 of every archive, preserves any existing
     * `root/.dsh` data (`api_key` and sessions), and atomically swaps it into `files/linux/ubuntu`.
     */
    fun installBundledEnvironment(
        onProgress: (EnvInstallProgress) -> Unit = {}
    ): Result<EnvManifest> = runCatching {
        val bundle = loadBundledManifest()
            ?: error("Bundled environment manifest ($BUNDLED_ENV_MANIFEST_ASSET) not found in APK")

        val linuxParent = rootfs.parentFile ?: File(rootFilesDir, "linux")
        linuxParent.mkdirs()

        // Clean stale staging / old backup dirs before checking free space
        val staging = File(linuxParent, "ubuntu.staging")
        if (staging.exists()) staging.deleteRecursively()
        linuxParent.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("ubuntu.old-") }
            ?.forEach { it.deleteRecursively() }

        if (bundle.minFreeSpaceMb > 0L) {
            val availMb = linuxParent.usableSpace / (1024L * 1024L)
            if (availMb in 1 until bundle.minFreeSpaceMb) {
                error("Insufficient storage: ${availMb} MB available, at least ${bundle.minFreeSpaceMb} MB required")
            }
        }

        staging.mkdirs()
        val totalBytes = bundle.archives.sumOf { it.sizeBytes }.coerceAtLeast(1L)
        val totalStages = bundle.archives.size + 1
        var completedArchiveBytes = 0L
        var totalEntries = 0

        try {
            bundle.archives.forEachIndexed { idx, archive ->
                onProgress(
                    EnvInstallProgress(
                        inProgress = true,
                        stageIndex = idx + 1,
                        stageCount = totalStages,
                        stageLabelZh = archive.labelZh,
                        stageLabelEn = archive.labelEn,
                        bytesDone = completedArchiveBytes,
                        totalBytes = totalBytes,
                        entriesExtracted = totalEntries
                    )
                )

                val targetDir = if (archive.targetSubdir.isBlank()) staging else File(staging, archive.targetSubdir)
                targetDir.mkdirs()

                val progressReporter: (Long, Int) -> Unit = { archiveBytes, archiveEntries ->
                    val curDone = (completedArchiveBytes + archiveBytes).coerceAtMost(totalBytes)
                    onProgress(
                        EnvInstallProgress(
                            inProgress = true,
                            stageIndex = idx + 1,
                            stageCount = totalStages,
                            stageLabelZh = archive.labelZh,
                            stageLabelEn = archive.labelEn,
                            bytesDone = curDone,
                            totalBytes = totalBytes,
                            entriesExtracted = totalEntries + archiveEntries
                        )
                    )
                }

                val isZstd = archive.assetPath.endsWith(".zst", ignoreCase = true) ||
                    archive.assetPath.endsWith(".zst.bin", ignoreCase = true)
                val apkSlice = if (DshnNativeCore.isAvailable) assetApkSliceOpener?.invoke(archive.assetPath) else null
                val stats = if (apkSlice != null) {
                    val (apkPath, startOffset, declaredLen) = apkSlice
                    val nativeJson = DshnNativeCore.extractTarFile(
                        archivePath = apkPath,
                        offsetBytes = startOffset,
                        lengthBytes = declaredLen,
                        destDir = targetDir.absolutePath,
                        stripComponents = archive.stripComponents,
                        expectedSha256 = archive.sha256.ifBlank { null },
                        isZstd = isZstd,
                        callback = object : DshnNativeCore.ExtractProgressCallback {
                            override fun onProgress(compressedBytesRead: Long, entriesExtracted: Int) {
                                progressReporter(compressedBytesRead, entriesExtracted)
                            }
                        }
                    )
                    val obj = json.parseToJsonElement(nativeJson).jsonObject
                    TarGzExtractor.ExtractStats(
                        entriesExtracted = obj["entries"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                        filesExtracted = obj["files"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                        symlinksCreated = obj["symlinks"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                        hardlinksCopied = obj["hardlinks"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                        compressedBytesRead = obj["bytes"]?.jsonPrimitive?.content?.toLongOrNull() ?: declaredLen,
                        sha256Hex = obj["sha256"]?.jsonPrimitive?.content.orEmpty()
                    )
                } else {
                    val stream = openAssetStream(archive.assetPath)
                        ?: error("Missing bundled archive asset: ${archive.assetPath}")
                    TarGzExtractor.extract(
                        gzipStream = stream,
                        destDir = targetDir,
                        stripComponents = archive.stripComponents,
                        expectedSha256 = archive.sha256.ifBlank { null },
                        posixOps = posixOps,
                        onProgress = progressReporter
                    )
                }
                completedArchiveBytes += if (archive.sizeBytes > 0L) archive.sizeBytes else stats.compressedBytesRead
                totalEntries += stats.entriesExtracted
            }

            // Finalize stage: symlinks, CA certs, DNS, shell profile, mountpoints, preserve ~/.dsh
            onProgress(
                EnvInstallProgress(
                    inProgress = true,
                    stageIndex = totalStages,
                    stageCount = totalStages,
                    stageLabelZh = "校验并配置容器环境",
                    stageLabelEn = "Verifying and configuring container",
                    bytesDone = totalBytes,
                    totalBytes = totalBytes,
                    entriesExtracted = totalEntries
                )
            )

            for (link in bundle.symlinks) {
                val linkFile = File(staging, link.linkPath)
                linkFile.parentFile?.mkdirs()
                posixOps.symlink(link.target, linkFile)
            }

            val dshBinEntry = File(staging, "opt/node24/lib/node_modules/@deepseek-ai/dsh/lib/bin.js")
            if (dshBinEntry.exists()) {
                posixOps.chmod(dshBinEntry, 0x1ED) // 0755
            }

            // Install Mozilla CA bundle if bundled and not already present
            if (bundle.caCertAssetPath.isNotBlank()) {
                val caTarget = File(staging, "etc/ssl/certs/ca-certificates.crt")
                if (!caTarget.exists()) {
                    openAssetStream(bundle.caCertAssetPath)?.use { caIn ->
                        writeFsyncAtomic(caTarget, caIn.readBytes())
                        posixOps.chmod(caTarget, 0x1A4) // 0644
                    }
                }
            }

            // Standard mountpoints & runtime directories
            listOf("run/dshn-bridge", "mnt/workspace", ".l2s", "root/.dsh", "tmp").forEach { rel ->
                val d = File(staging, rel).apply { mkdirs() }
                posixOps.chmod(d, if (rel == "tmp") 0x1FF else 0x1ED)
            }

            // Configure container DNS and default PATH / SSL_CERT_FILE / NODE_OPTIONS for interactive shells
            val stagedResolv = File(staging, "etc/resolv.conf")
            writeFsyncAtomic(stagedResolv, "nameserver 223.5.5.5\nnameserver 119.29.29.29\nnameserver 8.8.8.8\n".toByteArray(Charsets.UTF_8))
            posixOps.chmod(stagedResolv, 0x1A4) // 0644
            ensureContainerProfile(File(staging, "root/.bashrc"))
            ensureContainerProfile(File(staging, "root/.profile"))

            // Verify critical runtime binaries exist inside staging before committing
            val hasShell = File(staging, "bin/sh").exists() || File(staging, "usr/bin/sh").exists()
            check(hasShell) { "Staged rootfs missing /bin/sh" }
            check(File(staging, "opt/node24/bin/node").exists()) { "Staged rootfs missing /opt/node24/bin/node" }
            check(File(staging, "opt/node24/lib/node_modules/@deepseek-ai/dsh/package.json").exists()) {
                "Staged rootfs missing @deepseek-ai/dsh"
            }

            // Preserve existing user ~/.dsh state (sessions + api_key) if reinstalling over an existing rootfs
            if (dshStateDir.exists() && dshStateDir.isDirectory) {
                val stagedDsh = File(staging, "root/.dsh")
                copyDirectoryPreservingAll(dshStateDir, stagedDsh)
                val stagedKey = File(stagedDsh, "api_key")
                if (stagedKey.exists()) {
                    posixOps.chmod(stagedKey, 0x180) // 0600
                }
            }

            // Atomic directory swap with rollback if moving staging into place fails
            val backupOld = File(linuxParent, "ubuntu.old-${System.currentTimeMillis()}")
            val hadExisting = rootfs.exists()
            if (hadExisting) {
                check(rootfs.renameTo(backupOld)) { "Failed to move existing rootfs aside for upgrade" }
            }
            if (!staging.renameTo(rootfs)) {
                if (hadExisting && backupOld.exists()) backupOld.renameTo(rootfs)
                error("Failed to commit staged rootfs into ${rootfs.absolutePath}")
            }
            if (backupOld.exists()) backupOld.deleteRecursively()

            val synced = ensureRuntimeAssets().copy(
                bundledEnvVersion = bundle.bundleVersion,
                nodeVersion = readNodePackageVersion().ifEmpty { bundle.nodeVersion },
                dshVersion = readDshPackageVersion().ifEmpty { bundle.dshVersion }
            )
            saveManifest(synced)
            synced
        } catch (t: Throwable) {
            staging.deleteRecursively()
            throw t
        }
    }

    private fun ensureContainerProfile(profileFile: File) {
        val marker = "# DSHN bundled runtime PATH"
        val existing = if (profileFile.exists()) runCatching { profileFile.readText(Charsets.UTF_8) }.getOrDefault("") else ""
        if (existing.contains(marker)) return
        val block = buildString {
            if (existing.isNotEmpty() && !existing.endsWith("\n")) append("\n")
            append("\n$marker\n")
            append("export PATH=\"/opt/node24/bin:\$PATH\"\n")
            append("if [ -f /etc/ssl/certs/ca-certificates.crt ]; then export SSL_CERT_FILE=\"/etc/ssl/certs/ca-certificates.crt\"; fi\n")
            append("if [ -f /opt/dshn/dshn-dns-preload.cjs ]; then export NODE_OPTIONS=\"--require=/opt/dshn/dshn-dns-preload.cjs \${NODE_OPTIONS:-}\"; fi\n")
        }
        writeFsyncAtomic(profileFile, (existing + block).toByteArray(Charsets.UTF_8))
    }

    private fun copyDirectoryPreservingAll(src: File, dst: File) {
        if (!src.exists()) return
        dst.mkdirs()
        val children = src.listFiles() ?: return
        for (child in children) {
            if (child.name.endsWith(".lock")) continue
            val target = File(dst, child.name)
            if (child.isDirectory) {
                copyDirectoryPreservingAll(child, target)
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

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val rootFilesDir = File(filesDir.absolutePath.replaceFirst("/data/user/0/", "/data/data/"))
    val rootfs: File = File(rootFilesDir, "linux/ubuntu")
    val envDir: File = File(rootFilesDir, "env")
    val snapshotsDir: File = File(envDir, "snapshots")
    private val manifestFile = File(envDir, "env-manifest.json")
    private val dshStateDir = File(rootfs, "root/.dsh")

    @Volatile
    private var envInitialized: Boolean = false

    private fun ensureEnvInitialized() {
        if (envInitialized) return
        synchronized(this) {
            if (envInitialized) return
            envDir.mkdirs()
            snapshotsDir.mkdirs()
            envInitialized = true
            recoverInterruptedTransaction()
        }
    }

    fun loadManifest(): EnvManifest {
        ensureEnvInitialized()
        if (!manifestFile.exists()) return EnvManifest(rootfsReady = isRootfsReady())
        return runCatching {
            json.decodeFromString<EnvManifest>(manifestFile.readText(Charsets.UTF_8))
        }.getOrDefault(EnvManifest(rootfsReady = isRootfsReady()))
    }

    fun isRootfsReady(): Boolean = File(rootfs, "bin/sh").exists() && File(rootfs, "opt/node24/bin/node").exists()

    fun ensureRuntimeAssets(): EnvManifest {
        ensureEnvInitialized()
        val current = loadManifest()
        if (!isRootfsReady()) {
            val updated = current.copy(rootfsReady = false)
            if (current.rootfsReady || !manifestFile.exists()) saveManifest(updated)
            return updated
        }

        val optDshn = File(rootfs, "opt/dshn").apply { mkdirs() }
        val opener = assetOpener
        val digest = MessageDigest.getInstance("SHA-256")
        var anyFileChanged = false
        if (opener != null) {
            for ((assetName, executable) in BUNDLED_ASSETS) {
                val bytes = opener("dshn/$assetName")
                digest.update(assetName.toByteArray(Charsets.UTF_8))
                digest.update(bytes)
                val target = File(optDshn, assetName)
                if (!target.exists() || target.length() != bytes.size.toLong() || !target.readBytes().contentEquals(bytes)) {
                    writeFsyncAtomic(target, bytes)
                    anyFileChanged = true
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
        // Stage static Rust MCP UDS relay binary inside container opt/dshn/ if bundled in jniLibs
        val nativeRelaySrc = nativeLibDir?.let { File(it, "libdshn_mcp_relay.so") }
        if (nativeRelaySrc != null && nativeRelaySrc.isFile) {
            val nativeRelayDst = File(optDshn, "dshn-mcp-relay")
            val srcBytes = nativeRelaySrc.readBytes()
            if (!nativeRelayDst.exists() || nativeRelayDst.length() != srcBytes.size.toLong() || !nativeRelayDst.readBytes().contentEquals(srcBytes)) {
                writeFsyncAtomic(nativeRelayDst, srcBytes)
                anyFileChanged = true
            }
            nativeRelayDst.setExecutable(true, false)
            nativeRelayDst.setReadable(true, false)
            digest.update("dshn-mcp-relay".toByteArray(Charsets.UTF_8))
            digest.update(srcBytes)
        }

        ensureResolvConf()
        val migratedCreds = migrateLegacyCredentialsYaml(rootfs)
        val patchedDsh = applyDshProfileResolutionOptimization(rootfs)
        val bundleHash = digest.digest().joinToString("") { "%02x".format(it) }
        val dshPkgVer = readDshPackageVersion()
        val nodePkgVer = readNodePackageVersion()
        val nextDshVer = dshPkgVer.ifEmpty { current.dshVersion }
        val nextNodeVer = nodePkgVer.ifEmpty { current.nodeVersion }
        val unchanged = current.rootfsReady &&
            current.assetBundleHash == bundleHash &&
            current.dshVersion == nextDshVer &&
            current.nodeVersion == nextNodeVer &&
            !anyFileChanged &&
            !patchedDsh &&
            !migratedCreds &&
            manifestFile.exists()
        if (unchanged) {
            return current
        }
        val updated = current.copy(
            assetBundleHash = bundleHash,
            dshVersion = nextDshVer,
            nodeVersion = nextNodeVer,
            rootfsReady = true,
            lastSyncedEpochMs = System.currentTimeMillis()
        )
        saveManifest(updated)
        return updated
    }

    /**
     * Migrates pre-release flat `/root/.dsh/.credentials.yaml` to DSH 0.2.2-rc.5+ `version: 1` + `refs:`
     * schema so `credentials` / `authorization` / `deepseek-account` services activate cleanly without warnings.
     */
    internal fun migrateLegacyCredentialsYaml(targetRootfs: File): Boolean {
        val credFile = File(targetRootfs, "root/.dsh/.credentials.yaml")
        if (!credFile.isFile) return false
        val raw = runCatching { credFile.readText(Charsets.UTF_8) }.getOrNull() ?: return false
        val migrated = migrateLegacyCredentialsYamlText(raw) ?: return false
        writeFsyncAtomic(credFile, migrated.toByteArray(Charsets.UTF_8))
        credFile.setReadable(false, false); credFile.setReadable(true, true)
        credFile.setWritable(false, false); credFile.setWritable(true, true)
        return true
    }

    /**
     * Applies the MIT-licensed DSH Sugar `profile-resolution` startup optimization to
     * `@deepseek-ai/dsh-app-boot` (`index.js` and `profile-resolution-bootstrap.js`).
     * Eliminates ~46% of redundant `lstat`/`realpathSync` calls across installed packages on boot.
     * Safe and idempotent: leaves files untouched if already patched or if upstream layout differs.
     */
    internal fun applyDshProfileResolutionOptimization(targetRootfs: File): Boolean {
        val candidates = listOf(
            "opt/node24/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-app-boot/lib/index.js",
            "opt/node24/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-app-boot/lib/worker/profile-resolution-bootstrap.js",
            "opt/node24/lib/node_modules/@deepseek-ai/dsh-app-boot/lib/index.js",
            "opt/node24/lib/node_modules/@deepseek-ai/dsh-app-boot/lib/worker/profile-resolution-bootstrap.js"
        )
        var modifiedAny = false
        for (rel in candidates) {
            val file = File(targetRootfs, rel)
            if (!file.isFile) continue
            val original = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: continue
            if (original.contains(PROFILE_RES_OPT_MARKER)) continue
            val patched = patchDshBootResolutionSource(original) ?: continue
            writeFsyncAtomic(file, patched.toByteArray(Charsets.UTF_8))
            modifiedAny = true
        }
        return modifiedAny
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
        ensureEnvInitialized()
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
        ensureEnvInitialized()
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
        ensureEnvInitialized()
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
     * (both uncompressed `.jsonl` headers and compressed `session.vN.jsonl.zstd` generations)
     * without altering any files.
     */
    fun validateSnapshotCompatibility(dataDir: File) {
        val sessionsDir = File(dataDir, "sessions")
        if (!sessionsDir.exists() || !sessionsDir.isDirectory) return
        val versionedNameRegex = Regex("""^session\.v(\d+)\.jsonl(?:\.zstd)?$""")
        sessionsDir.walkTopDown().filter { it.isFile }.forEach { file ->
            // 1. Check compressed/uncompressed versioned generation filenames (e.g. session.v4.jsonl.zstd)
            versionedNameRegex.matchEntire(file.name)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { gen ->
                if (gen > MAX_SUPPORTED_SESSION_FORMAT_VERSION) {
                    throw IllegalStateException(
                        "Incompatible session generation v$gen in ${file.name} (max supported v$MAX_SUPPORTED_SESSION_FORMAT_VERSION)"
                    )
                }
            }
            // 2. Inspect JSON header line for uncompressed .json / .jsonl files
            if (file.name.endsWith(".json") || file.name.endsWith(".jsonl")) {
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
        val nodeCompat = isNodeVersionCompatible(nodeVer)
        checks += SelfTestCheck(
            name = "node_runtime",
            passed = nodeRes.exitCode == 0 && nodeVer.isNotEmpty() && nodeCompat,
            detail = when {
                nodeVer.isNotEmpty() && nodeCompat -> "$nodeVer (>= $MIN_NODE_VERSION)"
                nodeVer.isNotEmpty() -> "$nodeVer (requires >= $MIN_NODE_VERSION for DSH $TARGET_DSH_VERSION)"
                else -> "exit=${nodeRes.exitCode} ${nodeRes.stderr.take(120)}"
            }
        )

        val dshRes = runner("/opt/node24/bin/node", arrayOf(
            "--check", "/opt/dshn/dshn-session-bridge.mjs"
        ))
        val dshPkgVer = readDshPackageVersion()
        checks += SelfTestCheck(
            name = "dsh_and_bridge_syntax",
            passed = dshRes.exitCode == 0 && dshPkgVer.isNotEmpty(),
            detail = if (dshRes.exitCode == 0) {
                "dsh=$dshPkgVer (target $TARGET_DSH_VERSION), bridge syntax OK"
            } else {
                "exit=${dshRes.exitCode} ${dshRes.stderr.take(120)}"
            }
        )

        val ptyProbeScript = """
            import fs from 'node:fs';
            import { createRequire } from 'node:module';
            const c = [
              '/opt/node24/lib/node_modules/@deepseek-ai/dsh/node_modules/node-pty/lib/index.js',
              '/opt/node24/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-subprocess-local/node_modules/node-pty/lib/index.js',
              '/opt/node24/lib/node_modules/node-pty/lib/index.js'
            ];
            let pty = null;
            for (const p of c) {
              if (fs.existsSync(p)) { const m = await import(p); pty = m.default || m; break; }
            }
            if (!pty && fs.existsSync('/opt/node24/lib/node_modules/@deepseek-ai/dsh/package.json')) {
              pty = createRequire('/opt/node24/lib/node_modules/@deepseek-ai/dsh/package.json')('node-pty');
            }
            if (!pty || typeof pty.spawn !== 'function') process.exit(2);
            console.log('PTY_LOAD_OK');
        """.trimIndent().replace("\n", " ")
        val ptyRes = runner("/opt/node24/bin/node", arrayOf("--input-type=module", "-e", ptyProbeScript))
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

    /**
     * Transactionally upgrades `@deepseek-ai/dsh` inside the container:
     * 1. Creates a pre-upgrade `~/.dsh` state snapshot (excluding secrets).
     * 2. Installs `@deepseek-ai/dsh@<targetVersion>` via npm with required `--allow-scripts`.
     * 3. Re-syncs DSHN bridge assets & runs container self-test to verify `node-pty` and syntax.
     */
    fun upgradeDshToVersion(targetVersion: String = TARGET_DSH_VERSION): Result<DshUpgradeReport> = runCatching {
        require(isRootfsReady()) { "Container rootfs or Node 24 runtime is not ready" }
        val exec = longCommandRunner ?: commandRunner?.let { r -> { exe, args, _ -> r(exe, args) } }
            ?: error("No container runner configured")
        val cleanTarget = targetVersion.trim().ifEmpty { TARGET_DSH_VERSION }
        require(Regex("""^[A-Za-z0-9._-]+$""").matches(cleanTarget)) { "Invalid DSH version specifier: $cleanTarget" }

        val prevVer = readDshPackageVersion()
        val snapId = createSnapshot("pre-dsh-$cleanTarget").getOrNull()?.id

        val npmBin = "/opt/node24/bin/npm"
        val installRes = exec(
            npmBin,
            arrayOf(
                "install", "-g",
                "--allow-scripts=$REQUIRED_ALLOW_SCRIPTS",
                "@deepseek-ai/dsh@$cleanTarget"
            ),
            300L
        )
        val combinedTail = (installRes.stdout + "\n" + installRes.stderr)
            .lines()
            .filter { it.isNotBlank() }
            .takeLast(25)
            .joinToString("\n")
        if (installRes.exitCode != 0) {
            error("npm install @deepseek-ai/dsh@$cleanTarget failed (exit=${installRes.exitCode}): $combinedTail")
        }

        ensureRuntimeAssets()
        val installedVer = readDshPackageVersion()
        val selfTest = runSelfTest()
        DshUpgradeReport(
            targetVersion = cleanTarget,
            previousVersion = prevVer,
            installedVersion = installedVer,
            snapshotId = snapId,
            selfTestPassed = selfTest.allPassed,
            logTail = combinedTail
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
            writeFsyncAtomic(resolv, "nameserver 223.5.5.5\nnameserver 119.29.29.29\nnameserver 8.8.8.8\n".toByteArray(Charsets.UTF_8))
            resolv.setReadable(true, false)
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
        const val BUNDLED_ENV_MANIFEST_ASSET = "env/bundle-manifest.json"
        const val TARGET_DSH_VERSION = "0.2.2-rc.5"
        const val MIN_NODE_VERSION = "24.9.0"
        const val CURRENT_SESSION_FORMAT_VERSION = 4
        const val MAX_SUPPORTED_SESSION_FORMAT_VERSION = 10
        const val REQUIRED_ALLOW_SCRIPTS =
            "@deepseek-ai/dsh-subprocess-local,koffi,node-pty,@google/genai,protobufjs"
        private const val PROFILE_RES_OPT_MARKER = "/* dshn-profile-resolution-opt-v1 */"

        /**
         * Wraps pre-release flat `.credentials.yaml` entries under `version: 1` + `refs:`.
         * Returns `null` if already migrated, empty, or not a flat mapping.
         */
        internal fun migrateLegacyCredentialsYamlText(raw: String): String? {
            val lines = raw.replace("\r\n", "\n").lines()
            val nonBlank = lines.filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
            if (nonBlank.isEmpty()) return null
            if (nonBlank.any { it.trimStart().startsWith("version:") || it.trimStart().startsWith("refs:") }) {
                return null
            }
            if (!nonBlank.first().contains(':')) return null
            return buildString {
                append("version: 1\n")
                append("refs:\n")
                for (line in lines) {
                    if (line.isBlank()) continue
                    append("  ").append(line).append("\n")
                }
            }
        }

        /**
         * Pure transformation implementing DSH Sugar's MIT `profile-resolution.patch`.
         * Returns the patched source text or `null` if the target pattern is not present.
         */
        internal fun patchDshBootResolutionSource(src: String): String? {
            if (src.contains(PROFILE_RES_OPT_MARKER)) return null
            val targetHunk1 = """function prefixes(path) {
	const configured = resolve(path) + sep;
	const canonical = canonicalPath(path) + sep;
	return canonical === configured ? [configured] : [configured, canonical];
}
function compileResolution(resolution) {
	return {
		entries: new Map(resolution.entries.map((entry) => [entry.name, {
			...entry,
			realDir: canonicalPath(entry.packageDir)
		}])),
		profilesDir: resolution.profilesDir,
		profileDir: resolution.profileDir,
		profilePaths: prefixes(resolution.profilesDir),
		profile: resolution.profileDir === void 0 ? [] : prefixes(resolution.profileDir),
		installationPaths: [...new Set(resolution.entries.filter((entry) => entry.scope === "installation").flatMap((entry) => prefixes(entry.packageDir)))],"""
            val replacementHunk1 = """$PROFILE_RES_OPT_MARKER
function prefixes(path, realDir = canonicalPath(path)) {
	const configured = resolve(path) + sep;
	const canonical = realDir + sep;
	return canonical === configured ? [configured] : [configured, canonical];
}
function compileResolution(resolution) {
	const linked = resolution.linkedRoots.length !== 0;
	const entries = resolution.entries.map((entry) => ({
		...entry,
		realDir: entry.scope === "installation" && !linked ? void 0 : canonicalPath(entry.packageDir)
	}));
	return {
		entries: new Map(entries.map((entry) => [entry.name, entry])),
		profilesDir: resolution.profilesDir,
		profileDir: resolution.profileDir,
		profilePaths: prefixes(resolution.profilesDir),
		profile: resolution.profileDir === void 0 ? [] : prefixes(resolution.profileDir),
		installationPaths: linked ? [...new Set(entries.filter((entry) => entry.scope === "installation").flatMap((entry) => prefixes(entry.packageDir, entry.realDir)))] : [],"""
            val targetHunk2 = """		const current = [];
		for (const entry of next.entries.values()) current.push(entry.realDir);
		for (const root of successor.linkedRoots) current.push(root.realPath);
		this.moved.forget(current);"""
            val replacementHunk2 = """		if (this.moved.targets.size !== 0) {
			const current = [];
			for (const entry of next.entries.values()) current.push(entry.realDir ?? canonicalPath(entry.packageDir));
			for (const root of successor.linkedRoots) current.push(root.realPath);
			this.moved.forget(current);
		}"""
            if (!src.contains(targetHunk1) || !src.contains(targetHunk2)) return null
            return src.replaceFirst(targetHunk1, replacementHunk1).replaceFirst(targetHunk2, replacementHunk2)
        }

        private val BUNDLED_ASSETS = listOf(
            "dshn-bridge.patch.yml" to false,
            "dshn-dns-preload.cjs" to false,
            "dshn-session-bridge.mjs" to true,
            "dshn-mcp-relay.mjs" to true,
            "dshn-pty-runner.mjs" to true
        )

        fun isNodeVersionCompatible(versionText: String): Boolean {
            val m = Regex("""v?(\d+)\.(\d+)\.(\d+)""").find(versionText) ?: return false
            val maj = m.groupValues[1].toIntOrNull() ?: return false
            val min = m.groupValues[2].toIntOrNull() ?: return false
            return maj > 24 || (maj == 24 && min >= 9)
        }

        fun isDshVersionAtTarget(installed: String, target: String = TARGET_DSH_VERSION): Boolean {
            if (installed.isBlank()) return false
            return compareDshVersions(installed, target) >= 0
        }

        fun compareDshVersions(a: String, b: String): Int {
            fun parse(v: String): List<Int> {
                val mainAndPre = v.trim().removePrefix("v").split('-', limit = 2)
                val core = mainAndPre[0].split('.').map { it.toIntOrNull() ?: 0 }
                val pre = mainAndPre.getOrNull(1).orEmpty()
                val preRank = when {
                    pre.isEmpty() -> 3
                    pre.startsWith("rc.") -> 2
                    pre.startsWith("beta.") -> 1
                    else -> 0
                }
                val preNum = Regex("""(\d+)$""").find(pre)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                return listOf(
                    core.getOrElse(0) { 0 },
                    core.getOrElse(1) { 0 },
                    core.getOrElse(2) { 0 },
                    preRank,
                    preNum
                )
            }
            val pa = parse(a)
            val pb = parse(b)
            for (i in pa.indices) {
                if (pa[i] != pb[i]) return pa[i].compareTo(pb[i])
            }
            return 0
        }
    }
}
