package com.rokidmirror.protocol.viewport

import kotlin.math.max
import kotlin.math.min

enum class FitMode {
    /** Whole source visible, letterboxed. */
    FIT,
    /** Display fully covered, source cropped. */
    FILL,
    /** One source pixel = one display pixel. */
    ACTUAL,
    /** User-controlled zoom. */
    CUSTOM;

    companion object { fun fromWire(s: String): FitMode = entries.firstOrNull { it.name == s } ?: FIT }
}

/**
 * Normalized viewport shared by phone controls and glasses renderer.
 * `scale` is a multiplier on top of the FIT scale (1.0 = whole frame visible) and is only used
 * for [FitMode.CUSTOM]; `centerX/centerY` (0..1) is the source point shown at display center.
 */
data class ViewportState(
    val scale: Float = 1f,
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
    val fitMode: FitMode = FitMode.FIT,
)
