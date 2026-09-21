package com.rokidmirror.receiver.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonBoxTest {
    @Test
    fun aFaceBecomesAWiderTallerBoxAroundTheirHeadAndShoulders() {
        val box = PersonBox.fromFace(left = 0.4f, top = 0.3f, right = 0.5f, bottom = 0.45f)
        assertTrue("should be wider than the face", box.width > 0.1f)
        assertTrue("should be taller than the face", box.height > 0.15f)
        assertEquals("should stay centred on the face", 0.45f, (box.left + box.right) / 2f, 1e-5f)
    }

    @Test
    fun mostOfTheGrowthGoesDownwardsTowardsTheBody() {
        val faceTop = 0.3f
        val faceBottom = 0.45f
        val box = PersonBox.fromFace(0.4f, faceTop, 0.5f, faceBottom)
        val above = faceTop - box.top
        val below = box.bottom - faceBottom
        assertTrue("above $above below $below", below > above * 3f)
    }

    @Test
    fun boxesNeverLeaveTheFrame() {
        val corner = PersonBox.fromFace(0f, 0f, 0.2f, 0.2f)
        assertEquals(0f, corner.left, 0f)
        assertEquals(0f, corner.top, 0f)
        val far = PersonBox.fromFace(0.85f, 0.8f, 1f, 1f)
        assertEquals(1f, far.right, 0f)
        assertEquals(1f, far.bottom, 0f)
    }

    @Test
    fun aDegenerateFaceDoesNotProduceAnInvertedBox() {
        val box = PersonBox.fromFace(0.5f, 0.5f, 0.5f, 0.5f)
        assertTrue(box.right >= box.left)
        assertTrue(box.bottom >= box.top)
    }
}
