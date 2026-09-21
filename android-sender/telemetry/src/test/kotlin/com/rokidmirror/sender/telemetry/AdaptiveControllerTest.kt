package com.rokidmirror.sender.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveControllerTest {
    private fun ctl() = AdaptiveController(minBitrate = 1_000_000, maxBitrate = 4_000_000, maxFps = 60, stableIntervalsBeforeUpgrade = 3).apply { reset(3_000_000, 60) }
    private val calm = AdaptiveInputs(lossFraction = 0f, rttMs = 20f, minRttMs = 18f, encodedFps = 30f, encodeLatencyMs = 8f, receiverDecodeLagFrames = 0, droppedFramesPerSecond = 0f, sendQueueDrops = 0)
    private val mild = calm.copy(lossFraction = 0.03f)
    private val queuing = calm.copy(rttMs = 300f, minRttMs = 150f)
    /** Our own pipeline overflowing: a cut always relieves it, so it is never held back. */
    private val backpressure = calm.copy(sendQueueDrops = 2)
    private val severe = calm.copy(lossFraction = 0.2f)

    @Test
    fun calmNetworkDoesNothingThenUpgradesSlowly() {
        val c = ctl()
        assertEquals(AdaptiveAction.None, c.evaluate(calm))
        assertEquals(AdaptiveAction.None, c.evaluate(calm))
        val a = c.evaluate(calm)
        assertTrue(a is AdaptiveAction.SetBitrate && a.bitrate > 3_000_000)
    }

    @Test
    fun degradationOrderIsBitrateThenFpsThenResolution() {
        val c = ctl()
        var seenFps = false; var seenRes = false
        repeat(40) {
            when (val a = c.evaluate(backpressure)) {
                is AdaptiveAction.SetBitrate -> assertTrue("bitrate must drop before fps/res", !seenFps && !seenRes)
                is AdaptiveAction.SetFps -> { assertEquals(30, a.fps); assertEquals(1_000_000, c.level.bitrate); seenFps = true }
                is AdaptiveAction.SetResolutionStep -> { assertTrue(seenFps); seenRes = true }
                else -> {}
            }
        }
        assertTrue(seenFps && seenRes)
        assertEquals(AdaptiveLevel(1_000_000, 30, AdaptiveController.MAX_RESOLUTION_STEP), c.level)
    }

    @Test
    fun severeLossRequestsKeyframeFirstThenCutsBitrateHard() {
        val c = ctl()
        assertTrue(c.evaluate(severe) is AdaptiveAction.RequestKeyframe)
        val next = c.evaluate(severe)
        assertTrue(next is AdaptiveAction.SetBitrate && next.bitrate <= 1_800_000)
    }

    @Test
    fun highRttAndEncodeBackpressureCountAsStressOnceSustained() {
        for (input in listOf(calm.copy(rttMs = 150f), calm.copy(encodeLatencyMs = 60f), calm.copy(receiverDecodeLagFrames = 4))) {
            val c = ctl()
            assertEquals("one bad sample must not degrade", AdaptiveAction.None, c.evaluate(input))
            assertTrue(c.evaluate(input) is AdaptiveAction.SetBitrate)
        }
    }

    @Test
    fun aSlowLinkIsNotTreatedAsCongestionWhenItsFloorIsSlow() {
        // 159 ms RTT with a 155 ms floor is distance, not queuing: throttling cannot help.
        val c = ctl()
        repeat(10) { c.evaluate(calm.copy(rttMs = 159f, minRttMs = 155f)) }
        assertEquals(3_000_000, c.level.bitrate.coerceAtMost(3_000_000))
        assertEquals(60, c.level.fps)
        assertEquals(0, c.level.resolutionStep)
    }

    @Test
    fun queuingThatIgnoresTheCutsAlsoStopsTheRatchet() {
        // 225 ms round trip on a 10 ms link that stays 225 ms however little we send. Cutting
        // to a postage stamp did not help and the stream never came back.
        val c = ctl()
        repeat(40) { c.evaluate(calm.copy(rttMs = 225f, minRttMs = 10f)) }
        assertEquals(60, c.level.fps)
        assertEquals(0, c.level.resolutionStep)
    }

    @Test
    fun recoveryStopsBelowTheLevelThatUpsetTheLink() {
        // Climb, hit trouble at ~3 Mbps, then recover: it must not charge straight back to max.
        val c = ctl()
        repeat(2) { c.evaluate(queuing) }
        val afterCut = c.level.bitrate
        assertTrue(afterCut < 3_000_000)
        repeat(60) { c.evaluate(calm) }
        assertTrue("ceiling should hold recovery below the old peak, got ${c.level.bitrate}", c.level.bitrate < 4_000_000)
        assertTrue(c.level.bitrate > afterCut)
    }

    @Test
    fun delayAboveTheFloorStillCountsAsCongestion() {
        val c = ctl()
        val queuing = calm.copy(rttMs = 240f, minRttMs = 150f)
        assertEquals(AdaptiveAction.None, c.evaluate(queuing))
        assertTrue(c.evaluate(queuing) is AdaptiveAction.SetBitrate)
    }

    @Test
    fun aSourceProducingNoFramesIsNeverThrottled() {
        val c = ctl()
        repeat(30) { c.evaluate(calm.copy(encodedFps = 0f, rttMs = 400f, lossFraction = 0.5f)) }
        assertEquals(3_000_000, c.level.bitrate)
        assertEquals(60, c.level.fps)
        assertEquals(0, c.level.resolutionStep)
    }

    @Test
    fun lossThatCutsDoesNotStopTheDescent() {
        // Congestion: each cut halves the loss, so the controller should keep working.
        val c = ctl()
        var loss = 0.06f
        var cuts = 0
        repeat(12) {
            val action = c.evaluate(calm.copy(lossFraction = loss))
            if (action is AdaptiveAction.SetBitrate && action.bitrate < 3_000_000) { cuts++; loss *= 0.4f }
        }
        assertTrue("expected sustained cuts, saw $cuts", cuts >= 2)
        assertTrue(c.level.bitrate < 3_000_000)
    }

    @Test
    fun lossThatIgnoresTheCutsStopsTheRatchetAndRecovers() {
        // A radio that loses 3 % no matter what we send. The old controller cut to the floor
        // and stayed there; it must stop cutting and climb back instead.
        val c = ctl()
        repeat(40) { c.evaluate(mild) }
        assertEquals("frame rate must not be sacrificed to loss we cannot fix", 60, c.level.fps)
        assertEquals(0, c.level.resolutionStep)
        assertTrue("bitrate should recover, was ${c.level.bitrate}", c.level.bitrate > 1_500_000)
    }

    @Test
    fun isolatedBlipsNeverWalkTheStreamDown() {
        val c = ctl()
        repeat(20) {
            c.evaluate(mild)   // one stressed period
            c.evaluate(calm)   // recovers before the second
        }
        assertEquals(3_000_000, c.level.bitrate)
        assertEquals(60, c.level.fps)
        assertEquals(0, c.level.resolutionStep)
    }

    @Test
    fun latencyTrackerComputesWindowMetrics() {
        val t = LatencyTracker()
        var now = 1_000_000_000L
        repeat(30) {
            t.onDispatched(captureNs = now, encodeNs = now + 5_000_000, dispatchNs = now + 6_000_000, bytes = 10_000, keyframe = it == 0)
            now += 33_000_000
        }
        val s = t.snapshot(now)
        assertEquals(30L, s.frames)
        assertEquals(5f, s.encodeMsAvg, 0.01f)
        assertEquals(1f, s.dispatchMsAvg, 0.01f)
        assertTrue(s.fps in 29f..31f)
        assertEquals(1L, s.keyframes)
        assertTrue(s.bitrateBps in 2_300_000L..2_500_000L)
    }
}
