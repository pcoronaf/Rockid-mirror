package com.rokidmirror.protocol.viewport

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A region of the source frame in normalized coordinates, 0..1 on each axis. */
data class SourceRegion(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/** Placement of the full source image on the display, in display pixels; may exceed the display. */
data class Placement(val left: Float, val top: Float, val width: Float, val height: Float) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
}

/**
 * Pure viewport transforms used by the receiver renderer and the phone control UI.
 * Aspect ratio is always preserved; letterboxing appears only when the image is smaller than
 * the display on an axis (FIT or zoomed-out CUSTOM).
 */
object ViewportMath {
    const val MIN_SCALE = 0.5f
    const val MAX_SCALE = 8f

    fun fitScale(sourceW: Int, sourceH: Int, displayW: Int, displayH: Int): Float =
        min(displayW.toFloat() / sourceW, displayH.toFloat() / sourceH)

    fun fillScale(sourceW: Int, sourceH: Int, displayW: Int, displayH: Int): Float =
        max(displayW.toFloat() / sourceW, displayH.toFloat() / sourceH)

    /** Display pixels per source pixel for the state. */
    fun effectiveScale(state: ViewportState, sourceW: Int, sourceH: Int, displayW: Int, displayH: Int): Float {
        val fit = fitScale(sourceW, sourceH, displayW, displayH)
        return when (state.fitMode) {
            FitMode.FIT -> fit
            FitMode.FILL -> fillScale(sourceW, sourceH, displayW, displayH)
            FitMode.ACTUAL -> 1f
            FitMode.CUSTOM -> fit * state.scale.coerceIn(MIN_SCALE, MAX_SCALE)
        }
    }

    /** Multiplier relative to FIT that ACTUAL mode corresponds to (used when switching ACTUAL -> CUSTOM). */
    fun actualAsCustomScale(sourceW: Int, sourceH: Int, displayW: Int, displayH: Int): Float =
        1f / fitScale(sourceW, sourceH, displayW, displayH)

    /**
     * Clamps the center so the display never shows area outside the source, unless the image is
     * smaller than the display on that axis, in which case it is centered (intentional letterbox).
     */
    fun clamp(state: ViewportState, sourceW: Int, sourceH: Int, displayW: Int, displayH: Int): ViewportState {
        if (sourceW <= 0 || sourceH <= 0 || displayW <= 0 || displayH <= 0) return state
        val s = effectiveScale(state, sourceW, sourceH, displayW, displayH)
        val imgW = sourceW * s
        val imgH = sourceH * s
        val cx = clampAxis(state.centerX, imgW, displayW)
        val cy = clampAxis(state.centerY, imgH, displayH)
        return state.copy(scale = state.scale.coerceIn(MIN_SCALE, MAX_SCALE), centerX = cx, centerY = cy)
    }

    private fun clampAxis(center: Float, imageSize: Float, displaySize: Int): Float {
        if (imageSize <= displaySize + 0.5f) return 0.5f
        val half = displaySize / (2f * imageSize)
        return center.coerceIn(half, 1f - half)
    }

    /** Where to place the full source image so that (centerX, centerY) sits at the display center. */
    fun placement(state: ViewportState, sourceW: Int, sourceH: Int, displayW: Int, displayH: Int): Placement {
        val c = clamp(state, sourceW, sourceH, displayW, displayH)
        val s = effectiveScale(c, sourceW, sourceH, displayW, displayH)
        val imgW = sourceW * s
        val imgH = sourceH * s
        val left = displayW / 2f - c.centerX * imgW
        val top = displayH / 2f - c.centerY * imgH
        return Placement(left, top, imgW, imgH)
    }

    /**
     * Which part of the source frame the glasses are actually showing, as normalized source
     * coordinates. This is what lets the phone draw a map of the wearer's view without any
     * pixels travelling back.
     */
    fun visibleSourceRegion(state: ViewportState, sourceW: Int, sourceH: Int, displayW: Int, displayH: Int): SourceRegion {
        if (sourceW <= 0 || sourceH <= 0 || displayW <= 0 || displayH <= 0) return SourceRegion(0f, 0f, 1f, 1f)
        val clamped = clamp(state, sourceW, sourceH, displayW, displayH)
        val scale = effectiveScale(clamped, sourceW, sourceH, displayW, displayH)
        val visibleWidth = (displayW / (sourceW * scale)).coerceAtMost(1f)
        val visibleHeight = (displayH / (sourceH * scale)).coerceAtMost(1f)
        return SourceRegion(
            left = (clamped.centerX - visibleWidth / 2f).coerceIn(0f, 1f),
            top = (clamped.centerY - visibleHeight / 2f).coerceIn(0f, 1f),
            right = (clamped.centerX + visibleWidth / 2f).coerceIn(0f, 1f),
            bottom = (clamped.centerY + visibleHeight / 2f).coerceIn(0f, 1f),
        )
    }

    /** Pan by a fraction of the visible viewport (e.g. drag delta / display size). */
    fun pan(state: ViewportState, dxViewportFraction: Float, dyViewportFraction: Float, sourceW: Int, sourceH: Int, displayW: Int, displayH: Int): ViewportState {
        val s = effectiveScale(state, sourceW, sourceH, displayW, displayH)
        val visibleW = displayW / (sourceW * s)
        val visibleH = displayH / (sourceH * s)
        return clamp(state.copy(centerX = state.centerX + dxViewportFraction * visibleW, centerY = state.centerY + dyViewportFraction * visibleH), sourceW, sourceH, displayW, displayH)
    }

    /** Zoom by a factor around the current center, switching to CUSTOM. */
    fun zoom(state: ViewportState, factor: Float, sourceW: Int, sourceH: Int, displayW: Int, displayH: Int): ViewportState {
        val currentAsCustom = when (state.fitMode) {
            FitMode.CUSTOM -> state.scale
            else -> effectiveScale(state, sourceW, sourceH, displayW, displayH) / fitScale(sourceW, sourceH, displayW, displayH)
        }
        val next = (currentAsCustom * factor).coerceIn(MIN_SCALE, MAX_SCALE)
        return clamp(state.copy(fitMode = FitMode.CUSTOM, scale = next), sourceW, sourceH, displayW, displayH)
    }

    /** After an orientation/size change keep the mode but recenter (spec: center automatically). */
    fun onSourceChanged(state: ViewportState): ViewportState = state.copy(centerX = 0.5f, centerY = 0.5f)

    fun approximatelyEqual(a: ViewportState, b: ViewportState, eps: Float = 1e-3f): Boolean =
        a.fitMode == b.fitMode && abs(a.scale - b.scale) < eps && abs(a.centerX - b.centerX) < eps && abs(a.centerY - b.centerY) < eps
}
