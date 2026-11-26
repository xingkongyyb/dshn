package dev.dshn.app.runtime

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * bxroot container launcher wrapper.
 * Paths are canonicalized to `/data/data/<pkg>/...` so bxroot selftests succeed on Android.
 * Minimum bind set only (`/dev`, `/proc`, `/sys`, plus app-private bridge socket dir and optional explicit workspace).
 */
class ContainerLauncher(private val context: Context) {

    val nativeLibDir: File = File(context.applicationInfo.nativeLibraryDir)
    private val launcher: File = File(nativeLibDir, "libbxroot.so")

    val rootFilesDir: File = File(
        context.filesDir.absolutePath.replaceFirst("/data/user/0/", "/data/data/")
    )

    val rootfs: File = File(rootFilesDir, "linux/ubuntu")
    val bridgeDir: File = File(rootFilesDir, "bridge")
    private val tmpDir: File = File(context.cacheDir, "bxroot-tmp")

    @Volatile
    private var dirsPrepared: Boolean = false

    fun ensureDirs() {
        if (dirsPrepared) return
        synchronized(this) {
            if (dirsPrepared) return
            if (!bridgeDir.exists()) bridgeDir.mkdirs()
            bridgeDir.setReadable(false, false); bridgeDir.setReadable(true, true)
            bridgeDir.setWritable(false, false); bridgeDir.setWritable(true, true)
            bridgeDir.setExecutable(false, false); bridgeDir.setExecutable(true, true)
            if (!tmpDir.exists()) tmpDir.mkdirs()
            dirsPrepared = true
        }
    }

    /**
     * Checks whether Node 24's persistent V8 compile cache can be enabled safely.
     * Guards against the `pwritev` zero-byte cache file pitfall documented by DSH Sugar:
     * if a prior run produced >= 5 cache files and ALL of them are 0 bytes, disable the cache.
     */
    private fun resolveNodeCompileCacheDir(): String? {
        val cacheDir = File(rootfs, "root/.cache/dshn-v8")
        if (cacheDir.exists() && cacheDir.isDirectory) {
            val files = cacheDir.listFiles()?.filter { it.isFile } ?: emptyList()
            if (files.size >= 5 && files.all { it.length() == 0L }) {
                Log.w(TAG, "Disabling NODE_COMPILE_CACHE: detected ${files.size} zero-byte cache files")
                return null
            }
        } else {
            runCatching { cacheDir.mkdirs() }
        }
        return "/root/.cache/dshn-v8"
    }

    private fun binds(extraWorkspaceBind: String? = null): List<String> = buildList {
        add("/dev:/dev")
        add("/proc:/proc")
        add("/sys:/sys")
        add("${bridgeDir.absolutePath}:/run/dshn-bridge")
        if (!extraWorkspaceBind.isNullOrBlank()) {
            val host = File(extraWorkspaceBind)
            if (host.exists() && host.isDirectory) {
                add("${host.absolutePath}:/mnt/workspace")
            }
        }
    }

    fun isReady(): Boolean = launcher.canExecute() && File(rootfs, "bin/sh").exists()

    private fun command(
        guestExe: String,
        args: List<String>,
        cwd: String = "/root",
        extraWorkspaceBind: String? = null
    ): List<String> = buildList {
        add(launcher.absolutePath)
        add("-r"); add(rootfs.absolutePath)
        add("-0")
        add("-l")
        add("-w"); add(cwd)
        for (b in binds(extraWorkspaceBind)) {
            add("-b"); add(b)
        }
        add(guestExe)
        addAll(args)
    }

