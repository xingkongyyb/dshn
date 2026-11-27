package dev.dshn.app.runtime

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

data class StagedLocalPlugin(
    val containerPath: String,
    val hostFile: File,
    val kind: String // "tarball" or "directory"
)

/**
 * Safely stages a user-selected local plugin package (`.tgz`, `.tar.gz`, or `.zip`)
 * into `<rootfs>/root/.dsh/local-plugins/` for installation via DSH `pluginManager.installBundle`.
 *
 * Security invariants:
 * - Enforces hard compressed (128 MB) and extracted (256 MB / 4,000 entries) ceilings.
 * - Rejects Zip-Slip path traversal (`..` / absolute paths escaping destination directory).
 * - Sanitizes filenames to ASCII `[A-Za-z0-9._-]`.
 */
object LocalPluginImporter {
    const val CONTAINER_LOCAL_PLUGINS_DIR = "/root/.dsh/local-plugins"
    private const val MAX_ARCHIVE_BYTES = 128L * 1024L * 1024L
    private const val MAX_EXTRACTED_BYTES = 256L * 1024L * 1024L
    private const val MAX_ZIP_ENTRIES = 4_000

    fun stageLocalPluginUri(
        context: Context,
        rootfs: File,
        uri: Uri
    ): Result<StagedLocalPlugin> = runCatching {
        val displayName = resolveDisplayName(context, uri) ?: "local-plugin.tgz"
        val stream = context.contentResolver.openInputStream(uri)
            ?: error("无法读取所选文件 / Unable to open selected file")
        stream.use { input ->
            stageLocalPluginStream(rootfs, displayName, input).getOrThrow()
        }
    }

    fun stageLocalPluginStream(
        rootfs: File,
        displayName: String,
        input: InputStream,
        maxArchiveBytes: Long = MAX_ARCHIVE_BYTES
    ): Result<StagedLocalPlugin> = runCatching {
        val stagingRoot = File(rootfs, "root/.dsh/local-plugins").apply { mkdirs() }
        stagingRoot.setReadable(true, false)
        stagingRoot.setExecutable(true, false)

        val rawBase = sanitizeBaseName(displayName)
        val ts = System.currentTimeMillis()
        val tmpFile = File(stagingRoot, ".import-$ts.tmp")

        try {
            val buffered = BufferedInputStream(input, 32 * 1024)
            buffered.mark(8)
            val header = ByteArray(4)
            val headerRead = buffered.read(header)
            buffered.reset()

            val lowerName = displayName.lowercase()
            val isGzipMagic = headerRead >= 2 && (header[0].toInt() and 0xFF) == 0x1F && (header[1].toInt() and 0xFF) == 0x8B
            val isZipMagic = headerRead >= 2 && (header[0].toInt() and 0xFF) == 0x50 && (header[1].toInt() and 0xFF) == 0x4B

            val isTarball = isGzipMagic || lowerName.endsWith(".tgz") || lowerName.endsWith(".tar.gz")
            val isZip = !isTarball && (isZipMagic || lowerName.endsWith(".zip"))

            if (!isTarball && !isZip) {
                error("不支持的文件格式「$displayName」，请选择 .tgz、.tar.gz 或 .zip 插件包")
            }

            // Copy bounded archive to temp file first
            var totalRead = 0L
            FileOutputStream(tmpFile).use { out ->
                val buf = ByteArray(32 * 1024)
                while (true) {
                    val n = buffered.read(buf)
                    if (n < 0) break
                    totalRead += n
                    if (totalRead > maxArchiveBytes) {
                        error("插件包体积超过限制（最大 ${maxArchiveBytes / (1024 * 1024)} MB）")
                    }
                    out.write(buf, 0, n)
                }
                out.flush()
            }
            if (totalRead == 0L) {
                error("所选文件为空")
            }

            if (isTarball) {
                val finalName = "$rawBase-$ts.tgz"
                val finalFile = File(stagingRoot, finalName)
                check(tmpFile.renameTo(finalFile)) { "写入本地插件缓存失败" }
                finalFile.setReadable(true, false)
                StagedLocalPlugin(
                    containerPath = "$CONTAINER_LOCAL_PLUGINS_DIR/$finalName",
                    hostFile = finalFile,
                    kind = "tarball"
                )
            } else {
                val extractDirName = "$rawBase-$ts"
                val extractDir = File(stagingRoot, extractDirName).apply { mkdirs() }
                try {
                    extractZipSafely(tmpFile, extractDir)
                    val resolvedDir = resolvePackageRoot(extractDir)
                        ?: error("ZIP 压缩包内未找到 package.json，不是有效的 DSH 插件包")
                    val relPath = resolvedDir.canonicalFile.toRelativeString(stagingRoot.canonicalFile)
                        .replace(File.separatorChar, '/')
                    StagedLocalPlugin(
                        containerPath = "$CONTAINER_LOCAL_PLUGINS_DIR/$relPath",
                        hostFile = resolvedDir,
                        kind = "directory"
                    )
                } catch (t: Throwable) {
                    extractDir.deleteRecursively()
                    throw t
                }
            }
        } finally {
            if (tmpFile.exists()) tmpFile.delete()
        }
    }

