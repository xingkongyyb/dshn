package dev.dshn.app

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.dshn.app.runtime.ContainerLauncher
import kotlin.concurrent.thread

/**
 * P0 spike 测试 harness。
 * 默认跑 node -v 自检；可用 intent extra 触发任意容器命令：
 *   am start -n dev.dshn.app/.MainActivity --es cmd "export PATH=/opt/node24/bin:$PATH && dsh --version"
 * extra: cmd（bash -c 命令串）、timeout（秒，默认 600）。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        var status by mutableStateOf("容器运行中…")
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.padding(24.dp).verticalScroll(rememberScrollState())) {
                        Text("DSHN", style = MaterialTheme.typography.headlineMedium)
                        Text(status, modifier = Modifier.padding(top = 16.dp))
                    }
                }
            }
        }
        val cmdFile = java.io.File(filesDir, "cmd.sh")
        val cmd = intent.getStringExtra("cmd") ?: if (cmdFile.exists()) cmdFile.readText() else null
        val timeout = intent.getStringExtra("timeout")?.toLongOrNull() ?: 600L
        thread {
            val cl = ContainerLauncher(this)
            status = if (!cl.isReady()) {
                "容器未就绪：rootfs 存在=${cl.rootfs.exists()}"
            } else if (cmd != null) {
                Log.i(TAG, "cmd: $cmd")
                val r = cl.runCapture("/bin/bash", "-c", cmd, timeoutSec = timeout)
                "exit=${r.exitCode}\n--- stdout ---\n${r.stdout}\n--- stderr ---\n${r.stderr.take(2000)}"
            } else {
                val r = cl.runCapture("/opt/node24/bin/node", "-v")
                "node exit=${r.exitCode} ${r.stdout.trim()}"
            }
            Log.i(TAG, "result:\n$status")
            runCatching { java.io.File(filesDir, "result.txt").writeText(status) }
        }
    }
    companion object { private const val TAG = "DSHN-Container" }
}