package com.rokidmirror.receiver.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContrastFilterTest {
    private fun luma(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
    private fun level(argb: Int) = argb and 0xFF

    @Test
    fun flatFrameSurvivesWithoutDividingByZero() {
        val frame = ByteArray(16) { 120.toByte() }
        val out = ContrastFilter.enhance(frame, 4, 4)
        assertEquals(16, out.size)
        assertTrue(out.all { it ushr 24 == 0xFF })
    }

    @Test
    fun aDullFrameIsStretchedAcrossTheFullRange() {
        // Everything between 100 and 140: unreadable on a dim panel until it is stretched.
        val frame = ByteArray(100) { (100 + it % 41).toByte() }
        val out = ContrastFilter.enhance(frame, 10, 10)
        val levels = out.map { level(it) }
        assertTrue("darkest should approach black, was ${levels.min()}", levels.min() < 30)
        assertTrue("brightest should approach white, was ${levels.max()}", levels.max() > 225)
    }

    @Test
    fun percentileBoundsIgnoreOutliers() {
        // 98 mid-grey pixels with one black and one white speck: the specks must not set the range.
        val frame = ByteArray(100) { 128.toByte() }
        frame[0] = 0
        frame[99] = 255.toByte()
        val bounds = ContrastFilter.percentileBounds(frame, 10, 10, clip = 0.02f)
        assertTrue("low was ${bounds[0]}", bounds[0] > 0)
        assertTrue("high was ${bounds[1]}", bounds[1] < 255)
    }

    @Test
    fun gainPushesValuesAwayFromMidGrey() {
        val frame = luma(100, 150, 100, 150)
        val plain = ContrastFilter.toArgb(frame, 2, 2, 0, 255, gain = 1f).map { level(it) }
        val boosted = ContrastFilter.toArgb(frame, 2, 2, 0, 255, gain = 2f).map { level(it) }
        assertTrue(boosted[0] < plain[0])
        assertTrue(boosted[1] > plain[1])
    }

    @Test
    fun rowStridePaddingIsSkipped() {
        // Camera planes are padded; the padding must never reach the picture.
        val width = 2; val height = 2; val stride = 4
        val frame = ByteArray(stride * height)
        frame[0] = 10; frame[1] = 20; frame[2] = 99; frame[3] = 99
        frame[4] = 30; frame[5] = 40; frame[6] = 99; frame[7] = 99
        val out = ContrastFilter.toArgb(frame, width, height, 0, 255, 1f, stride)
        assertEquals(4, out.size)
        assertEquals(listOf(10, 20, 30, 40), out.map { level(it) })
    }

    @Test
    fun outputBufferIsReused() {
        val frame = ByteArray(9) { (it * 20).toByte() }
        val buffer = IntArray(9)
        val out = ContrastFilter.enhance(frame, 3, 3, out = buffer)
        assertTrue(out === buffer)
    }
}