    private fun extractZipSafely(zipFile: File, destDir: File) {
        val canonicalDest = destDir.canonicalFile
        var entryCount = 0
        var totalExtracted = 0L
        val buf = ByteArray(16 * 1024)

        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                entryCount++
                if (entryCount > MAX_ZIP_ENTRIES) {
                    error("ZIP 内文件数量超过限制（>$MAX_ZIP_ENTRIES）")
                }
                val name = entry.name.replace('\\', '/')
                if (name.isBlank() || name.startsWith("/") || name.split('/').any { it == ".." }) {
                    error("检测到不安全的压缩包路径：$name")
                }
                val outFile = File(canonicalDest, name).canonicalFile
                val destPrefix = canonicalDest.path + File.separator
                if (outFile.path != canonicalDest.path && !outFile.path.startsWith(destPrefix)) {
                    error("检测到路径穿越条目：$name")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                    outFile.setReadable(true, false)
                    outFile.setExecutable(true, false)
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        while (true) {
                            val n = zis.read(buf)
                            if (n < 0) break
                            totalExtracted += n
                            if (totalExtracted > MAX_EXTRACTED_BYTES) {
                                error("ZIP 解压后体积超过限制（最大 ${MAX_EXTRACTED_BYTES / (1024 * 1024)} MB）")
                            }
                            fos.write(buf, 0, n)
                        }
                    }
                    outFile.setReadable(true, false)
                }
                zis.closeEntry()
            }
        }
    }

    /**
     * Resolves the directory containing `package.json`. Supports both flat archives and
     * single top-level wrapper folders (such as GitHub "Download ZIP" or `npm pack` `package/`).
     */
    private fun resolvePackageRoot(extractDir: File): File? {
        if (File(extractDir, "package.json").isFile) return extractDir
        val children = extractDir.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") && it.name != "__MACOSX" }
            .orEmpty()
        if (children.size == 1 && File(children[0], "package.json").isFile) {
            return children[0]
        }
        return null
    }

    private fun sanitizeBaseName(displayName: String): String {
        val stripped = displayName
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .removeSuffix(".tar.gz")
            .removeSuffix(".TAR.GZ")
            .removeSuffix(".tgz")
            .removeSuffix(".TGZ")
            .removeSuffix(".zip")
            .removeSuffix(".ZIP")
        val clean = stripped.replace(Regex("""[^A-Za-z0-9._-]"""), "_").trim('_', '.')
        return clean.take(40).ifEmpty { "plugin" }
    }

    private fun resolveDisplayName(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment
    }
}
