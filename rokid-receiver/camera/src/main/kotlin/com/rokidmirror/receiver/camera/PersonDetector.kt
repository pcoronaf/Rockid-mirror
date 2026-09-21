package com.rokidmirror.receiver.camera

/** A person located in the frame, in normalized coordinates of the displayed image. */
data class DetectedPerson(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/** Boundary for whatever finds people in a frame, so the model can be swapped. */
interface PersonDetector {
    /** @return people found, in normalized coordinates. Called off the main thread. */
    fun detect(frame: VisionFrame): List<DetectedPerson>
    fun close()
    val description: String
}

/** One enhanced frame ready to display and to search. */
class VisionFrame(
    val pixels: IntArray,
    val width: Int,
    val height: Int,
    /** Rotation to apply when displaying, so the world is the right way up. */
    val rotationDegrees: Int,
)

/**
 * Turns a detected face into a box around the person.
 *
 * Detection finds faces, so the box is grown to head and shoulders: wider than the face, taller
 * downwards, and clamped to the frame. Pure maths, unit tested, no Android types.
 */
object PersonBox {
    const val WIDTH_FACTOR = 2.2f
    const val HEIGHT_FACTOR = 2.8f
    /** How much of the growth goes above the face rather than below it. */
    const val ABOVE_SHARE = 0.18f

    fun fromFace(left: Float, top: Float, right: Float, bottom: Float): DetectedPerson {
        val faceWidth = (right - left).coerceAtLeast(0f)
        val faceHeight = (bottom - top).coerceAtLeast(0f)
        val centreX = (left + right) / 2f
        val newWidth = faceWidth * WIDTH_FACTOR
        val newHeight = faceHeight * HEIGHT_FACTOR
        val growth = newHeight - faceHeight
        return DetectedPerson(
            left = (centreX - newWidth / 2f).coerceIn(0f, 1f),
            top = (top - growth * ABOVE_SHARE).coerceIn(0f, 1f),
            right = (centreX + newWidth / 2f).coerceIn(0f, 1f),
            bottom = (bottom + growth * (1f - ABOVE_SHARE)).coerceIn(0f, 1f),
        )
    }
}