    private fun environment(
        permissionMode: String = "workspace-write",
        deepseekBaseUrl: String? = null,
        deepseekSearchBaseUrl: String? = null,
        webSearchProvider: String? = null,
        extraEnv: Map<String, String> = emptyMap()
    ): Map<String, String> = buildMap {
        put("BXROOT_LIB_PATH", File(nativeLibDir, "libbxroot-runtime.so").absolutePath)
        put("BXROOT_LINKER_PATH", File(nativeLibDir, "libbxroot-ulx.so").absolutePath)
        put("BXROOT_STUB_LOADER", File(nativeLibDir, "libbxroot-stub-loader.so").absolutePath)
        put("BXROOT_TMP_DIR", tmpDir.absolutePath)
        put("BXROOT_L2S_DIR", File(rootfs, ".l2s").absolutePath)
        put("PATH", "/opt/node24/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
        put("HOME", "/root")
        put("TERM", "xterm-256color")
        put("LANG", "en_US.UTF-8")
        put("LC_ALL", "en_US.UTF-8")
        put("DSH_PERMISSION_MODE", permissionMode)
        put("DSHN_SESSION_BRIDGE_SOCK", "/run/dshn-bridge/session-bridge.sock")
        put("DSHN_DEVICE_MCP_SOCK", "/run/dshn-bridge/device-mcp.sock")

        val caBundle = File(rootfs, "etc/ssl/certs/ca-certificates.crt")
        if (caBundle.exists()) {
            put("SSL_CERT_FILE", "/etc/ssl/certs/ca-certificates.crt")
        }

        if (!deepseekBaseUrl.isNullOrBlank()) {
            put("DEEPSEEK_BASE_URL", deepseekBaseUrl.trim())
        }
        if (!deepseekSearchBaseUrl.isNullOrBlank()) {
            put("DEEPSEEK_SEARCH_BASE_URL", deepseekSearchBaseUrl.trim())
        }
        if (!webSearchProvider.isNullOrBlank()) {
            put("DSH_WEB_SEARCH_PROVIDER", webSearchProvider.trim())
        }

        val keyFile = File(rootfs, "root/.dsh/api_key")
        if (keyFile.exists()) {
            val key = runCatching { keyFile.readText().trim() }.getOrDefault("")
            if (key.isNotEmpty()) put("DEEPSEEK_API_KEY", key)
        }
        putAll(extraEnv)

        val dnsPreload = File(rootfs, "opt/dshn/dshn-dns-preload.cjs")
        if (dnsPreload.exists()) {
            val flag = "--require=/opt/dshn/dshn-dns-preload.cjs"
            val cur = this["NODE_OPTIONS"].orEmpty().trim()
            put("NODE_OPTIONS", if (cur.contains(flag)) cur else "$flag $cur".trim())
        }

        if (!containsKey("NODE_COMPILE_CACHE") && !containsKey("NODE_DISABLE_COMPILE_CACHE")) {
            resolveNodeCompileCacheDir()?.let { cachePath ->
                put("NODE_COMPILE_CACHE", cachePath)
            }
        }
    }

    fun start(
        guestExe: String,
        vararg args: String,
        cwd: String = "/root",
        permissionMode: String = "workspace-write",
        extraWorkspaceBind: String? = null,
        deepseekBaseUrl: String? = null,
        deepseekSearchBaseUrl: String? = null,
        webSearchProvider: String? = null,
        extraEnv: Map<String, String> = emptyMap()
    ): Process {
        ensureDirs()
        val cmd = command(guestExe, args.toList(), cwd = cwd, extraWorkspaceBind = extraWorkspaceBind)
        val pb = ProcessBuilder(cmd)
        pb.environment().putAll(
            environment(
                permissionMode = permissionMode,
                deepseekBaseUrl = deepseekBaseUrl,
                deepseekSearchBaseUrl = deepseekSearchBaseUrl,
                webSearchProvider = webSearchProvider,
                extraEnv = extraEnv
            )
        )
        pb.redirectErrorStream(false)
        Log.i(TAG, "launch: $guestExe ${args.joinToString(" ")}")
        return pb.start()
    }

    fun runCapture(
        guestExe: String,
        vararg args: String,
        timeoutSec: Long = 60,
        permissionMode: String = "workspace-write",
        extraEnv: Map<String, String> = emptyMap()
    ): Result {
        val proc = start(guestExe, *args, permissionMode = permissionMode, extraEnv = extraEnv)
        val out = StringBuilder()
        val err = StringBuilder()
        val tOut = Thread { runCatching { proc.inputStream.bufferedReader().forEachLine { out.appendLine(it) } } }
        val tErr = Thread { runCatching { proc.errorStream.bufferedReader().forEachLine { err.appendLine(it) } } }
        tOut.start(); tErr.start()
        val finished = proc.waitFor(timeoutSec, TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
        }
        tOut.join(2000); tErr.join(2000)
        val code = if (finished) proc.exitValue() else -1
        return Result(code, out.toString(), err.toString())
    }

    data class Result(val exitCode: Int, val stdout: String, val stderr: String)

    companion object {
        private const val TAG = "DSHN-Container"
    }
}
