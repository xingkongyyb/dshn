package dev.dshn.app.runtime

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * bxroot 容器启动器封装。
 * 契约见 vendor/bxroot/PROVENANCE.md 与上游 docs/DSHA-适配说明.md：
 * launcher 从 argv 派生 ROOTFS/WORKDIR/FAKEROOT/LINK2SYMLINK；
 * App 侧只设 LIB_PATH/LINKER_PATH/STUB_LOADER/TMP_DIR/L2S_DIR。
 * 环境变量每次启动重生成（设计 H10：nativeLibraryDir 路径随安装变化）。
 */
class ContainerLauncher(private val context: Context) {

    private val nativeLibDir: File = File(context.applicationInfo.nativeLibraryDir)
    private val launcher: File = File(nativeLibDir, "libbxroot.so")

    /** 容器根文件系统（App 私有目录，bxroot 要求）。
     *  用 /data/data 规范形式：bxroot init 自检的 stat 钩子对 /data/user/0 形式报 ENOENT。 */
    val rootfs: File = File(
        File(context.filesDir, "linux/ubuntu").absolutePath
            .replaceFirst("/data/user/0/", "/data/data/")
    )

    private val tmpDir: File = File(context.cacheDir, "bxroot-tmp").apply { mkdirs() }

    /** 最小 bind 集（设计 §8：不 bind /sdcard，凭据区不入容器）。 */
    private val binds: List<String> = listOf(
        "/dev:/dev",
        "/proc:/proc",
        "/sys:/sys",
    )

    fun isReady(): Boolean = launcher.canExecute() && File(rootfs, "bin/sh").exists()

    /** 构建 bxroot 命令行：launcher -r rootfs -0 -l -w /root [-b ...] <guest-exe> [args]。 */
    private fun command(guestExe: String, args: List<String>): List<String> = buildList {
        add(launcher.absolutePath)
        add("-r"); add(rootfs.absolutePath)
        add("-0")
        add("-l")
        add("-w"); add("/root")
        for (b in binds) { add("-b"); add(b) }
        add(guestExe)
        addAll(args)
    }

    private fun environment(): Map<String, String> = mapOf(
        "BXROOT_LIB_PATH" to File(nativeLibDir, "libbxroot-runtime.so").absolutePath,
        "BXROOT_LINKER_PATH" to File(nativeLibDir, "libbxroot-ulx.so").absolutePath,
        "BXROOT_STUB_LOADER" to File(nativeLibDir, "libbxroot-stub-loader.so").absolutePath,
        "BXROOT_TMP_DIR" to tmpDir.absolutePath,
        "BXROOT_L2S_DIR" to File(rootfs, ".l2s").absolutePath,
        "PATH" to "/opt/node24/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "HOME" to "/root",
        "TERM" to "xterm-256color",
    )

    /** 启动容器进程（stdio 管道，stderr 独立以便排空，设计 H6）。 */
    fun start(guestExe: String, vararg args: String): Process {
        val pb = ProcessBuilder(command(guestExe, args.toList()))
        pb.environment().putAll(environment())
        pb.redirectErrorStream(false)
        Log.i(TAG, "launch: ${command(guestExe, args.toList()).joinToString(" ")}")
        return pb.start()
    }

    /** 同步跑一条命令，捕获 stdout/stderr（spike 验证用）。 */
    fun runCapture(guestExe: String, vararg args: String, timeoutSec: Long = 60): Result {
        val proc = start(guestExe, *args)
        val out = StringBuilder()
        val err = StringBuilder()
        val tOut = Thread { proc.inputStream.bufferedReader().forEachLine { out.appendLine(it) } }
        val tErr = Thread { proc.errorStream.bufferedReader().forEachLine { err.appendLine(it) } }
        tOut.start(); tErr.start()
        val finished = proc.waitFor(timeoutSec, TimeUnit.SECONDS)
        if (!finished) { proc.destroyForcibly(); }
        tOut.join(2000); tErr.join(2000)
        val code = if (finished) proc.exitValue() else -1
        Log.i(TAG, "exit=$code stdout=${out.toString().trim()} stderr=${err.toString().trim()}")
        return Result(code, out.toString(), err.toString())
    }

    data class Result(val exitCode: Int, val stdout: String, val stderr: String)

    companion object { private const val TAG = "DSHN-Container" }
}