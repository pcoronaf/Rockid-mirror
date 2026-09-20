package com.rokidmirror.receiver.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TapDetectorTest {
    @Test
    fun singleTapArmsTheTimerAndFires() {
        val d = TapDetector(320)
        assertEquals(TapDetector.Decision.ARM_SINGLE, d.onTap(1000))
        assertTrue(d.isArmed)
        assertTrue(d.onTimer())
        assertFalse(d.isArmed)
    }

    @Test
    fun secondTapInsideWindowIsDoubleAndCancelsTheSingle() {
        val d = TapDetector(320)
        d.onTap(1000)
        assertEquals(TapDetector.Decision.DOUBLE, d.onTap(1200))
        assertFalse("the armed single must not also fire", d.onTimer())
    }

    @Test
    fun boundaryTapIsStillDouble() {
        val d = TapDetector(320)
        d.onTap(1000)
        assertEquals(TapDetector.Decision.DOUBLE, d.onTap(1320))
    }

    @Test
    fun tapAfterWindowStartsANewSingle() {
        val d = TapDetector(320)
        d.onTap(1000)
        assertEquals(TapDetector.Decision.ARM_SINGLE, d.onTap(1321))
    }

    @Test
    fun threeFastTapsAreOneDoubleThenANewSingle() {
        val d = TapDetector(320)
        assertEquals(TapDetector.Decision.ARM_SINGLE, d.onTap(0))
        assertEquals(TapDetector.Decision.DOUBLE, d.onTap(100))
        assertEquals(TapDetector.Decision.ARM_SINGLE, d.onTap(200))
    }

    @Test
    fun resetDisarms() {
        val d = TapDetector()
        d.onTap(0)
        d.reset()
        assertFalse(d.onTimer())
    }
}
