package com.rokidmirror.receiver.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import com.rokidmirror.protocol.Protocol
import com.rokidmirror.protocol.control.ControlMessage
import com.rokidmirror.protocol.control.ErrorCode
import com.rokidmirror.protocol.control.Payloads
import com.rokidmirror.protocol.video.EncodedAccessUnit
import com.rokidmirror.protocol.video.ReassemblyStats
import com.rokidmirror.protocol.viewport.ViewportState
import com.rokidmirror.receiver.BuildConfig
import com.rokidmirror.receiver.R
import com.rokidmirror.receiver.control.ReceiverSession
import com.rokidmirror.receiver.control.SessionDecoder
import com.rokidmirror.receiver.control.SessionOverlay
import com.rokidmirror.receiver.control.SessionPreview
import com.rokidmirror.receiver.control.SessionSurface
import com.rokidmirror.receiver.control.SessionVision
import com.rokidmirror.receiver.control.SessionTransport
import com.rokidmirror.receiver.platform.ContextPlatformInfo
import com.rokidmirror.receiver.platform.InputCapabilities
import com.rokidmirror.receiver.platform.PlatformCapabilities
import com.rokidmirror.receiver.platform.RokidPlatformAdapter
import com.rokidmirror.receiver.platform.Support
import com.rokidmirror.receiver.telemetry.PipelineStats
import com.rokidmirror.receiver.telemetry.ReceiverLog
import com.rokidmirror.receiver.transport.NsdAdvertiser
import com.rokidmirror.receiver.transport.ReceiverTransport
import com.rokidmirror.receiver.transport.ReceiverTransportListener
import com.rokidmirror.protocol.VideoCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Everything the Activity contributes while it has a window: pixels, input and the decoder. */
/** One snapshot of the glasses' display, plus what it actually shows. */
class PreviewSnapshot(val bitmap: android.graphics.Bitmap, val kind: String)

interface DisplayTarget {
    val surface: SessionSurface
    val decoder: SessionDecoder
    val vision: SessionVision
    /**
     * Draws what the wearer sees into a bitmap. Must run on the main thread. Returns null when
     * there is nothing to show. A decoded video frame cannot be read back from the decoder's
     * surface, so during mirroring this carries the overlay and the phone draws the rest.
     */
    fun drawPreview(maxWidth: Int): PreviewSnapshot?
    val overlay: SessionOverlay
    val platform: RokidPlatformAdapter
}

/**
 * Keeps the receiver alive independently of its window.
 *
 * The glasses' launcher, a sleep or any other app taking the foreground used to end the session:
 * the socket closed, the phone saw a lost link, and pairing had to start over. The network side,
 * the advertisement and the session state now live here, in a foreground service, and the
 * Activity only lends its surface while it is visible. Video can obviously only be displayed
 * while there is a window, so a stream arriving with no window brings the Activity forward.
 */
