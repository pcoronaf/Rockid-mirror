package com.rokidmirror.receiver.app

import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import com.rokidmirror.protocol.VideoCodec
import com.rokidmirror.receiver.BuildConfig
import com.rokidmirror.protocol.control.ControlMessage
import com.rokidmirror.protocol.control.ErrorCode
import com.rokidmirror.protocol.control.Payloads
import com.rokidmirror.protocol.video.EncodedAccessUnit
import com.rokidmirror.protocol.video.ReassemblyStats
import com.rokidmirror.protocol.viewport.ViewportState
import com.rokidmirror.receiver.control.ReceiverSession
import com.rokidmirror.receiver.control.SessionDecoder
import com.rokidmirror.receiver.control.SessionOverlay
import com.rokidmirror.receiver.control.SessionSurface
import com.rokidmirror.receiver.control.SessionTransport
import com.rokidmirror.receiver.decoder.DecodeResult
import com.rokidmirror.receiver.decoder.DecoderFormat
import com.rokidmirror.receiver.decoder.MediaCodecVideoDecoder
import com.rokidmirror.receiver.platform.AndroidPlatformAdapter
import com.rokidmirror.receiver.platform.PlatformCapabilities
import com.rokidmirror.receiver.renderer.OverlayView
import com.rokidmirror.receiver.renderer.ViewportRenderer
import com.rokidmirror.receiver.telemetry.PipelineStats
import com.rokidmirror.receiver.telemetry.ReceiverLog
import com.rokidmirror.receiver.transport.NsdAdvertiser
import com.rokidmirror.receiver.transport.ReceiverTransport
import com.rokidmirror.receiver.transport.ReceiverTransportListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Single full-screen activity for the glasses. Composition root for the receiver: platform
 * adapter -> renderer -> decoder -> transport -> session. Runs only while visible (M0/M1
 * scope); the decoder holds the SurfaceView's surface.
 */
class ReceiverActivity : Activity() {
    private companion object { const val TAG = "Activity" }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var platform: AndroidPlatformAdapter
    private lateinit var renderer: ViewportRenderer
    private lateinit var overlay: OverlayView
    private lateinit var decoder: MediaCodecVideoDecoder
    private lateinit var transport: ReceiverTransport
    private lateinit var session: ReceiverSession
    private lateinit var advertiser: NsdAdvertiser
    private val stats = PipelineStats()
    private var surfaceHolder: SurfaceHolder? = null
    private var pendingFormat: DecoderFormat? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as ReceiverApplication
        platform = AndroidPlatformAdapter(this)
        platform.setKeepAwake(true)
        hideSystemBars()

        val display = platform.getDisplayInfo()
        val root = FrameLayout(this)
        val host = FrameLayout(this).apply { clipChildren = true }
        renderer = ViewportRenderer(host, platform.createRenderTarget(), display)
        overlay = OverlayView(this).apply { debugEnabled = BuildConfig.DEBUG }
        root.addView(host, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(overlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)

        decoder = MediaCodecVideoDecoder(stats, onError = { msg -> session.onDecoderError(msg) })
        val probe = PlatformCapabilities.probeDecoder()
        val capabilities = PlatformCapabilities.capabilities(display, platform.describe(), platform.getInputCapabilities(), platform.getSensorCapabilities(), probe)
        ReceiverLog.i(TAG, "platform", "model" to platform.describe().model, "os" to platform.describe().os, "display" to "${display.width}x${display.height}@${display.refreshHz}", "decoder" to probe?.name, "hw" to probe?.hardwareAccelerated, "lowLatency" to probe?.lowLatency, "maxDecode" to capabilities.maxDecode)

        transport = ReceiverTransport(app.identity, app.credentials, { capabilities }, transportListener)
        session = ReceiverSession(
            platform = platform,
            surface = surfaceBridge, decoder = decoderBridge, transport = transportBridge, overlay = overlayBridge,
            stats = stats, scope = scope, debugOverlay = BuildConfig.DEBUG,
            onForgetAllSenders = { app.credentials.forgetAll() },
        )
        renderer.onSurfaceReady = { holder -> surfaceHolder = holder; pendingFormat?.let { decoder.configure(it, holder.surface) } }
        renderer.onSurfaceLost = { surfaceHolder = null; decoder.stop() }
        advertiser = NsdAdvertiser(this)
    }

