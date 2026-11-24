package dev.dshn.app.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

data class PendingBridgeConfirmation(
    val id: String,
    val tool: String,
    val summary: String,
    val detail: String,
    val isRootAction: Boolean = false,
    val promptInjectionWarning: String =
        "Security warning: screen content from external apps is untrusted input. Verify this action before approving."
)

/**
 * Coordinates per-call human confirmation for high-risk device operations
 * (tap, swipe, text input, global actions, ADB exec, Root exec). Serializes requests cleanly.
 */
class ConfirmationBroker(private val context: Context) {
    private val queueMutex = Mutex()
    private val pendingDeferredLock = Any()
    private var activeDeferred: CompletableDeferred<Boolean>? = null

    private val _pending = MutableStateFlow<PendingBridgeConfirmation?>(null)
    val pending: StateFlow<PendingBridgeConfirmation?> = _pending.asStateFlow()

    private val notifManager by lazy { context.getSystemService(NotificationManager::class.java) }

    suspend fun requestConfirmation(
        tool: String,
        summary: String,
        detail: String,
        timeoutMs: Long = 45_000L,
        isRootAction: Boolean = false
    ): Boolean = queueMutex.withLock {
        val id = "bridge-confirm-${System.nanoTime()}"
        val deferred = CompletableDeferred<Boolean>()
        synchronized(pendingDeferredLock) {
            activeDeferred = deferred
        }
        val warning = if (isRootAction) {
            "ROOT (uid=0) privilege warning: this command executes on the Android host as superuser. Verify every argument before approving."
        } else {
            "Security warning: screen content from external apps is untrusted input. Verify this action before approving."
        }
        val req = PendingBridgeConfirmation(
            id = id,
            tool = tool,
            summary = summary,
            detail = detail,
            isRootAction = isRootAction,
            promptInjectionWarning = warning
        )
        _pending.value = req
        postHeadsUpNotification(req)

        return@withLock try {
            withTimeoutOrNull(timeoutMs) { deferred.await() } ?: false
        } finally {
            synchronized(pendingDeferredLock) {
                if (activeDeferred === deferred) activeDeferred = null
            }
            if (_pending.value?.id == id) _pending.value = null
            runCatching { notifManager.cancel(NOTIF_ID) }
        }
    }

    fun respond(requestId: String, allowOnce: Boolean) {
        val current = _pending.value
        if (current != null && current.id != requestId) return
        val d = synchronized(pendingDeferredLock) { activeDeferred }
        d?.complete(allowOnce)
    }

    private fun postHeadsUpNotification(req: PendingBridgeConfirmation) {
        runCatching {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Device Action Confirmations",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Prompts when the local agent requests a high-risk device interaction."
            }
            notifManager.createNotificationChannel(channel)

            val launchIntent = (context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent())
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val openIntent = PendingIntent.getActivity(
                context,
                NOTIF_ID,
                launchIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val notif = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("Approve ${req.tool}?")
                .setContentText(req.summary)
                .setContentIntent(openIntent)
                .setAutoCancel(true)
                .build()
            notifManager.notify(NOTIF_ID, notif)
        }
    }

    companion object {
        private const val CHANNEL_ID = "dshn_bridge_confirm"
        private const val NOTIF_ID = 4201
    }
}
