package com.rokidmirror.receiver.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PipelineStatsTest {
    @Test
    fun computesStageLatencies() {
        val s = PipelineStats()
        var t = 1_000_000_000L
        for (f in 0 until 30) {
            s.onComplete(f.toLong(), firstPacketNs = t, completeNs = t + 2_000_000, bytes = 8_000)
            s.onSubmitted(f.toLong(), t + 2_500_000)
            s.onDecoded(f.toLong(), t + 9_500_000)
            s.onPresented(f.toLong(), t + 12_500_000)
            t += 33_000_000
        }
        val st = s.snapshot(t)
        assertEquals(2f, st.reassemblyMs, 0.01f)
        assertEquals(7f, st.decodeMs, 0.01f)
        assertEquals(3f, st.renderMs, 0.01f)
        assertTrue(st.fps in 29f..31f)
        assertEquals(0, st.decodeLagFrames)
        assertEquals(30L, st.framesDecoded)
        assertTrue(st.bitrateBps in 1_800_000L..2_000_000L)
    }

    @Test
    fun lagCountsSubmittedButNotDecoded() {
        val s = PipelineStats()
        s.onComplete(1, 0, 0, 1); s.onSubmitted(1, 0)
        s.onComplete(2, 0, 0, 1); s.onSubmitted(2, 0)
        assertEquals(2, s.decodeLagFrames())
        s.onDecoded(1, 10)
        assertEquals(1, s.decodeLagFrames())
    }

    @Test
    fun keyframeSuggestionEdgeDetection() {
        val s = PipelineStats()
        assertTrue(!s.consumeKeyframeSuggestion(0))
        assertTrue(s.consumeKeyframeSuggestion(1))
        assertTrue(!s.consumeKeyframeSuggestion(1))
        assertTrue(s.consumeKeyframeSuggestion(3))
    }
}
