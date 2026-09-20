package com.rokidmirror.sender.control

import com.rokidmirror.protocol.viewport.FitMode
import com.rokidmirror.protocol.viewport.ViewportMath
import com.rokidmirror.protocol.viewport.ViewportState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phone-side model of the glasses viewport. Applies the same clamping math as the receiver so
 * the sliders and gesture pad never produce an invalid state; [onChanged] is invoked with the
 * clamped state for the transport to send (throttled by the session).
 */
class ViewportController(private val onChanged: (ViewportState) -> Unit) {
    private val _state = MutableStateFlow(ViewportState())
    val state: StateFlow<ViewportState> = _state.asStateFlow()

    var sourceWidth = 1080; private set
    var sourceHeight = 2340; private set
    var displayWidth = 480; private set
    var displayHeight = 640; private set

    fun setGeometry(sourceW: Int, sourceH: Int, displayW: Int, displayH: Int, recenter: Boolean) {
        val changed = sourceW != sourceWidth || sourceH != sourceHeight
        sourceWidth = sourceW; sourceHeight = sourceH; displayWidth = displayW; displayHeight = displayH
        val next = if (changed && recenter) ViewportMath.onSourceChanged(_state.value) else _state.value
        apply(next)
    }

    fun apply(state: ViewportState) {
        val clamped = ViewportMath.clamp(state, sourceWidth, sourceHeight, displayWidth, displayHeight)
        if (!ViewportMath.approximatelyEqual(clamped, _state.value)) {
            _state.value = clamped
            onChanged(clamped)
        } else {
            _state.value = clamped
        }
    }

    fun setFitMode(mode: FitMode) {
        val current = _state.value
        val next = when (mode) {
            FitMode.CUSTOM -> current.copy(fitMode = FitMode.CUSTOM, scale = if (current.fitMode == FitMode.CUSTOM) current.scale else ViewportMath.effectiveScale(current, sourceWidth, sourceHeight, displayWidth, displayHeight) / ViewportMath.fitScale(sourceWidth, sourceHeight, displayWidth, displayHeight))
            else -> current.copy(fitMode = mode, centerX = 0.5f, centerY = 0.5f)
        }
        apply(next)
    }

    fun setZoom(scale: Float) = apply(_state.value.copy(fitMode = FitMode.CUSTOM, scale = scale))
    fun zoomBy(factor: Float) = apply(ViewportMath.zoom(_state.value, factor, sourceWidth, sourceHeight, displayWidth, displayHeight))
    fun setCenter(x: Float, y: Float) = apply(_state.value.copy(centerX = x, centerY = y))
    fun panByViewportFraction(dx: Float, dy: Float) = apply(ViewportMath.pan(_state.value, dx, dy, sourceWidth, sourceHeight, displayWidth, displayHeight))
    fun resetToFit() = apply(ViewportState())

    /** Current zoom expressed as a CUSTOM multiplier (for the slider even in FIT/FILL/ACTUAL). */
    fun zoomMultiplier(): Float = when (_state.value.fitMode) {
        FitMode.CUSTOM -> _state.value.scale
        else -> ViewportMath.effectiveScale(_state.value, sourceWidth, sourceHeight, displayWidth, displayHeight) / ViewportMath.fitScale(sourceWidth, sourceHeight, displayWidth, displayHeight)
    }
}
