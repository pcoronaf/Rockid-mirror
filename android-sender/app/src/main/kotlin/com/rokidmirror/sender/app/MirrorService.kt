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
        const val ACTION_STOP = "com.rokidmirror.sender.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_SOURCE_LABEL = "sourceLabel"
        private const val CHANNEL_ID = "mirroring"
        private const val NOTIFICATION_ID = 1

        fun startProjection(context: Context, resultCode: Int, data: Intent, sourceLabel: String) {
            val i = Intent(context, MirrorService::class.java).setAction(ACTION_START_PROJECTION)
                .putExtra(EXTRA_RESULT_CODE, resultCode).putExtra(EXTRA_RESULT_DATA, data).putExtra(EXTRA_SOURCE_LABEL, sourceLabel)
            context.startForegroundService(i)
        }

        fun startSynthetic(context: Context) {
            context.startForegroundService(Intent(context, MirrorService::class.java).setAction(ACTION_START_SYNTHETIC))
        }

        fun stop(context: Context) { context.startService(Intent(context, MirrorService::class.java).setAction(ACTION_STOP)) }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val session by lazy { MirrorApplication.from(application).session }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_PROJECTION -> {
                goForeground()
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
                @Suppress("DEPRECATION") val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                val label = intent.getStringExtra(EXTRA_SOURCE_LABEL) ?: "Phone"
                if (data == null || resultCode == Int.MIN_VALUE) { stopSelfSafely(); return START_NOT_STICKY }
                scope.launch {
                    runCatching { session.startProjectionStreaming(resultCode, data, label) }
                        .onFailure { MirrorLog.e(TAG, "projection_start_failed", it); stopSelfSafely() }
                }
                watchSession()
            }
            ACTION_START_SYNTHETIC -> {
                goForeground()
                scope.launch { runCatching { session.startSyntheticStreaming() }.onFailure { MirrorLog.e(TAG, "synthetic_start_failed", it); stopSelfSafely() } }
                watchSession()
            }
            ACTION_STOP -> { session.stopStreaming("notification"); stopSelfSafely() }
            else -> stopSelfSafely()
        }
        return START_NOT_STICKY
    }

    private fun watchSession() {
        scope.launch {
            session.state.collectLatest { s ->
                val alive = s is SenderState.Streaming || s is SenderState.Paused || s is SenderState.Recovering || s is SenderState.AwaitingCapturePermission
                if (!alive) stopSelfSafely()
            }
        }
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, MirrorService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.notification_stop), stop).build())
            .build()
        startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
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
