package com.rokidmirror.receiver.renderer

import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewScalingTest {
    @Test
    fun keepsTheGlassesAspectRatio() {
        val (w, h) = PreviewScaling.scaledSize(480, 640, 240)
        assertEquals(240, w)
        assertEquals(320, h)
    }

    @Test
    fun neverEnlargesASmallSource() {
        assertEquals(120 to 160, PreviewScaling.scaledSize(120, 160, 320))
    }

    @Test
    fun degenerateInputIsSafe() {
        assertEquals(PreviewScaling.MIN_EDGE to PreviewScaling.MIN_EDGE, PreviewScaling.scaledSize(0, 0, 320))
        val (_, h) = PreviewScaling.scaledSize(4000, 1, 320)
        assertEquals(PreviewScaling.MIN_EDGE, h)
    }

    @Test
    fun attemptsDegradeAndAlwaysTerminate() {
        val attempts = PreviewScaling.attempts(70, 480)
        assertEquals(70 to 480, attempts.first())
        assertEquals("must end small enough to fit any budget", 30 to 240, attempts.last())
        // Every step is no bigger than the one before it, in both quality and width.
        attempts.zipWithNext().forEach { (a, b) ->
            org.junit.Assert.assertTrue("quality $a -> $b", b.first <= a.first)
            org.junit.Assert.assertTrue("width $a -> $b", b.second <= a.second)
        }
    }

    @Test
    fun attemptsClampAbsurdRequests() {
        val attempts = PreviewScaling.attempts(500, 99999)
        org.junit.Assert.assertTrue(attempts.first().first <= 90)
        org.junit.Assert.assertTrue(attempts.first().second <= 960)
    }

    @Test
    fun rateIsClampedToSomethingTheLinkCanCarry() {
        assertEquals(333L, PreviewScaling.intervalMs(3))
        assertEquals(1000L, PreviewScaling.intervalMs(0))
        assertEquals(100L, PreviewScaling.intervalMs(60))
    }
}
