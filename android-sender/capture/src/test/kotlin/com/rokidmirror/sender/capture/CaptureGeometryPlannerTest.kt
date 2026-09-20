package com.rokidmirror.sender.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureGeometryPlannerTest {
    @Test
    fun portraitPhoneTo720pClass() {
        val (w, h) = CaptureGeometryPlanner.encodedSize(1080, 2340, 1280)
        assertEquals(1280, h)
        assertEquals(576, w) // 590.7 aligned down to 16
    }

    @Test
    fun landscapeAppKeepsOrientation() {
        val (w, h) = CaptureGeometryPlanner.encodedSize(1920, 1080, 1280)
        assertEquals(1280, w); assertEquals(720, h)
    }

    @Test
    fun neverUpscales() {
        val (w, h) = CaptureGeometryPlanner.encodedSize(600, 400, 1280)
        assertEquals(592, w); assertEquals(400, h)
    }

    @Test
    fun shortEdgeLimitApplies() {
        val (w, h) = CaptureGeometryPlanner.encodedSize(1000, 1000, 1280, shortEdgeLimit = 720)
        assertEquals(720, w); assertEquals(720, h)
    }

    @Test
    fun resolutionSteps() {
        assertEquals(1280, CaptureGeometryPlanner.applyResolutionStep(1280, 0))
        assertEquals(960, CaptureGeometryPlanner.applyResolutionStep(1280, 1))
        assertEquals(640, CaptureGeometryPlanner.applyResolutionStep(1280, 2))
    }
}