class ReceiverService : Service() {
    companion object {
        private const val TAG = "Service"
        private const val CHANNEL_ID = "receiver"
        private const val NOTIFICATION_ID = 42
        const val ACTION_STOP = "com.rokidmirror.receiver.STOP"

        fun start(context: Context) {
            val intent = Intent(context, ReceiverService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ReceiverService::class.java).setAction(ACTION_STOP))
        }
    }

    inner class LocalBinder : Binder() { val service: ReceiverService get() = this@ReceiverService }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val stats = PipelineStats()
    private val platforms = MutableStateFlow<RokidPlatformAdapter?>(null)

    @Volatile
    private var target: DisplayTarget? = null

    /** Set by the Activity so an exit from the glasses closes the window as well as the link. */
    @Volatile
    var onExit: (() -> Unit)? = null

    lateinit var session: ReceiverSession; private set
    private lateinit var transport: ReceiverTransport
    private lateinit var advertiser: NsdAdvertiser
    lateinit var capabilities: Payloads.Capabilities; private set
    var bootInfo: String = ""; private set
    private var started = false

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        goForeground()
        val app = application as ReceiverApplication
        val display = ContextPlatformInfo.displayInfo(this)
        val platform = ContextPlatformInfo.describe()
        val sensors = ContextPlatformInfo.sensors(this)
        val probe = PlatformCapabilities.probeDecoder()
        capabilities = PlatformCapabilities.capabilities(display, platform, InputCapabilities(Support.UNVERIFIED, Support.UNVERIFIED), sensors, probe)
        bootInfo = buildString {
            append("v${ReceiverApplication.VERSION}  ${display.width}x${display.height} @${display.densityDpi}dpi ${display.refreshHz}Hz  api ${platform.apiLevel} ${platform.abi}\n")
            append(if (probe == null) "NO H.264 DECODER" else "dec ${probe.name.takeLast(22)} hw=${probe.hardwareAccelerated} ll=${probe.lowLatency} max ${capabilities.maxDecode.width}x${capabilities.maxDecode.height}@${capabilities.maxDecode.fps}")
            append("\nimu=${sensors.rotationVector}")
        }
        ReceiverLog.i(TAG, "service_created", "display" to "${display.width}x${display.height}", "decoder" to probe?.name, "hw" to probe?.hardwareAccelerated)

        transport = ReceiverTransport(app.identity, app.credentials, { capabilities }, transportListener)
        session = ReceiverSession(
            platforms = platforms,
            surface = surfaceBridge, decoder = decoderBridge, vision = visionBridge, preview = previewBridge,
            transport = transportBridge, overlay = overlayBridge,
            stats = stats, scope = scope, debugOverlay = BuildConfig.DEBUG,
            onForgetAllSenders = { app.credentials.forgetAll() },
            onExitRequested = { stopEverything() },
        )
        advertiser = NsdAdvertiser(this)
        session.start()
        runCatching { transport.start() }.onFailure { ReceiverLog.e(TAG, "transport_start_failed", it) }
        runCatching {
            advertiser.start(app.identity.receiverId, app.identity.receiverName, Protocol.DEFAULT_CONTROL_PORT, display.width, display.height)
        }.onFailure { ReceiverLog.e(TAG, "advertise_failed", it) }
        started = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopEverything(); return START_NOT_STICKY }
        goForeground()
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { advertiser.stop() }
        runCatching { transport.stop() }
        runCatching { session.stop() }
        scope.cancel()
        ReceiverLog.i(TAG, "service_destroyed")
        super.onDestroy()
    }

    // ---- attachment from the Activity --------------------------------------------------

    fun attach(displayTarget: DisplayTarget) {
        target = displayTarget
        platforms.value = displayTarget.platform
        overlayBridge.setInfo(bootInfo + "\ntcp ${Protocol.DEFAULT_CONTROL_PORT} udp ${Protocol.DEFAULT_VIDEO_PORT}  mdns=${advertiser.available}\n" + localAddresses())
        session.onDisplayAttached()
        ReceiverLog.i(TAG, "display_attached")
    }

    fun detach(displayTarget: DisplayTarget) {
        if (target !== displayTarget) return
        previewJob?.cancel()
        runCatching { displayTarget.vision.setEnabled(false) }
        target = null
        platforms.value = null
        session.onDisplayDetached()
        ReceiverLog.i(TAG, "display_detached")
    }

    /** Explicit exit: drop the link and the notification, and close the window if it is up. */
    private fun stopEverything() {
        runCatching { onExit?.invoke() }
        runCatching { advertiser.stop() }
        runCatching { transport.stop() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** A stream with nowhere to draw: ask the platform to show our window again. */
    private fun requestUi() {
        if (target != null) return
        val intent = Intent(this, ReceiverActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        runCatching { startActivity(intent) }
            .onSuccess { ReceiverLog.i(TAG, "ui_requested") }
            .onFailure { ReceiverLog.w(TAG, "ui_request_blocked", "error" to it.message) }
    }

    private fun localAddresses(): String = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { ni -> ni.inetAddresses.toList().filterIsInstance<java.net.Inet4Address>().map { "${ni.name} ${it.hostAddress}" } }
            .joinToString("  ").ifBlank { "no IPv4 address (not on Wi-Fi?)" }
    }.getOrElse { "address unknown" }

    private fun goForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, ReceiverActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, ReceiverService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.service_title))
            .setContentText(getString(R.string.service_text))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.service_stop), stop).build())
            .build()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure { ReceiverLog.e(TAG, "start_foreground_failed", it) }
    }

    // ---- bridges: forward to the attached display, or absorb while there is none -------

    private val transportListener = object : ReceiverTransportListener {
        override fun onListening(controlPort: Int, videoPort: Int) = session.onListening(controlPort)
        override fun onSenderConnecting(senderName: String) = session.onSenderConnecting(senderName)
        override fun onShowPairingCode(code: String) { requestUi(); session.onShowPairingCode(code) }
        override fun onPairingFailed(reason: String) = session.onPairingFailed(reason)
        override fun onConnected(senderName: String, sessionId: String) = session.onConnected(senderName, sessionId)
        override fun onControlMessage(message: ControlMessage) = session.onControlMessage(message)
        override fun onAccessUnit(unit: EncodedAccessUnit, firstPacketNs: Long, completeNs: Long) = session.onAccessUnit(unit, firstPacketNs, completeNs)
        override fun onReassemblyStats(stats: ReassemblyStats) = session.onReassemblyStats(stats)
        override fun onDisconnected(reason: String) = session.onDisconnected(reason)
    }

    private val surfaceBridge = object : SessionSurface {
        override fun setSource(width: Int, height: Int) {
            if (target == null) requestUi()
            target?.surface?.setSource(width, height)
        }
        override fun sourceToDisplay(nx: Float, ny: Float) = target?.surface?.sourceToDisplay(nx, ny)
        override fun hideVideo() { target?.surface?.hideVideo() }
        override fun setViewport(state: ViewportState) { target?.surface?.setViewport(state) }
        override val currentViewport: ViewportState get() = target?.surface?.currentViewport ?: ViewportState()
        override val sourceWidth: Int get() = target?.surface?.sourceWidth ?: 0
        override val sourceHeight: Int get() = target?.surface?.sourceHeight ?: 0
        override val displayWidth: Int get() = target?.surface?.displayWidth ?: capabilities.display.width
        override val displayHeight: Int get() = target?.surface?.displayHeight ?: capabilities.display.height
    }

    private val decoderBridge = object : SessionDecoder {
        override fun configure(codec: VideoCodec, width: Int, height: Int, csd0: ByteArray?, csd1: ByteArray?): Boolean =
            target?.decoder?.configure(codec, width, height, csd0, csd1) ?: false
        override val surfaceReady: Boolean get() = target?.decoder?.surfaceReady ?: false
        override fun submit(unit: EncodedAccessUnit): Boolean = target?.decoder?.submit(unit) ?: false
        override fun stop() { target?.decoder?.stop() }
        override val isConfigured: Boolean get() = target?.decoder?.isConfigured ?: false
        override val name: String get() = target?.decoder?.name ?: "-"
    }

    private val visionBridge = object : SessionVision {
        override val available: Boolean get() = target?.vision?.available ?: false
        override val running: Boolean get() = target?.vision?.running ?: false
        override fun setEnabled(enabled: Boolean) {
            if (enabled && target == null) { requestUi(); return }
            target?.vision?.setEnabled(enabled)
        }
        override fun setGain(gain: Float) { target?.vision?.setGain(gain) }
    }

    private var previewJob: kotlinx.coroutines.Job? = null

    private val previewBridge = object : SessionPreview {
        override fun setEnabled(enabled: Boolean, fps: Int, maxWidth: Int, quality: Int) {
            previewJob?.cancel()
            if (!enabled) return
            previewJob = scope.launch { previewLoop(fps, maxWidth, quality) }
        }
    }

    /**
     * Snapshots are drawn on the main thread and compressed off it, at a few frames a second:
     * enough for the phone to see what the wearer sees without competing with the video stream.
     */
    private suspend fun previewLoop(fps: Int, maxWidth: Int, quality: Int) {
        val interval = com.rokidmirror.receiver.renderer.PreviewScaling.intervalMs(fps)
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            val snapshot = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                runCatching { target?.drawPreview(maxWidth) }.getOrNull()
            }
            if (snapshot != null) {
                val encoded = encodeWithinBudget(snapshot.bitmap, quality, maxWidth)
                if (encoded != null) {
                    transport.send(
                        com.rokidmirror.protocol.control.MessageType.PREVIEW_FRAME,
                        Payloads.PreviewFrame.serializer(),
                        Payloads.PreviewFrame(encoded, snapshot.bitmap.width, snapshot.bitmap.height, snapshot.kind, System.nanoTime()),
                    )
                }
            }
            kotlinx.coroutines.delay(interval)
        }
    }

    /**
     * Encodes a snapshot small enough to send, trying lower quality and then a smaller picture.
     * A snapshot that cannot fit is skipped: it would exceed the control frame limit, and the
     * first version of this cost the link every time it tried.
     */
    private fun encodeWithinBudget(bitmap: android.graphics.Bitmap, quality: Int, maxWidth: Int): String? {
        for ((attemptQuality, attemptWidth) in com.rokidmirror.receiver.renderer.PreviewScaling.attempts(quality, maxWidth)) {
            val source = if (attemptWidth >= bitmap.width) bitmap else runCatching {
                val (w, h) = com.rokidmirror.receiver.renderer.PreviewScaling.scaledSize(bitmap.width, bitmap.height, attemptWidth)
                android.graphics.Bitmap.createScaledBitmap(bitmap, w, h, true)
            }.getOrNull() ?: continue
            val encoded = runCatching {
                val stream = java.io.ByteArrayOutputStream()
                source.compress(android.graphics.Bitmap.CompressFormat.JPEG, attemptQuality, stream)
                android.util.Base64.encodeToString(stream.toByteArray(), android.util.Base64.NO_WRAP)
            }.getOrNull()
            if (source !== bitmap) source.recycle()
            if (encoded != null && encoded.length <= Protocol.MAX_PREVIEW_PAYLOAD_BYTES) return encoded
        }
        ReceiverLog.w(TAG, "preview_skipped_too_large")
        return null
    }

    private val transportBridge = object : SessionTransport {
        override fun requestKeyframe(reason: String) = transport.requestKeyframe(reason)
        override fun sendStats(s: Payloads.Stats) = transport.sendStats(s)
        override fun sendError(code: ErrorCode, message: String) = transport.sendError(code, message)
        override fun ping() = transport.ping()
        override fun requireKeyframe() = transport.requireKeyframe()
        override fun disconnect(reason: String) = transport.disconnectCurrent(reason)
        override val clockOffsetNs: Long get() = transport.clockSync.offsetNs
        override val rttNs: Long get() = transport.clockSync.lastRttNs.coerceAtLeast(0)
    }

    private val overlayBridge = object : SessionOverlay {
        override var chromeVisible: Boolean
            get() = target?.overlay?.chromeVisible ?: true
            set(value) { target?.overlay?.chromeVisible = value }
        override fun setStatus(text: String) { target?.overlay?.setStatus(text) }
        override fun setInfo(text: String) { target?.overlay?.setInfo(text) }
        override fun setPointer(x: Float?, y: Float?, pressed: Boolean) { target?.overlay?.setPointer(x, y, pressed) }
        override fun showPairingCode(formatted: String?) { target?.overlay?.showPairingCode(formatted) }
        override fun setWarning(text: String?) { target?.overlay?.setWarning(text) }
        override fun setDebug(text: String?) { target?.overlay?.setDebug(text) }
        override fun setHint(text: String?) { target?.overlay?.setHint(text) }
        override fun setStreamingIndicator(on: Boolean) { target?.overlay?.setStreamingIndicator(on) }
    }
}