    override fun onStart() {
        super.onStart()
        val app = application as ReceiverApplication
        session.start()
        runCatching { transport.start() }.onFailure { ReceiverLog.e(TAG, "transport_start_failed", it); overlay.setWarning("Cannot open ports: ${it.message}") }
        val d = platform.getDisplayInfo()
        advertiser.start(app.identity.receiverId, app.identity.receiverName, com.rokidmirror.protocol.Protocol.DEFAULT_CONTROL_PORT, d.width, d.height)
    }

    override fun onStop() {
        advertiser.stop()
        transport.stop()
        session.stop()
        super.onStop()
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = platform.onKeyEvent(event) || super.dispatchKeyEvent(event)

    private fun hideSystemBars() {
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    // ---- bridges between modules (kept here so modules stay independent of each other) -------

    private val transportListener = object : ReceiverTransportListener {
        override fun onListening(controlPort: Int, videoPort: Int) = session.onListening(controlPort)
        override fun onSenderConnecting(senderName: String) = session.onSenderConnecting(senderName)
        override fun onShowPairingCode(code: String) = session.onShowPairingCode(code)
        override fun onPairingFailed(reason: String) = session.onPairingFailed(reason)
        override fun onConnected(senderName: String, sessionId: String) = session.onConnected(senderName, sessionId)
        override fun onControlMessage(message: ControlMessage) = session.onControlMessage(message)
        override fun onAccessUnit(unit: EncodedAccessUnit, firstPacketNs: Long, completeNs: Long) = session.onAccessUnit(unit, firstPacketNs, completeNs)
        override fun onReassemblyStats(stats: ReassemblyStats) = session.onReassemblyStats(stats)
        override fun onDisconnected(reason: String) = session.onDisconnected(reason)
    }

    private val surfaceBridge = object : SessionSurface {
        override fun setSource(width: Int, height: Int) = renderer.setSource(width, height)
        override fun setViewport(state: ViewportState) = renderer.setViewport(state)
        override val currentViewport: ViewportState get() = renderer.viewport
        override val sourceWidth: Int get() = renderer.sourceWidth
        override val sourceHeight: Int get() = renderer.sourceHeight
        override val displayWidth: Int get() = renderer.displayWidth
        override val displayHeight: Int get() = renderer.displayHeight
    }

    private val decoderBridge = object : SessionDecoder {
        override fun configure(codec: VideoCodec, width: Int, height: Int, csd0: ByteArray?, csd1: ByteArray?): Boolean {
            val f = DecoderFormat(codec, width, height, csd0, csd1)
            pendingFormat = f
            val holder = surfaceHolder ?: return false
            return decoder.configure(f, holder.surface)
        }
        override fun submit(unit: EncodedAccessUnit): Boolean = decoder.submit(unit) == DecodeResult.QUEUED
        override fun stop() { pendingFormat = null; decoder.stop() }
        override val isConfigured: Boolean get() = decoder.isConfigured
        override val name: String get() = decoder.decoderName
    }

    private val transportBridge = object : SessionTransport {
        override fun requestKeyframe(reason: String) = transport.requestKeyframe(reason)
        override fun sendStats(stats: Payloads.Stats) = transport.sendStats(stats)
        override fun sendError(code: ErrorCode, message: String) = transport.sendError(code, message)
        override fun ping() = transport.ping()
        override fun requireKeyframe() = transport.requireKeyframe()
        override fun disconnect(reason: String) = transport.disconnectCurrent(reason)
        override val clockOffsetNs: Long get() = transport.clockSync.offsetNs
        override val rttNs: Long get() = transport.clockSync.lastRttNs.coerceAtLeast(0)
    }

    private val overlayBridge = object : SessionOverlay {
        override fun setStatus(text: String) { overlay.setStatus(text) }
        override fun showPairingCode(formatted: String?) { overlay.showPairingCode(formatted) }
        override fun setWarning(text: String?) { overlay.setWarning(text) }
        override fun setDebug(text: String?) { overlay.setDebug(text) }
        override fun setHint(text: String?) { overlay.setHint(text) }
        override fun setStreamingIndicator(on: Boolean) { overlay.setStreamingIndicator(on) }
    }
}
