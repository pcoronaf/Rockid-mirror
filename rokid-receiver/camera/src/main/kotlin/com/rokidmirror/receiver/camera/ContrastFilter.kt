package com.rokidmirror.receiver.camera

/**
 * Turns a camera luma plane into a high-contrast greyscale image for the glasses.
 *
 * The waveguide is a dim, single-colour panel, so a raw camera frame reads as mush on it. This
 * stretches the useful part of the histogram across the full range, which is what makes shapes
 * and faces stand out. Pure functions on plain arrays, so the behaviour is unit-testable without
 * a camera or a device.
 */
object ContrastFilter {
    /** Fraction of pixels ignored at each end of the histogram before stretching. */
    const val DEFAULT_CLIP = 0.02f

    /** Luma bounds covering the central portion of the histogram, as [low, high]. */
    fun percentileBounds(luma: ByteArray, width: Int, height: Int, clip: Float = DEFAULT_CLIP, rowStride: Int = width): IntArray {
        val histogram = IntArray(256)
        var counted = 0
        for (y in 0 until height) {
            val row = y * rowStride
            for (x in 0 until width) {
                histogram[luma[row + x].toInt() and 0xFF]++
                counted++
            }
        }
        if (counted == 0) return intArrayOf(0, 255)
        val drop = (counted * clip).toInt()

        var low = 0
        var seen = 0
        while (low < 255 && seen + histogram[low] <= drop) { seen += histogram[low]; low++ }

        var high = 255
        seen = 0
        while (high > low && seen + histogram[high] <= drop) { seen += histogram[high]; high-- }

        return intArrayOf(low, high)
    }

    /**
     * Maps luma to opaque ARGB, stretching [low, high] across the full range.
     *
     * @param gain extra contrast around mid grey, 1.0 for none
     * @param out reused pixel buffer of width * height
     */
    fun toArgb(
        luma: ByteArray,
        width: Int,
        height: Int,
        low: Int,
        high: Int,
        gain: Float = 1f,
        rowStride: Int = width,
        out: IntArray = IntArray(width * height),
    ): IntArray {
        require(out.size >= width * height) { "output buffer too small" }
        val span = (high - low).coerceAtLeast(1)
        val table = IntArray(256)
        for (value in 0..255) {
            val stretched = ((value - low) * 255f / span).coerceIn(0f, 255f)
            val boosted = ((stretched - 128f) * gain + 128f).coerceIn(0f, 255f)
            val level = boosted.toInt()
            table[value] = (0xFF shl 24) or (level shl 16) or (level shl 8) or level
        }
        for (y in 0 until height) {
            val src = y * rowStride
            val dst = y * width
            for (x in 0 until width) {
                out[dst + x] = table[luma[src + x].toInt() and 0xFF]
            }
        }
        return out
    }

    /** Convenience: measure this frame and convert it in one step. */
    fun enhance(luma: ByteArray, width: Int, height: Int, gain: Float = 1f, clip: Float = DEFAULT_CLIP, rowStride: Int = width, out: IntArray = IntArray(width * height)): IntArray {
        val bounds = percentileBounds(luma, width, height, clip, rowStride)
        return toArgb(luma, width, height, bounds[0], bounds[1], gain, rowStride, out)
    }
}
