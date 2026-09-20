package com.rokidmirror.receiver.control

import com.rokidmirror.protocol.viewport.FitMode
import com.rokidmirror.protocol.viewport.ViewportState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadViewportControllerTest {
    private fun ctl() = HeadViewportController(enabled = true, deadZoneRad = 0.05f, gain = 1f, smoothing = 0f).apply { setBase(ViewportState(2f, 0.5f, 0.5f, FitMode.CUSTOM)) }

    @Test
    fun disabledReturnsNull() { assertNull(HeadViewportController(enabled = false).onPose(1f, 1f)) }

    @Test
    fun fitModeIgnoresHeadMotion() {
        val c = ctl(); c.setBase(ViewportState(fitMode = FitMode.FIT))
        assertNull(c.onPose(0.5f, 0.5f))
    }

    @Test
    fun deadZoneHoldsCenter() {
        val c = ctl()
        c.onPose(0f, 0f)
        val s = c.onPose(0.03f, -0.03f)!!
        assertEquals(0.5f, s.centerX, 1e-6f); assertEquals(0.5f, s.centerY, 1e-6f)
    }

    @Test
    fun yawRightMovesViewportRightAndClamps() {
        val c = ctl()
        c.onPose(0f, 0f)
        val s = c.onPose(0.25f, 0f)!!
        assertEquals(0.7f, s.centerX, 1e-6f)
        val big = c.onPose(3f, 0f)!!
        assertEquals(1.0f, big.centerX, 1e-6f) // maxOffset 0.5
    }

    @Test
    fun pitchUpMovesViewportUp() {
        val c = ctl(); c.onPose(0f, 0f)
        val s = c.onPose(0f, 0.25f)!!
        assertTrue(s.centerY < 0.5f)
    }

    @Test
    fun recenterMakesCurrentPoseNeutralAndNoDrift() {
        val c = ctl(); c.onPose(0f, 0f)
        c.onPose(1f, 1f)
        c.recenter()
        val s = c.onPose(1f, 1f)!!
        assertEquals(0.5f, s.centerX, 1e-6f); assertEquals(0.5f, s.centerY, 1e-6f)
        repeat(1000) { c.onPose(1.2f, 1f) }
        assertEquals(0.5f + 0.15f, c.onPose(1.2f, 1f)!!.centerX, 1e-5f) // bounded, no accumulation
    }

    @Test
    fun smoothingApproachesTarget() {
        val c = HeadViewportController(enabled = true, deadZoneRad = 0f, gain = 1f, smoothing = 0.5f).apply { setBase(ViewportState(2f, 0.5f, 0.5f, FitMode.CUSTOM)) }
        c.onPose(0f, 0f)
        val first = c.onPose(0.2f, 0f)!!.centerX
        val second = c.onPose(0.2f, 0f)!!.centerX
        assertTrue(first < second && second < 0.7f)
    }
}
