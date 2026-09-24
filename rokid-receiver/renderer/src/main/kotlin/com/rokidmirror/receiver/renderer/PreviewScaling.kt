package com.rokidmirror.receiver.renderer

/**
 * Sizing for the snapshots the glasses send back to the phone. Kept separate and pure so the
 * arithmetic that decides how much bandwidth this costs is unit-testable.
 */
object PreviewScaling {
    const val MIN_EDGE = 16

    /** Largest size within [maxWidth] that keeps the display's aspect ratio. */
    fun scaledSize(sourceWidth: Int, sourceHeight: Int, maxWidth: Int): Pair<Int, Int> {
        if (sourceWidth <= 0 || sourceHeight <= 0) return MIN_EDGE to MIN_EDGE
        if (sourceWidth <= maxWidth) return sourceWidth to sourceHeight
        val scale = maxWidth.toFloat() / sourceWidth
        val width = maxWidth.coerceAtLeast(MIN_EDGE)
        val height = (sourceHeight * scale).toInt().coerceAtLeast(MIN_EDGE)
        return width to height
    }

    /** Period between snapshots for a requested rate, clamped to something a link can carry. */
    fun intervalMs(fps: Int): Long = (1000L / fps.coerceIn(1, 10))

    /**
     * Quality and width to try, in order, until an encoded snapshot fits the budget. Sending an
     * oversized one is not an option: it exceeds the control frame limit and costs the link.
     */
    fun attempts(quality: Int, maxWidth: Int): List<Pair<Int, Int>> {
        val startQuality = quality.coerceIn(20, 90)
        val startWidth = maxWidth.coerceIn(MIN_EDGE, 960)
        return listOf(
            startQuality to startWidth,
            (startQuality * 7 / 10).coerceAtLeast(25) to startWidth,
            (startQuality * 6 / 10).coerceAtLeast(25) to (startWidth * 7 / 10).coerceAtLeast(MIN_EDGE),
            30 to (startWidth / 2).coerceAtLeast(MIN_EDGE),
        )
    }
}
