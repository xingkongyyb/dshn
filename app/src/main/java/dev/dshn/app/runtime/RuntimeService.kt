package dev.dshn.app.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.RemoteCallbackList
import dev.dshn.app.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Dedicated `:runtime` process Service hosting [RuntimeSupervisor] under `specialUse` ForegroundService.
 * Crashes or native errors inside the container process will not crash the `:app` UI process.
 */
class RuntimeService : Service(), RuntimeEventSink {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var supervisor: RuntimeSupervisor
    private val callbacks = RemoteCallbackList<IRuntimeCallback>()
    private val notifManager by lazy { getSystemService(NotificationManager::class.java) }

    private val binder = object : IRuntimeService.Stub() {
        override fun registerCallback(cb: IRuntimeCallback?) {
            if (cb != null) {
                callbacks.register(cb)
                runCatching {
                    cb.onStateChanged(supervisor.state.name, supervisor.detail, 0)
                }
            }
        }

        override fun unregisterCallback(cb: IRuntimeCallback?) {
            if (cb != null) callbacks.unregister(cb)
        }

        override fun startRuntime() {
            promoteToForeground("Starting DSH runtime...")
            serviceScope.launch { supervisor.start() }
        }

        override fun stopRuntime() {
            serviceScope.launch {
                supervisor.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
        }

        override fun restartRuntime() {
            promoteToForeground("Restarting DSH runtime...")
            serviceScope.launch { supervisor.restart() }
        }

        override fun getStatusJson(): String = supervisor.getStatusJson()

        override fun getRecentLogsJson(maxLines: Int): String = supervisor.getRecentLogsJson(maxLines)

        override fun clearLogs() {
            supervisor.clearLogs()
        }

        override fun newSession(cwd: String?): String = runBlocking(Dispatchers.IO) {
            supervisor.newSession(cwd.orEmpty())
        }

        override fun resumeSession(sessionId: String?, cwd: String?): String = runBlocking(Dispatchers.IO) {
            supervisor.resumeSession(sessionId.orEmpty(), cwd.orEmpty())
        }

        override fun closeSession(sessionId: String?): String = runBlocking(Dispatchers.IO) {
            supervisor.closeSession(sessionId.orEmpty())
        }

        override fun setConfigOption(sessionId: String?, configId: String?, value: String?): String =
            runBlocking(Dispatchers.IO) {
                supervisor.setConfigOption(sessionId.orEmpty(), configId.orEmpty(), value.orEmpty())
            }

        override fun listSessionsFromBridge(): String = runBlocking(Dispatchers.IO) {
            supervisor.listSessionsFromBridge()
        }

        override fun readSessionSurfaceFromBridge(sessionId: String?): String = runBlocking(Dispatchers.IO) {
            supervisor.readSurfaceFromBridge(sessionId.orEmpty())
        }

        override fun listCommandsFromBridge(sessionId: String?): String = runBlocking(Dispatchers.IO) {
            supervisor.listCommandsFromBridge(sessionId)
        }

        override fun executeCommandFromBridge(sessionId: String?, line: String?): String =
            runBlocking(Dispatchers.IO) {
                supervisor.executeCommandFromBridge(sessionId.orEmpty(), line.orEmpty())
            }

        override fun sendPrompt(requestId: String?, sessionId: String?, text: String?) {
            supervisor.sendPromptAsync(requestId.orEmpty(), sessionId.orEmpty(), text.orEmpty())
        }

        override fun cancelPrompt(sessionId: String?) {
            supervisor.cancelPrompt(sessionId.orEmpty())
        }

        override fun replyPermission(requestId: String?, optionId: String?, cancelled: Boolean) {
            supervisor.replyPermission(requestId.orEmpty(), optionId.orEmpty(), cancelled)
        }

        override fun replyQuestion(requestId: String?, answersJson: String?, cancelled: Boolean) {
            serviceScope.launch {
                supervisor.replyQuestion(requestId.orEmpty(), answersJson.orEmpty(), cancelled)
            }
        }

        override fun reloadSettings() {
            supervisor.reloadSettings()
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        supervisor = RuntimeSupervisor(applicationContext, serviceScope, this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            serviceScope.launch {
                supervisor.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return START_NOT_STICKY
        }
        promoteToForeground("DSH runtime ready")
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        callbacks.kill()
        runBlocking { supervisor.stop() }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun promoteToForeground(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun ensureNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "DSHN Agent Runtime",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps the local DeepSeek Harness agent container alive during tasks."
        }
        notifManager.createNotificationChannel(channel)
    }

    private fun buildNotification(contentText: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, RuntimeService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("DSHN Runtime")
            .setContentText(contentText)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(null, "Stop", stopIntent).build()
            )
            .build()
    }

    private inline fun broadcast(block: (IRuntimeCallback) -> Unit) {
        synchronized(callbacks) {
            val count = callbacks.beginBroadcast()
            try {
                for (i in 0 until count) {
                    runCatching { block(callbacks.getBroadcastItem(i)) }
                }
            } finally {
                callbacks.finishBroadcast()
            }
        }
    }

    override fun onStateChanged(state: RuntimeLifecycleState, detail: String, restartCount: Int) {
        if (state == RuntimeLifecycleState.HEALTHY || state == RuntimeLifecycleState.STARTING || state == RuntimeLifecycleState.DEGRADED) {
            runCatching { notifManager.notify(NOTIF_ID, buildNotification("${state.name}: $detail")) }
        }
        broadcast { it.onStateChanged(state.name, detail, restartCount) }
    }

    override fun onSessionUpdate(sessionId: String, jsonUpdate: String) {
        broadcast { it.onSessionUpdate(sessionId, jsonUpdate) }
    }

    override fun onPermissionRequested(
        requestId: String,
        sessionId: String,
        toolCallId: String?,
        title: String?,
        optionsJson: String
    ) {
        broadcast { it.onPermissionRequested(requestId, sessionId, toolCallId, title, optionsJson) }
    }

    override fun onQuestionRequested(questionRequestJson: String) {
        broadcast { it.onQuestionRequested(questionRequestJson) }
    }

    override fun onQuestionCancelled(requestId: String, sessionId: String?) {
        broadcast { it.onQuestionCancelled(requestId, sessionId) }
    }

    override fun onPromptCompleted(
        requestId: String,
        sessionId: String,
        stopReason: String?,
        errorMessage: String?
    ) {
        broadcast { it.onPromptCompleted(requestId, sessionId, stopReason, errorMessage) }
    }

    override fun onInterruptedTurnUnknown(sessionId: String, message: String) {
        broadcast { it.onInterruptedTurnUnknown(sessionId, message) }
    }

    override fun onStderrLine(line: String) {
        broadcast { it.onStderrLine(line) }
    }

    companion object {
        const val CHANNEL_ID = "dshn_runtime"
        const val NOTIF_ID = 4101
        const val ACTION_STOP = "dev.dshn.app.runtime.STOP"
    }
}
