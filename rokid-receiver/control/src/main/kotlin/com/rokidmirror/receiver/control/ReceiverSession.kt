package com.rokidmirror.receiver.control

import com.rokidmirror.protocol.Protocol
import com.rokidmirror.protocol.VideoCodec
import com.rokidmirror.protocol.control.ControlCodec
import com.rokidmirror.protocol.control.ControlMessage
import com.rokidmirror.protocol.control.ErrorCode
import com.rokidmirror.protocol.control.MessageType
import com.rokidmirror.protocol.control.Payloads
import com.rokidmirror.protocol.crypto.PairingCommitment
import com.rokidmirror.protocol.video.EncodedAccessUnit
import com.rokidmirror.protocol.video.ReassemblyStats
import com.rokidmirror.protocol.viewport.FitMode
import com.rokidmirror.protocol.viewport.ViewportMath
import com.rokidmirror.protocol.viewport.ViewportState
import com.rokidmirror.receiver.platform.GlassesInput
import com.rokidmirror.receiver.platform.RokidPlatformAdapter
import com.rokidmirror.receiver.telemetry.PipelineStats
import com.rokidmirror.receiver.telemetry.ReceiverLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Base64

/** Receiver-side abstractions the session drives; implemented by renderer/decoder/transport modules. */
interface SessionSurface {
    fun setSource(width: Int, height: Int)
    /** Normalized source coordinates to display pixels, or null when nothing is shown. */
    fun sourceToDisplay(nx: Float, ny: Float): Pair<Float, Float>?
    /** Hide the video surface so the overlay is unobstructed while nothing is streaming. */
    fun hideVideo()
    fun setViewport(state: ViewportState)
    val currentViewport: ViewportState
    val sourceWidth: Int
    val sourceHeight: Int
    val displayWidth: Int
    val displayHeight: Int
}

interface SessionDecoder {
    fun configure(codec: VideoCodec, width: Int, height: Int, csd0: ByteArray?, csd1: ByteArray?): Boolean
    /**
     * False while the render surface has not been created yet. Configuration is then deferred
     * and runs from the surface callback, which is not an error.
     */
    val surfaceReady: Boolean
    fun submit(unit: EncodedAccessUnit): Boolean
    fun stop()
    val isConfigured: Boolean
    val name: String
}

interface SessionTransport {
    fun requestKeyframe(reason: String)
    fun sendStats(stats: Payloads.Stats)
    fun sendError(code: ErrorCode, message: String)
    fun ping()
    fun requireKeyframe()
    fun disconnect(reason: String)
    val clockOffsetNs: Long
    val rttNs: Long
}

/** Vision mode, owned by the window because it needs a camera and somewhere to draw. */
interface SessionVision {
    val available: Boolean
    val running: Boolean
    fun setEnabled(enabled: Boolean)
    fun setGain(gain: Float)
}

/** Sends snapshots of the glasses' display back to the phone; owned by the service. */
interface SessionPreview {
    fun setEnabled(enabled: Boolean, fps: Int, maxWidth: Int, quality: Int)
}

interface SessionOverlay {
    /** Status, info and hint text; warnings and the pairing code are never hidden. */
    var chromeVisible: Boolean
    fun setStatus(text: String)
    fun setInfo(text: String)
    fun setPointer(x: Float?, y: Float?, pressed: Boolean)
    fun showPairingCode(formatted: String?)
    fun setWarning(text: String?)
    fun setDebug(text: String?)
    fun setHint(text: String?)
    fun setStreamingIndicator(on: Boolean)
}

