package com.rokidmirror.sender.control

import kotlin.math.abs
import kotlin.math.hypot

/** Cursor position in normalized captured-source coordinates (0..1). */
data class PointerPosition(val x: Float, val y: Float)

/**
 * Pure trackpad maths for mouse mode: turns drags on the phone's control pad into an absolute
 * cursor position over the mirrored frame. Relative movement with mild acceleration, so a small
 * pad can reach every corner of a tall phone screen without losing precision for small moves.
 *
 * Holds no Android types so it is unit-testable.
 */
class PointerController(
    /** Pad travel of 1.0 (its full width) moves the cursor this fraction of the frame. */
    var gain: Float = 1.1f,
    /** Extra multiplier applied to fast flicks; 1.0 disables acceleration. */
    var acceleration: Float = 2.2f,
    /** Movement below this pad fraction per event is treated as precision movement. */
    var precisionThreshold: Float = 0.01f,
) {
    var position = PointerPosition(0.5f, 0.5f); private set

    /** Aspect ratio correction keeps pad movement isotropic on a tall source frame. */
    var sourceWidth: Int = 1080
    var sourceHeight: Int = 2340

    fun reset(x: Float = 0.5f, y: Float = 0.5f) { position = PointerPosition(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f)) }

    fun moveTo(x: Float, y: Float): PointerPosition {
        position = PointerPosition(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
        return position
    }

    /**
     * @param dxPad horizontal drag as a fraction of the pad width
     * @param dyPad vertical drag as a fraction of the pad height
     */
    fun moveBy(dxPad: Float, dyPad: Float): PointerPosition {
        val speed = hypot(dxPad, dyPad)
        val factor = if (speed <= precisionThreshold) gain else gain * (1f + (acceleration - 1f) * minOf(1f, speed / 0.15f))
        // The pad is square-ish while the frame is tall: scale Y so a diagonal drag stays diagonal.
        val aspect = if (sourceWidth > 0 && sourceHeight > 0) sourceWidth.toFloat() / sourceHeight else 1f
        val nx = position.x + dxPad * factor
        val ny = position.y + dyPad * factor * (1f / aspect.coerceAtLeast(0.05f)).coerceAtMost(4f)
        position = PointerPosition(nx.coerceIn(0f, 1f), ny.coerceIn(0f, 1f))
        return position
    }

    /** Cursor in captured-source pixels, which equal phone screen pixels for whole-screen capture. */
    fun toSourcePixels(): Pair<Int, Int> =
        (position.x * sourceWidth).toInt().coerceIn(0, maxOf(0, sourceWidth - 1)) to
            (position.y * sourceHeight).toInt().coerceIn(0, maxOf(0, sourceHeight - 1))

    fun isAtEdge(): Boolean = position.x <= 0f || position.y <= 0f || position.x >= 1f || position.y >= 1f

    fun movedSince(other: PointerPosition, epsilon: Float = 0.001f): Boolean =
        abs(position.x - other.x) > epsilon || abs(position.y - other.y) > epsilon
}
