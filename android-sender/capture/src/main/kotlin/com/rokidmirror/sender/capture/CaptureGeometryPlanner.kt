package com.rokidmirror.sender.capture

/**
 * Pure geometry helper: given the source content size and the negotiated encoded long edge,
 * returns the VirtualDisplay size the encoder surface should have. Aspect ratio is preserved;
 * dimensions are multiples of 16 as hardware encoders prefer.
 */
object CaptureGeometryPlanner {
    private const val ALIGN = 16

    fun encodedSize(sourceWidth: Int, sourceHeight: Int, longEdgeLimit: Int, shortEdgeLimit: Int = Int.MAX_VALUE): Pair<Int, Int> {
        require(sourceWidth > 0 && sourceHeight > 0)
        val landscape = sourceWidth >= sourceHeight
        val srcLong = maxOf(sourceWidth, sourceHeight).toDouble()
        val srcShort = minOf(sourceWidth, sourceHeight).toDouble()
        var scale = minOf(1.0, longEdgeLimit / srcLong)
        if (srcShort * scale > shortEdgeLimit) scale = shortEdgeLimit / srcShort
        val longEdge = align(srcLong * scale)
        val shortEdge = align(srcShort * scale)
        return if (landscape) longEdge to shortEdge else shortEdge to longEdge
    }

    /** Resolution steps used by the adaptive controller: 0 = 100 %, 1 = 75 %, 2 = 50 %. */
    fun applyResolutionStep(longEdge: Int, step: Int): Int = when (step) {
        0 -> longEdge
        1 -> align(longEdge * 0.75)
        else -> align(longEdge * 0.5)
    }

    private fun align(v: Double): Int = maxOf(ALIGN, (v / ALIGN).toInt() * ALIGN)
}
