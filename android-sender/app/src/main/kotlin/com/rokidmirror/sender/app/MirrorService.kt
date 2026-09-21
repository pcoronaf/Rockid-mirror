package com.rokidmirror.sender.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.rokidmirror.sender.R
import com.rokidmirror.sender.app.session.SyntheticPattern
import com.rokidmirror.sender.app.ui.MainActivity
import com.rokidmirror.sender.control.SenderState
import com.rokidmirror.sender.telemetry.MirrorLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground service of type mediaProjection. It must be in the foreground *before*
 * `MediaProjectionManager.getMediaProjection` is called (Android 14+), which is why the
 * activity forwards the consent result here instead of starting capture itself.
 * The persistent notification doubles as the user-visible "mirroring active" indicator.
 */
class MirrorService : Service() {
    companion object {
        private const val TAG = "Service"
        const val ACTION_START_PROJECTION = "com.rokidmirror.sender.START_PROJECTION"
        const val ACTION_START_SYNTHETIC = "com.rokidmirror.sender.START_SYNTHETIC"
        const val ACTION_START_EXTENDED = "com.rokidmirror.sender.START_EXTENDED"
        const val ACTION_STOP = "com.rokidmirror.sender.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_SOURCE_LABEL = "sourceLabel"
        const val EXTRA_PATTERN = "pattern"
        private const val CHANNEL_ID = "mirroring"
        private const val NOTIFICATION_ID = 1

        fun startProjection(context: Context, resultCode: Int, data: Intent, sourceLabel: String) {
            val i = Intent(context, MirrorService::class.java).setAction(ACTION_START_PROJECTION)
                .putExtra(EXTRA_RESULT_CODE, resultCode).putExtra(EXTRA_RESULT_DATA, data).putExtra(EXTRA_SOURCE_LABEL, sourceLabel)
            context.startForegroundService(i)
        }

        fun startSynthetic(context: Context, pattern: SyntheticPattern) {
            context.startForegroundService(
                Intent(context, MirrorService::class.java).setAction(ACTION_START_SYNTHETIC).putExtra(EXTRA_PATTERN, pattern.name),
            )
        }

        fun startExtended(context: Context) {
            context.startForegroundService(Intent(context, MirrorService::class.java).setAction(ACTION_START_EXTENDED))
        }

        fun stop(context: Context) { context.startService(Intent(context, MirrorService::class.java).setAction(ACTION_STOP)) }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val session by lazy { MirrorApplication.from(application).session }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_PROJECTION -> {
                usesProjection = true
                goForeground(projecting = true)
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
                @Suppress("DEPRECATION") val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                val label = intent.getStringExtra(EXTRA_SOURCE_LABEL) ?: "Phone"
                if (data == null || resultCode == Int.MIN_VALUE) { stopSelfSafely(); return START_NOT_STICKY }
                scope.launch {
                    runCatching { session.startProjectionStreaming(resultCode, data, label) }
                        .onFailure { MirrorLog.e(TAG, "projection_start_failed", it); stopSelfSafely() }
                        .onSuccess { watchSession() }
                }
            }
            ACTION_START_SYNTHETIC -> {
                // No MediaProjection is involved, so the service must NOT claim that type:
                // since Android 14 startForeground(mediaProjection) without a projection token
                // is a SecurityException, which is what crashed the app here.
                usesProjection = false
                goForeground(projecting = false)
                val pattern = runCatching { SyntheticPattern.valueOf(intent.getStringExtra(EXTRA_PATTERN) ?: "") }
                    .getOrDefault(SyntheticPattern.TEST_PATTERN)
                scope.launch {
                    runCatching { session.startSyntheticStreaming(pattern) }
                        .onFailure { MirrorLog.e(TAG, "synthetic_start_failed", it); stopSelfSafely() }
                        .onSuccess { watchSession() }
                }
            }
            ACTION_START_EXTENDED -> {
                // A second display is not screen capture, so no projection type here either.
                usesProjection = false
                goForeground(projecting = false, text = getString(R.string.notification_extended))
                scope.launch {
                    runCatching { session.startExtendedStreaming() }
                        .onFailure { MirrorLog.e(TAG, "extended_start_failed", it); stopSelfSafely() }
                        .onSuccess { watchSession() }
                }
            }
            ACTION_STOP -> {
                session.stopStreaming("notification")
                // Stay foreground if the link is still up; the watcher stops us once it is not.
                if (!session.isConnected) stopSelfSafely() else goForeground(projecting = false, text = getString(R.string.notification_connected))
            }
            else -> stopSelfSafely()
        }
        return START_NOT_STICKY
    }

    /**
     * The service outlives capture on purpose. Android ends the projection when the screen locks;
     * if the service stopped there too, the process would go to the background and the platform
     * would abort the control socket, which looked to the user like "connection lost" on unlock.
     * While a receiver link is up we stay foreground as a connected-device session instead.
     */
    private fun watchSession() {
        if (watching) return
        watching = true
        scope.launch {
            session.state.collectLatest { s ->
                when {
                    s is SenderState.Streaming || s is SenderState.Paused || s is SenderState.AwaitingCapturePermission ->
                        goForeground(projecting = usesProjection, text = getString(R.string.notification_text))
                    s is SenderState.Recovering -> goForeground(projecting = false, text = getString(R.string.notification_reconnecting))
                    s is SenderState.Ready -> goForeground(projecting = false, text = getString(R.string.notification_connected))
                    else -> stopSelfSafely()
                }
            }
        }
    }

    private var watching = false
    /** True only while a real MediaProjection session is running. */
    private var usesProjection = false

    private fun goForeground(projecting: Boolean = true, text: String = "") {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW))
        val body = text.ifBlank { getString(R.string.notification_text) }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, MirrorService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(if (projecting) R.string.notification_title else R.string.notification_title_connected))
            .setContentText(body)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.notification_stop), stop).build())
            .build()
        val type = if (projecting) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        try {
            startForeground(NOTIFICATION_ID, n, type)
        } catch (e: Exception) {
            // A type change must never take the session down. Fall back to the other declared
            // type, and if even that fails leave foreground rather than being killed for it.
            MirrorLog.e(TAG, "start_foreground_failed", e, "projecting" to projecting)
            val fallback = if (projecting) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            runCatching { startForeground(NOTIFICATION_ID, n, fallback) }
                .onFailure { MirrorLog.e(TAG, "start_foreground_fallback_failed", it); stopSelfSafely() }
        }
    }

    private fun stopSelfSafely() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
