package dev.dshn.app.acp

import android.content.Context
import android.util.Log
import dev.dshn.app.runtime.ContainerLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * 把 AcpClient 接到 bxroot 容器内的 `dsh --profile acp` 进程。
 * 负责进程生命周期、stderr 排空（设计 H6）、协议握手。
 */
class AcpSessionManager(
    context: Context,
    private val scope: CoroutineScope,
    private val onPermissionRequest: suspend (PermissionRequest) -> PermissionOutcome,
) {
    private val launcher = ContainerLauncher(context)
    private var process: Process? = null
    private var client: AcpClient? = null

    val updates: SharedFlow<SessionUpdate>? get() = client?.updates

    fun isReady(): Boolean = launcher.isReady()

    /** 启动容器内 ACP 服务并完成 initialize 握手。 */
    suspend fun start(): AcpClient {
        check(launcher.isReady()) { "容器未就绪：rootfs 缺失" }
        val proc = launcher.start("/opt/node24/bin/dsh", "--profile", "acp")
        process = proc
        // 持续排空 stderr，避免管道满阻塞（H6）
        scope.launch(Dispatchers.IO) {
            runCatching {
                proc.errorStream.bufferedReader().forEachLine { line ->
                    if (!line.contains("selftest")) Log.d(TAG, "stderr: $line")
                }
            }
        }
        val c = AcpClient(proc.inputStream, proc.outputStream, scope, onPermissionRequest)
        val init = c.initialize()
        Log.i(TAG, "ACP initialized: $init")
        client = c
        return c
    }

    fun client(): AcpClient? = client

    fun stop() {
        client?.close()
        runCatching { process?.destroyForcibly() }
        client = null
        process = null
    }

    companion object { private const val TAG = "DSHN-Acp" }
}