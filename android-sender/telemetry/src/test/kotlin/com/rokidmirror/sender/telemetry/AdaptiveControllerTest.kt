package com.rokidmirror.sender.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveControllerTest {
    private fun ctl() = AdaptiveController(minBitrate = 1_000_000, maxBitrate = 4_000_000, maxFps = 60, stableIntervalsBeforeUpgrade = 3).apply { reset(3_000_000, 60) }
    private val calm = AdaptiveInputs(0f, 20f, 8f, 0, 0f, 0)
    private val mild = calm.copy(lossFraction = 0.02f)
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
            when (val a = c.evaluate(mild)) {
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
