package com.rokidmirror.protocol

import com.rokidmirror.protocol.viewport.FitMode
import com.rokidmirror.protocol.viewport.ViewportMath
import com.rokidmirror.protocol.viewport.ViewportState
import org.junit.Assert.assertEquals
import org.junit.Test

class ViewportMathTest {
    // Phone portrait 720x1600 shown on 480x640 glasses
    private val sw = 720; private val sh = 1600; private val dw = 480; private val dh = 640

    @Test
    fun fitShowsWholeFrameCentered() {
        val p = ViewportMath.placement(ViewportState(fitMode = FitMode.FIT), sw, sh, dw, dh)
        assertEquals(0.4f, ViewportMath.fitScale(sw, sh, dw, dh), 1e-6f)
        assertEquals(288f, p.width, 1e-3f)
        assertEquals(640f, p.height, 1e-3f)
        assertEquals(96f, p.left, 1e-3f)
        assertEquals(0f, p.top, 1e-3f)
    }

    @Test
    fun fillCoversDisplay() {
        val p = ViewportMath.placement(ViewportState(fitMode = FitMode.FILL), sw, sh, dw, dh)
        assertEquals(480f, p.width, 1e-3f)
        assertEquals(1066.667f, p.height, 1e-2f)
        assertEquals(0f, p.left, 1e-3f)
        assertEquals((640 - 1066.667f) / 2, p.top, 1e-2f)
    }

    @Test
    fun actualIsOneToOne() {
        val p = ViewportMath.placement(ViewportState(fitMode = FitMode.ACTUAL), sw, sh, dw, dh)
        assertEquals(720f, p.width, 1e-3f)
        assertEquals(1600f, p.height, 1e-3f)
    }

    @Test
    fun panIsClampedToSourceBounds() {
        var s = ViewportState(fitMode = FitMode.ACTUAL)
        s = ViewportMath.pan(s, -10f, -10f, sw, sh, dw, dh) // way past the top-left corner
        val p = ViewportMath.placement(s, sw, sh, dw, dh)
        assertEquals(0f, p.left, 1e-3f)
        assertEquals(0f, p.top, 1e-3f)
        s = ViewportMath.pan(s, 10f, 10f, sw, sh, dw, dh)
        val q = ViewportMath.placement(s, sw, sh, dw, dh)
        assertEquals(dw.toFloat(), q.right, 1e-3f)
        assertEquals(dh.toFloat(), q.bottom, 1e-3f)
    }

    @Test
    fun smallerThanDisplayAxisStaysCentered() {
        val s = ViewportMath.clamp(ViewportState(fitMode = FitMode.FIT, centerX = 0.9f, centerY = 0.9f), sw, sh, dw, dh)
        assertEquals(0.5f, s.centerX, 0f)
        assertEquals(0.5f, s.centerY, 0f) // 640 == display height -> centered
    }

    @Test
    fun zoomSwitchesToCustomAndClamps() {
        val z = ViewportMath.zoom(ViewportState(fitMode = FitMode.FIT), 2f, sw, sh, dw, dh)
        assertEquals(FitMode.CUSTOM, z.fitMode)
        assertEquals(2f, z.scale, 1e-6f)
        val big = ViewportMath.zoom(z, 100f, sw, sh, dw, dh)
        assertEquals(ViewportMath.MAX_SCALE, big.scale, 1e-6f)
        val fromActual = ViewportMath.zoom(ViewportState(fitMode = FitMode.ACTUAL), 1f, sw, sh, dw, dh)
        assertEquals(2.5f, fromActual.scale, 1e-6f) // 1 / fitScale(0.4)
    }

    @Test
    fun sourceChangeRecenters() {
        val s = ViewportMath.onSourceChanged(ViewportState(2f, 0.1f, 0.2f, FitMode.CUSTOM))
        assertEquals(0.5f, s.centerX, 0f); assertEquals(0.5f, s.centerY, 0f); assertEquals(FitMode.CUSTOM, s.fitMode)
    }
}
