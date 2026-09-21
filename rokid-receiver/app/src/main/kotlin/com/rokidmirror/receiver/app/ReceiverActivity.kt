package com.rokidmirror.receiver.app

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import com.rokidmirror.protocol.VideoCodec
import com.rokidmirror.protocol.video.EncodedAccessUnit
import com.rokidmirror.protocol.viewport.ViewportState
import com.rokidmirror.receiver.BuildConfig
import com.rokidmirror.receiver.control.SessionDecoder
import com.rokidmirror.receiver.control.SessionOverlay
import com.rokidmirror.receiver.control.SessionSurface
import com.rokidmirror.receiver.decoder.DecodeResult
import com.rokidmirror.receiver.decoder.DecoderFormat
import com.rokidmirror.receiver.decoder.MediaCodecVideoDecoder
import com.rokidmirror.receiver.platform.AndroidPlatformAdapter
import com.rokidmirror.receiver.platform.DisplayInfo
import com.rokidmirror.receiver.platform.RokidPlatformAdapter
import com.rokidmirror.receiver.renderer.OverlayView
import com.rokidmirror.receiver.renderer.ViewportRenderer
import com.rokidmirror.receiver.telemetry.ReceiverLog

/**
 * The receiver's window. It owns pixels, input and the decoder, and lends them to
 * [ReceiverService], which owns the link and outlives this Activity. Closing or backgrounding
 * this window no longer ends the session.
 */
class ReceiverActivity : Activity() {
    private companion object { const val TAG = "Activity" }

    private lateinit var platform: AndroidPlatformAdapter
    private lateinit var renderer: ViewportRenderer
    private lateinit var overlay: OverlayView
    private var decoder: MediaCodecVideoDecoder? = null
    private var service: ReceiverService? = null
    private var surfaceHolder: SurfaceHolder? = null
    private var pendingFormat: DecoderFormat? = null
    private var attached = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        platform = AndroidPlatformAdapter(this)
        val display = runCatching { platform.getDisplayInfo() }.getOrElse { DisplayInfo(480, 640, 240, 60f) }
        val root = FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        val host = FrameLayout(this).apply { clipChildren = true }
        renderer = ViewportRenderer(host, platform.createRenderTarget(), display)
        overlay = OverlayView(this).apply { debugEnabled = BuildConfig.DEBUG }
        root.addView(host, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(overlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)
        overlay.setStatus("Rokid Mirror starting…")
        (application as ReceiverApplication).consumeLastCrash()?.let { overlay.setWarning("Previous crash: $it") }
        runCatching { platform.setKeepAwake(true) }
        runCatching { hideSystemBars() }

        renderer.onSurfaceReady = { holder ->
            surfaceHolder = holder
            pendingFormat?.let { format ->
                val decoder = decoder ?: return@let
                if (decoder.configure(format, holder.surface)) service?.session?.onDecoderReady()
                else service?.session?.onDecoderError("configuration failed after surface creation")
            }
        }
        renderer.onSurfaceLost = { surfaceHolder = null; decoder?.stop() }

        ReceiverService.start(this)
        bindService(Intent(this, ReceiverService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStart() {
        super.onStart()
        attachIfReady()
    }

    override fun onStop() {
        service?.let { if (attached) { it.detach(displayTarget); attached = false } }
        super.onStop()
    }

    override fun onDestroy() {
        service?.onExit = null
        runCatching { unbindService(connection) }
        decoder?.stop()
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = platform.onKeyEvent(event) || super.dispatchKeyEvent(event)

    override fun dispatchTouchEvent(event: MotionEvent): Boolean = platform.onTouchEvent(event) || super.dispatchTouchEvent(event)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val bound = (binder as? ReceiverService.LocalBinder)?.service ?: return
            service = bound
            bound.onExit = { runOnUiThread { ReceiverLog.i(TAG, "exit"); finish() } }
            decoder = MediaCodecVideoDecoder(bound.stats, onError = { message -> bound.session.onDecoderError(message) })
            ReceiverLog.i(TAG, "service_bound")
            attachIfReady()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            attached = false
            service = null
        }
    }

    private fun attachIfReady() {
        val bound = service ?: return
        if (attached) return
        attached = true
        bound.attach(displayTarget)
    }

    private fun hideSystemBars() {
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    private val displayTarget = object : DisplayTarget {
        override val platform: RokidPlatformAdapter get() = this@ReceiverActivity.platform

        override val surface = object : SessionSurface {
            override fun setSource(width: Int, height: Int) = renderer.setSource(width, height)
            override fun sourceToDisplay(nx: Float, ny: Float): Pair<Float, Float>? = renderer.sourceToDisplay(nx, ny)
            override fun hideVideo() = renderer.hideVideo()
            override fun setViewport(state: ViewportState) = renderer.setViewport(state)
            override val currentViewport: ViewportState get() = renderer.viewport
            override val sourceWidth: Int get() = renderer.sourceWidth
            override val sourceHeight: Int get() = renderer.sourceHeight
            override val displayWidth: Int get() = renderer.displayWidth
            override val displayHeight: Int get() = renderer.displayHeight
        }

        override val decoder = object : SessionDecoder {
            override fun configure(codec: VideoCodec, width: Int, height: Int, csd0: ByteArray?, csd1: ByteArray?): Boolean {
                val format = DecoderFormat(codec, width, height, csd0, csd1)
                pendingFormat = format
                val holder = surfaceHolder ?: return false
                return this@ReceiverActivity.decoder?.configure(format, holder.surface) ?: false
            }
            override val surfaceReady: Boolean get() = surfaceHolder != null && this@ReceiverActivity.decoder != null
            override fun submit(unit: EncodedAccessUnit): Boolean = this@ReceiverActivity.decoder?.submit(unit) == DecodeResult.QUEUED
            override fun stop() { pendingFormat = null; this@ReceiverActivity.decoder?.stop() }
            override val isConfigured: Boolean get() = this@ReceiverActivity.decoder?.isConfigured ?: false
            override val name: String get() = this@ReceiverActivity.decoder?.decoderName ?: "-"
        }

        override val overlay = object : SessionOverlay {
            override var chromeVisible: Boolean
                get() = this@ReceiverActivity.overlay.chromeVisible
                set(value) { this@ReceiverActivity.overlay.chromeVisible = value }
            override fun setStatus(text: String) { this@ReceiverActivity.overlay.setStatus(text) }
            override fun setInfo(text: String) { this@ReceiverActivity.overlay.setInfo(text) }
            override fun setPointer(x: Float?, y: Float?, pressed: Boolean) { this@ReceiverActivity.overlay.setPointer(x, y, pressed) }
            override fun showPairingCode(formatted: String?) { this@ReceiverActivity.overlay.showPairingCode(formatted) }
            override fun setWarning(text: String?) { this@ReceiverActivity.overlay.setWarning(text) }
            override fun setDebug(text: String?) { this@ReceiverActivity.overlay.setDebug(text) }
            override fun setHint(text: String?) { this@ReceiverActivity.overlay.setHint(text) }
            override fun setStreamingIndicator(on: Boolean) { this@ReceiverActivity.overlay.setStreamingIndicator(on) }
        }
    }
}
