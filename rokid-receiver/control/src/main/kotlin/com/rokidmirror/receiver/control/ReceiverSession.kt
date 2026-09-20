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
import com.rokidmirror.receiver.platform.Support
import com.rokidmirror.receiver.telemetry.PipelineStats
import com.rokidmirror.receiver.telemetry.ReceiverLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

interface SessionOverlay {
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
class ReceiverSession(
    private val platform: RokidPlatformAdapter,
    private val surface: SessionSurface,
    private val decoder: SessionDecoder,
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
    private companion object { const val TAG = "Session"; const val KEYFRAME_REQUEST_MIN_INTERVAL_NS = 300_000_000L }

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
                    if (state == State.STREAMING && s.fps < 1f && framesSubmitted > 0) overlay.setWarning("No video: waiting for frames…") else overlay.setWarning(null)
                }
            }
        }
        scope.launch { platform.inputEvents().collect { onInput(it) } }
        if (platform.getSensorCapabilities().rotationVector != Support.UNAVAILABLE) {
            headJob = scope.launch { platform.headPose().collect { pose -> head.onPose(pose.yawRad, pose.pitchRad)?.let { surface.setViewport(it) } } }
        }
    }

    fun stop() { statsJob?.cancel(); headJob?.cancel(); decoder.stop() }

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
        stats.onPacketCounters(s.packetsReceived, s.packetsLost, s.framesDelivered, s.framesDropped)
        if (stats.consumeKeyframeSuggestion(s.keyframeRequestsSuggested)) requestKeyframe("packet loss")
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
            GlassesInput.Select -> toggleFitZoom()
            GlassesInput.DoubleTap -> { overlay.setHint("Closing…"); onExitRequested() }
            GlassesInput.Forward -> stepZoom(1.25f)
            GlassesInput.Backward -> stepZoom(0.8f)
            GlassesInput.LongPress -> { head.recenter(); surface.setViewport(baseViewport); overlay.setHint("Recentered"); scope.launch { delay(1200); overlay.setHint(null) } }
            GlassesInput.Back -> { if (state == State.ADVERTISING || state == State.RECONNECT_WAIT) { onForgetAllSenders(); overlay.setHint("All paired phones forgotten"); scope.launch { delay(2000); overlay.setHint(null) } } }
            is GlassesInput.Unknown -> overlay.setHint("Key ${input.keyCode}")
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
        if (next != State.STREAMING) overlay.setDebug(null)
        if (next == State.ADVERTISING) overlay.setHint("Open Rokid Mirror on the phone · double tap the temple to exit")
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