/**
 * Receiver state machine + glue: reacts to control messages, feeds the decoder, drives the
 * viewport (phone commands, touch bar, optional head motion) and reports STATS every second.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ReceiverSession(
    /** The Activity's adapter while one is attached, null while the app has no window. */
    private val platforms: StateFlow<RokidPlatformAdapter?>,
    private val surface: SessionSurface,
    private val decoder: SessionDecoder,
    private val vision: SessionVision,
    private val preview: SessionPreview,
    private val transport: SessionTransport,
    private val overlay: SessionOverlay,
    private val stats: PipelineStats,
    private val scope: CoroutineScope,
    private val debugOverlay: Boolean,
    private val onForgetAllSenders: () -> Unit,
    /** Double tap on the temple bar leaves the app, matching other Rokid apps. */
    private val onExitRequested: () -> Unit = {},
    private val clockNs: () -> Long = System::nanoTime,
) {
    private companion object {
        const val TAG = "Session"
        const val KEYFRAME_REQUEST_MIN_INTERVAL_NS = 300_000_000L
        const val STALL_WARNING_SECONDS = 3
        /** How long the heads-up text stays on screen after something changes. */
        const val CHROME_VISIBLE_MS = 4000L
    }

    enum class State { ADVERTISING, PAIRING, CONNECTED, STREAMING, RECONNECT_WAIT }

    var state: State = State.ADVERTISING; private set
    private var senderName = ""
    private var sourceName = ""
    private var streamFormat: Payloads.StreamFormat? = null
    private var lastKeyframeRequestNs = 0L
    private var statsJob: Job? = null
    private var headJob: Job? = null
    private var baseViewport = ViewportState()
    private var pointerVisible = false
    private var lastReassembly: ReassemblyStats? = null
    private var lastFramesDecoded = -1L
    private var stalledSeconds = 0
    private var chromeJob: Job? = null
    private var framesSubmitted = 0L
    private var framesDroppedByDecoder = 0L
    val head = HeadViewportController(enabled = false)

    init { enter(State.ADVERTISING) }

    fun start() {
        statsJob?.cancel()
        statsJob = scope.launch {
            while (isActive) {
                delay(Protocol.PING_INTERVAL_MS)
                if (state == State.STREAMING || state == State.CONNECTED) {
                    transport.ping()
                    val s = stats.snapshot(clockNs(), transport.clockOffsetNs)
                    transport.sendStats(s)
                    updateDebug(s)
                    if (state == State.STREAMING) watchForStall(s) else { stalledSeconds = 0; overlay.setWarning(null) }
                }
            }
        }
        scope.launch { platforms.flatMapLatest { it?.inputEvents() ?: emptyFlow() }.collect { onInput(it) } }
        // Surfacing raw codes on the glasses is the only practical way to learn what the temple
        // bar actually sends on this hardware.
        scope.launch {
            platforms.flatMapLatest { it?.rawInput() ?: emptyFlow() }.collect { description ->
                ReceiverLog.i(TAG, "raw_input", "event" to description)
                if (debugOverlay) { showChrome(); overlay.setHint(description) }
            }
        }
        // headPose() completes immediately when the device exposes no orientation sensor.
        headJob = scope.launch {
            platforms.flatMapLatest { it?.headPose() ?: emptyFlow() }
                .collect { pose -> head.onPose(pose.yawRad, pose.pitchRad)?.let { surface.setViewport(it) } }
        }
    }

    fun stop() { statsJob?.cancel(); headJob?.cancel(); chromeJob?.cancel(); decoder.stop() }

    // ---- transport callbacks ------------------------------------------------------------------

    fun onListening(controlPort: Int) { overlay.setHint("Open Rokid Mirror on the phone · port $controlPort"); enter(State.ADVERTISING) }
    fun onSenderConnecting(name: String) { senderName = name; overlay.setStatus("Connecting: $name") }
    fun onShowPairingCode(code: String) { enter(State.PAIRING); overlay.showPairingCode(PairingCommitment.formatForDisplay(code)) }
    fun onPairingFailed(reason: String) { overlay.showPairingCode(null); overlay.setWarning("Pairing failed: $reason"); enter(State.ADVERTISING) }

    fun onConnected(name: String, sessionId: String) {
        senderName = name
        overlay.showPairingCode(null)
        enter(State.CONNECTED)
        ReceiverLog.i(TAG, "connected", "sender" to name, "session" to sessionId)
    }

    fun onDisconnected(reason: String) {
        preview.setEnabled(false, 0, 0, 0)
        decoder.stop()
        surface.hideVideo()
        overlay.setPointer(null, null, false)
        pointerVisible = false
        streamFormat = null
        framesSubmitted = 0
        overlay.showPairingCode(null)
        overlay.setStreamingIndicator(false)
        enter(State.RECONNECT_WAIT)
        overlay.setHint("Link lost ($reason). Waiting for the phone to reconnect…")
        scope.launch { delay(3000); if (state == State.RECONNECT_WAIT) enter(State.ADVERTISING) }
    }

    fun onControlMessage(m: ControlMessage) {
        when (m.type) {
            MessageType.STREAM_START -> {
                // A stream and the camera view cannot share the panel.
                if (vision.running) vision.setEnabled(false)
                val s = ControlCodec.payloadOf(m, Payloads.StreamStart.serializer())
                sourceName = s.sourceName
                framesSubmitted = 0; framesDroppedByDecoder = 0
                enter(State.STREAMING)
                ReceiverLog.i(TAG, "stream_start", "codec" to s.codec, "w" to s.width, "h" to s.height, "fps" to s.fps, "bps" to s.bitrate)
            }
            MessageType.STREAM_FORMAT -> onStreamFormat(ControlCodec.payloadOf(m, Payloads.StreamFormat.serializer()))
            MessageType.STREAM_STOP -> {
                decoder.stop(); streamFormat = null
                surface.hideVideo()
                overlay.setPointer(null, null, false); pointerVisible = false
                overlay.setStreamingIndicator(false)
                enter(State.CONNECTED)
            }
            MessageType.VIEWPORT_SET -> {
                val v = ControlCodec.payloadOf(m, Payloads.ViewportSet.serializer())
                baseViewport = ViewportState(v.scale, v.centerX, v.centerY, FitMode.fromWire(v.fitMode))
                head.setBase(baseViewport)
                surface.setViewport(baseViewport)
            }
            MessageType.PREVIEW_SET -> {
                val p = ControlCodec.payloadOf(m, Payloads.PreviewSet.serializer())
                preview.setEnabled(p.enabled, p.fps, p.maxWidth, p.quality)
                ReceiverLog.i(TAG, "preview", "enabled" to p.enabled, "fps" to p.fps, "maxWidth" to p.maxWidth)
            }
            MessageType.VISION_SET -> {
                val v = ControlCodec.payloadOf(m, Payloads.Vision.serializer())
                vision.setGain(v.contrast)
                setVision(v.enabled, "phone")
            }
            MessageType.POINTER -> {
                val p = ControlCodec.payloadOf(m, Payloads.Pointer.serializer())
                pointerVisible = p.visible
                if (!p.visible) overlay.setPointer(null, null, false)
                else surface.sourceToDisplay(p.x, p.y)?.let { (x, y) -> overlay.setPointer(x, y, p.pressed) }
            }
            MessageType.PROFILE_SET -> {
                val p = ControlCodec.payloadOf(m, Payloads.ProfileSet.serializer())
                baseViewport = ViewportState(p.scale, 0.5f + p.offsetX, 0.5f + p.offsetY, FitMode.fromWire(p.fitMode))
                head.setBase(baseViewport)
                surface.setViewport(baseViewport)
                overlay.setHint("Profile: ${p.name}")
                scope.launch { delay(2000); overlay.setHint(null) }
            }
            else -> ReceiverLog.d(TAG, "ignored", "type" to m.type)
        }
    }

    private fun onStreamFormat(f: Payloads.StreamFormat) {
        val codec = VideoCodec.fromWire(f.codec)
        if (codec == null) { transport.sendError(ErrorCode.UNSUPPORTED_CODEC, "codec ${f.codec}"); return }
        surface.setSource(f.sourceWidth, f.sourceHeight)
        val changed = streamFormat?.let { it.codec != f.codec || it.width != f.width || it.height != f.height || it.csd0 != f.csd0 } ?: true
        streamFormat = f
        if (changed || !decoder.isConfigured) {
            val ok = decoder.configure(codec, f.width, f.height, f.csd0?.let { Base64.getDecoder().decode(it) }, f.csd1?.let { Base64.getDecoder().decode(it) })
            if (!ok) {
                if (!decoder.surfaceReady) {
                    // The video surface is created once the renderer shows it; the pending format
                    // is applied from that callback. Nothing is wrong, so tell the sender nothing.
                    ReceiverLog.i(TAG, "decoder_deferred_until_surface")
                    return
                }
                transport.sendError(ErrorCode.DECODER_FAILED, "decoder configuration failed")
                overlay.setWarning("Decoder failed")
                return
            }
            onDecoderReady()
        }
        ReceiverLog.i(TAG, "stream_format", "codec" to f.codec, "w" to f.width, "h" to f.height, "srcW" to f.sourceWidth, "srcH" to f.sourceHeight, "hasCsd" to (f.csd0 != null))
    }

    fun onAccessUnit(unit: EncodedAccessUnit, firstPacketNs: Long, completeNs: Long) {
        if (!decoder.isConfigured) {
            // Keyframes carry SPS/PPS in-band; configure lazily if STREAM_FORMAT has not arrived yet.
            val f = streamFormat
            if (f == null || !unit.isKeyframe || !decoder.surfaceReady) return
            if (!decoder.configure(VideoCodec.fromWire(f.codec) ?: return, f.width, f.height, null, null)) return
            onDecoderReady()
        }
        stats.onComplete(unit.frameId, firstPacketNs, completeNs, unit.size)
        framesSubmitted++
        if (!decoder.submit(unit)) {
            framesDroppedByDecoder++
            requestKeyframe("decoder dropped frame ${unit.frameId}")
        }
    }

    fun onReassemblyStats(s: ReassemblyStats) {
        lastReassembly = s
        stats.onPacketCounters(s.packetsReceived, s.packetsLost, s.framesDelivered, s.framesDropped)
        if (stats.consumeKeyframeSuggestion(s.keyframeRequestsSuggested)) requestKeyframe("packet loss")
    }

    /**
     * A black screen is useless on a device with no visible log, so when the stream is running
     * but nothing reaches the display, say which stage stopped rather than showing nothing.
     */
    private fun watchForStall(s: Payloads.Stats) {
        if (s.framesDecoded != lastFramesDecoded) {
            lastFramesDecoded = s.framesDecoded
            stalledSeconds = 0
            overlay.setWarning(null)
            return
        }
        stalledSeconds++
        if (stalledSeconds < STALL_WARNING_SECONDS) return
        val packets = lastReassembly?.packetsReceived ?: 0
        val delivered = lastReassembly?.framesDelivered ?: 0
        if (!decoder.surfaceReady && !hasDisplay()) return
        val reason = when {
            streamFormat == null -> "no STREAM_FORMAT from the phone yet"
            !decoder.surfaceReady -> "display surface not ready"
            !decoder.isConfigured -> "decoder not configured (codec ${streamFormat?.codec})"
            packets == 0L -> "no video packets arriving (UDP blocked on this network?)"
            delivered == 0L -> "packets arrive but no frame completes ($packets received)"
            framesSubmitted == 0L -> "frames complete but none reach the decoder"
            else -> "decoder accepted ${framesSubmitted} frames and returned none"
        }
        overlay.chromeVisible = true
        overlay.setWarning("No video: $reason")
        ReceiverLog.w(TAG, "stall", "reason" to reason, "packets" to packets, "delivered" to delivered,
            "submitted" to framesSubmitted, "decoded" to s.framesDecoded, "configured" to decoder.isConfigured, "surface" to decoder.surfaceReady)
        if (stalledSeconds % 3 == 0) requestKeyframe("stalled: $reason")
        // Every few seconds of silence, rebuild the decoder rather than sit on a dead one.
        if (stalledSeconds % 6 == 0) {
            ReceiverLog.w(TAG, "stall_reconfigure", "after" to stalledSeconds)
            decoder.stop()
            streamFormat?.let { onStreamFormat(it) }
        }
    }

    /** A window appeared: restate everything it needs to draw, and rebuild the decoder. */
    fun onDisplayAttached() {
        overlay.chromeVisible = true
        enter(state)
        streamFormat?.let { onStreamFormat(it) }
        surface.setViewport(baseViewport)
        if (state == State.STREAMING) requestKeyframe("display reattached")
    }

    /** The window went away. The link stays up; there is simply nowhere to draw. */
    fun onDisplayDetached() {
        decoder.stop()
        transport.requireKeyframe()
        stalledSeconds = 0
        ReceiverLog.i(TAG, "display_detached_stream_kept")
    }

    /** Called after a successful (re)configuration, including a deferred one. */
    fun onDecoderReady() {
        overlay.setWarning(null)
        transport.requireKeyframe()
        requestKeyframe("decoder (re)configured")
    }

    fun onDecoderError(message: String) {
        overlay.setWarning("Decoder error: $message")
        transport.sendError(ErrorCode.DECODER_FAILED, message)
        streamFormat?.let { onStreamFormat(it) } // try to reconfigure once
    }

    // ---- input -------------------------------------------------------------------------------

    /**
     * Glasses-side view control (M5). Mapping (temple touch bar -> key events) is UNVERIFIED on
     * RV101/RV102; the adapter logs raw key codes so it can be corrected after the M0 audit.
     */
    private fun onInput(input: GlassesInput) {
        ReceiverLog.d(TAG, "input", "type" to input::class.simpleName)
        when (input) {
            // The phone drives zoom and viewport; on the glasses a tap is for getting the
            // heads-up text out of the way, which is what it is mostly in the way of.
            GlassesInput.Select -> if (overlay.chromeVisible) hideChrome() else showChrome(sticky = true)
            GlassesInput.DoubleTap -> { overlay.setHint("Closing…"); onExitRequested() }
            // With no picture to zoom, the swipes are the way into and out of vision mode.
            GlassesInput.Forward -> if (state == State.STREAMING) { showChrome(); stepZoom(1.25f) } else setVision(true, "temple")
            GlassesInput.Backward -> if (state == State.STREAMING) { showChrome(); stepZoom(0.8f) } else setVision(false, "temple")
            GlassesInput.LongPress -> when (state) {
                // Recentring means nothing with no picture, so idle long press is "forget all senders".
                State.ADVERTISING, State.RECONNECT_WAIT -> {
                    onForgetAllSenders()
                    showChrome(sticky = true)
                    overlay.setHint("All paired phones forgotten")
                    scope.launch { delay(2500); overlay.setHint(null) }
                }
                else -> {
                    head.recenter(); surface.setViewport(baseViewport)
                    showChrome(); overlay.setHint("Recentered")
                    scope.launch { delay(1200); overlay.setHint(null) }
                }
            }
            // Back leaves the app. On this hardware a temple double tap arrives as Back, which
            // is why every other Rokid app closes on it; consuming it kept us open.
            GlassesInput.Back -> { overlay.setHint("Closing…"); onExitRequested() }
            is GlassesInput.Unknown -> overlay.setHint("Key ${input.keyCode}")
        }
    }

    /**
     * Vision mode takes the display, so it is mutually exclusive with a stream. Nothing about
     * the camera indicator is touched here; the platform drives it as it always does.
     */
    fun setVision(enabled: Boolean, source: String) {
        if (enabled && state == State.STREAMING) {
            overlay.setWarning("Stop mirroring first: the glasses can show one or the other")
            return
        }
        if (!enabled) {
            vision.setEnabled(false)
            overlay.setHint(null)
            return
        }
        if (!vision.available) {
            showChrome(sticky = true)
            overlay.setWarning("Camera not available: grant the permission in the glasses' settings")
            return
        }
        surface.hideVideo()
        vision.setEnabled(true)
        showChrome()
        ReceiverLog.i(TAG, "vision_mode", "enabled" to true, "source" to source)
    }

    /** Progress from the camera pipeline, shown briefly so it does not sit on top of the view. */
    fun onVisionStatus(running: Boolean, people: Int, fps: Float, detector: String, error: String?) {
        when {
            error != null -> { showChrome(sticky = true); overlay.setWarning("Vision: $error") }
            !running -> overlay.setHint(null)
            else -> {
                overlay.setWarning(null)
                overlay.setStatus(if (people == 0) "Vision · no one in view" else "Vision · $people in view")
                overlay.setDebug("camera %.0f fps · %s".format(fps, detector))
            }
        }
    }

    private fun toggleFitZoom() {
        val v = surface.currentViewport
        baseViewport = if (v.fitMode == FitMode.FIT) ViewportState(2f, 0.5f, 0.5f, FitMode.CUSTOM) else ViewportState()
        head.setBase(baseViewport)
        surface.setViewport(baseViewport)
    }

    private fun stepZoom(factor: Float) {
        baseViewport = ViewportMath.zoom(surface.currentViewport, factor, surface.sourceWidth.coerceAtLeast(1), surface.sourceHeight.coerceAtLeast(1), surface.displayWidth, surface.displayHeight)
        head.setBase(baseViewport)
        surface.setViewport(baseViewport)
    }

    // ---- helpers -------------------------------------------------------------------------------

    private fun requestKeyframe(reason: String) {
        val now = clockNs()
        if (now - lastKeyframeRequestNs < KEYFRAME_REQUEST_MIN_INTERVAL_NS) return
        lastKeyframeRequestNs = now
        transport.requestKeyframe(reason)
    }

    /** Shows the heads-up text, and unless [sticky] hides it again after a few seconds. */
    private fun showChrome(sticky: Boolean = false) {
        chromeJob?.cancel()
        overlay.chromeVisible = true
        if (sticky) return
        chromeJob = scope.launch {
            delay(CHROME_VISIBLE_MS)
            if (state == State.STREAMING) overlay.chromeVisible = false
        }
    }

    private fun hasDisplay(): Boolean = surface.displayWidth > 1 && surface.sourceWidth >= 0 && decoder.name != "-"

    private fun hideChrome() {
        chromeJob?.cancel()
        overlay.chromeVisible = false
    }

    private fun enter(next: State) {
        state = next
        val status = when (next) {
            State.ADVERTISING -> "Rokid Mirror · waiting for phone"
            State.PAIRING -> "Pairing with $senderName"
            State.CONNECTED -> "Connected: $senderName"
            State.STREAMING -> "$senderName${if (sourceName.isNotBlank()) " · $sourceName" else ""}"
            State.RECONNECT_WAIT -> "Reconnecting…"
        }
        overlay.setStatus(status)
        overlay.setStreamingIndicator(next == State.STREAMING)
        // Text is useful while connecting and in the way once the picture is up.
        if (next == State.STREAMING) showChrome() else { chromeJob?.cancel(); overlay.chromeVisible = true }
        if (next != State.STREAMING) overlay.setDebug(null)
        if (next == State.ADVERTISING) overlay.setHint("Open Rokid Mirror on the phone · swipe forward for camera view · double tap to exit")
        if (next == State.CONNECTED || next == State.STREAMING) overlay.setHint(null)
    }

    private fun updateDebug(s: Payloads.Stats) {
        if (!debugOverlay) return
        val f = streamFormat
        overlay.setDebug(
            "%s %dx%d %.0ffps %dkbps\nrtt %.1f net~%.1f asm %.1f dec %.1f rnd %.1f ms\nlost %d drop %d lag %d dec %s".format(
                f?.codec ?: "-", f?.width ?: 0, f?.height ?: 0, s.fps, s.bitrateBps / 1000,
                transport.rttNs / 1e6, transport.rttNs / 2e6, s.reassemblyMs, s.decodeMs, s.renderMs,
                s.packetsLost, s.framesDropped, s.decodeLagFrames, decoder.name.takeLast(18),
            ),
        )
    }
}
