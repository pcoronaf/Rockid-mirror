package com.rokidmirror.protocol

import com.rokidmirror.protocol.control.ErrorCode
import com.rokidmirror.protocol.control.MirrorException
import com.rokidmirror.protocol.control.Payloads
import com.rokidmirror.protocol.transport.ClockSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StreamNegotiatorTest {
    private val caps = Payloads.Capabilities(
        display = Payloads.DisplaySize(480, 640),
        codecs = listOf(Payloads.CodecCapability("h264", listOf("baseline", "main"))),
        maxDecode = Payloads.DecodeLimit(1280, 720, 30),
    )

    @Test
    fun portraitPhoneBalancedPreset() {
        val p = StreamNegotiator.negotiate(StreamPreset.BALANCED, 1080, 2340, caps)
        assertEquals(1280, p.height)
        assertEquals(StreamNegotiator.align((1280 * 1080 / 2340)), p.width)
        assertEquals(30, p.fps)
        assertEquals(3_000_000, p.bitrate)
        assertTrue(p.width % 16 == 0 && p.height % 16 == 0)
    }

    @Test
    fun interactiveFpsClampedByReceiver() {
        val p = StreamNegotiator.negotiate(StreamPreset.INTERACTIVE, 2340, 1080, caps)
        assertEquals(30, p.fps)
        assertEquals(1280, p.width)
        assertEquals(576, p.height)
    }

    @Test
    fun squareishSourceLimitedByShortEdge() {
        val p = StreamNegotiator.negotiate(StreamPreset.BALANCED, 1000, 1000, caps)
        assertEquals(720, p.width); assertEquals(720, p.height)
    }

    @Test
    fun neverUpscalesSmallSource() {
        val p = StreamNegotiator.negotiate(StreamPreset.BALANCED, 400, 300, caps)
        assertEquals(400, p.width); assertEquals(288, p.height)
    }

    @Test
    fun encoderLimitsApply() {
        val p = StreamNegotiator.negotiate(StreamPreset.INTERACTIVE, 1080, 2340, caps, EncoderLimits(1920, 1080, 24, 2_000_000, 4_000_000))
        assertEquals(24, p.fps)
        assertEquals(4_000_000, p.bitrate)
    }

    @Test
    fun missingH264IsUnsupportedCodec() {
        try {
            StreamNegotiator.negotiate(StreamPreset.LOW, 100, 100, caps.copy(codecs = listOf(Payloads.CodecCapability("vp8"))))
            fail()
        } catch (e: MirrorException) { assertEquals(ErrorCode.UNSUPPORTED_CODEC, e.code) }
    }

    @Test
    fun clockSyncUsesMinRttSample() {
        val c = ClockSync()
        c.onPong(sentLocalNs = 0, peerNs = 1_000_000 + 5_000_000, receivedLocalNs = 10_000_000) // rtt 10ms, offset 1ms
        c.onPong(sentLocalNs = 100, peerNs = 100 + 1_000_000 + 1_000_000 + 30_000_000, receivedLocalNs = 100 + 2_000_000) // rtt 2ms, offset ~31ms
        assertEquals(2_000_000L, c.minRttNs)
        assertEquals(31_000_000L, c.offsetNs)
        assertEquals(0L, c.toLocalNs(31_000_000L))
    }
}
