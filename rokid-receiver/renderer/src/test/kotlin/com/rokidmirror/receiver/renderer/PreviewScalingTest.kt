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
    fun rateIsClampedToSomethingTheLinkCanCarry() {
        assertEquals(333L, PreviewScaling.intervalMs(3))
        assertEquals(1000L, PreviewScaling.intervalMs(0))
        assertEquals(100L, PreviewScaling.intervalMs(60))
    }
}
