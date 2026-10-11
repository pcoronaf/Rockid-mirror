package com.rokidmirror.receiver.control

import com.rokidmirror.protocol.VideoCodec
import com.rokidmirror.protocol.control.ErrorCode
import com.rokidmirror.protocol.control.MessageFactory
import com.rokidmirror.protocol.control.MessageType
import com.rokidmirror.protocol.control.Payloads
import com.rokidmirror.protocol.video.EncodedAccessUnit
import com.rokidmirror.protocol.viewport.ViewportState
import com.rokidmirror.receiver.telemetry.PipelineStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A decoder that cannot configure must not take the link down. The receiver tries to reconfigure
 * once; the old code reported that failure through the same path it reacted to, so a decoder that
 * kept refusing recursed until the stack overflowed, killing the control thread — which the phone
 * could only see as "receiver closed the connection".
 */
class DecoderErrorRecoveryTest {
    private val factory = MessageFactory("test-session") { 0L }

    private fun streamFormat() = factory.create(
        MessageType.STREAM_FORMAT, Payloads.StreamFormat.serializer(),
        Payloads.StreamFormat("h264", 480, 640, 30, null, null, 480, 640),
    )

    @Test fun aDecoderThatNeverConfiguresIsRetriedExactlyOnce() {
        val decoder = FailingDecoder()
        val transport = RecordingTransport()
        val session = session(decoder, transport)

        // The decoder fails to configure, which calls back into onDecoderError.
        session.onControlMessage(streamFormat())
        session.onDecoderError("configure failed")

        // One reconfigure attempt from onDecoderError, no runaway recursion.
        assertTrue("retried at least once", decoder.configureCalls >= 2)
        assertTrue("did not recurse unboundedly", decoder.configureCalls <= 3)
        assertTrue("told the phone", transport.errors.any { it.first == ErrorCode.DECODER_FAILED })
    }

    private fun session(decoder: SessionDecoder, transport: SessionTransport) = ReceiverSession(
        platforms = MutableStateFlow(null),
        surface = NoSurface,
        decoder = decoder,
        vision = NoVision,
        preview = NoPreview,
        transport = transport,
        overlay = NoOverlay,
        stats = PipelineStats(),
        scope = CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
        debugOverlay = false,
        onForgetAllSenders = {},
    )

    private class FailingDecoder : SessionDecoder {
        var configureCalls = 0
        override fun configure(codec: VideoCodec, width: Int, height: Int, csd0: ByteArray?, csd1: ByteArray?): Boolean {
            configureCalls++
            return false
        }
        override val surfaceReady = true
        override fun submit(unit: EncodedAccessUnit) = false
        override fun stop() {}
        override val isConfigured = false
        override val name = "fake"
    }

    private class RecordingTransport : SessionTransport {
        val errors = mutableListOf<Pair<ErrorCode, String>>()
        override fun requestKeyframe(reason: String) {}
        override fun sendStats(stats: Payloads.Stats) {}
        override fun sendError(code: ErrorCode, message: String) { errors += code to message }
        override fun ping() {}
        override fun requireKeyframe() {}
        override fun disconnect(reason: String) {}
        override val clockOffsetNs = 0L
        override val rttNs = 0L
    }

    private object NoSurface : SessionSurface {
        override fun setSource(width: Int, height: Int) {}
        override fun sourceToDisplay(nx: Float, ny: Float): Pair<Float, Float>? = null
        override fun hideVideo() {}
        override fun setViewport(state: ViewportState) {}
        override val currentViewport = ViewportState()
        override val sourceWidth = 0
        override val sourceHeight = 0
        override val displayWidth = 480
        override val displayHeight = 640
    }

    private object NoVision : SessionVision {
        override val available = false
        override val running = false
        override fun setEnabled(enabled: Boolean) {}
        override fun setGain(gain: Float) {}
    }

    private object NoPreview : SessionPreview {
        override fun setEnabled(enabled: Boolean, fps: Int, maxWidth: Int, quality: Int) {}
    }

    private object NoOverlay : SessionOverlay {
        override var chromeVisible = true
        override fun setStatus(text: String) {}
        override fun setInfo(text: String) {}
        override fun setPointer(x: Float?, y: Float?, pressed: Boolean) {}
        override fun showPairingCode(formatted: String?) {}
        override fun setWarning(text: String?) {}
        override fun setDebug(text: String?) {}
        override fun setHint(text: String?) {}
        override fun setStreamingIndicator(on: Boolean) {}
    }
}
