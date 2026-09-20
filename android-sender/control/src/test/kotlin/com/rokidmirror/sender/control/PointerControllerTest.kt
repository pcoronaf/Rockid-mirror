package com.rokidmirror.sender.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PointerControllerTest {
    private fun ctl() = PointerController().apply { sourceWidth = 1080; sourceHeight = 2340 }

    @Test
    fun startsCentered() {
        assertEquals(0.5f, ctl().position.x, 0f)
        assertEquals(0.5f, ctl().position.y, 0f)
    }

    @Test
    fun clampsToFrame() {
        val c = ctl()
        repeat(20) { c.moveBy(0.5f, 0.5f) }
        assertEquals(1f, c.position.x, 0f)
        assertEquals(1f, c.position.y, 0f)
        assertTrue(c.isAtEdge())
        repeat(40) { c.moveBy(-0.5f, -0.5f) }
        assertEquals(0f, c.position.x, 0f)
        assertEquals(0f, c.position.y, 0f)
    }

    @Test
    fun smallMovesAreNotAccelerated() {
        val c = ctl()
        c.moveBy(0.005f, 0f)
        assertEquals(0.5f + 0.005f * 1.1f, c.position.x, 1e-6f)
    }

    @Test
    fun fastFlickTravelsFurtherThanTheSameDistanceInSmallSteps() {
        val flick = ctl().apply { moveBy(0.2f, 0f) }
        val creep = ctl().apply { repeat(40) { moveBy(0.005f, 0f) } }
        assertTrue("flick=${flick.position.x} creep=${creep.position.x}", flick.position.x > creep.position.x)
    }

    @Test
    fun verticalTravelIsScaledForTallFrames() {
        // A tall 1080x2340 frame: the same pad drag must cover proportionally more height.
        val c = ctl()
        c.moveBy(0f, 0.1f)
        val dy = c.position.y - 0.5f
        val square = PointerController().apply { sourceWidth = 1000; sourceHeight = 1000 }
        square.moveBy(0f, 0.1f)
        assertTrue(dy > square.position.y - 0.5f)
    }

    @Test
    fun mapsToSourcePixels() {
        val c = ctl()
        c.moveTo(0f, 1f)
        assertEquals(0 to 2339, c.toSourcePixels())
        c.moveTo(0.5f, 0.5f)
        assertEquals(540 to 1170, c.toSourcePixels())
    }

    @Test
    fun absoluteMoveIsClamped() {
        val c = ctl()
        c.moveTo(-3f, 7f)
        assertEquals(0f, c.position.x, 0f)
        assertEquals(1f, c.position.y, 0f)
    }
}
