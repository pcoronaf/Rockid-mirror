package com.rokidmirror.receiver.control

import com.rokidmirror.protocol.viewport.FitMode
import com.rokidmirror.protocol.viewport.ViewportState
import kotlin.math.abs

/**
 * 2D head-controlled viewport (spec "Head-controlled viewport"): yaw -> horizontal offset,
 * pitch -> vertical offset, around a base viewport chosen on the phone. Dead zone, low-pass
 * filter, configurable gain, recenter, and hard clamping (done by the renderer through
 * ViewportMath). Uses relative orientation since recenter so drift cannot accumulate: the
 * offset is always a bounded function of (current - reference) orientation. Pure Kotlin.
 */
class HeadViewportController(
    var enabled: Boolean = false,
    /** Radians of head motion ignored around the reference. */
    var deadZoneRad: Float = 0.03f,
    /** Viewport fraction moved per radian beyond the dead zone. */
    var gain: Float = 1.2f,
    /** 0..1, higher = smoother/slower. */
    var smoothing: Float = 0.6f,
    /** Maximum offset from the base center, in normalized source coordinates. */
    var maxOffset: Float = 0.5f,
) {
    private var base = ViewportState()
    private var refYaw = Float.NaN
    private var refPitch = Float.NaN
    private var filteredYaw = 0f
    private var filteredPitch = 0f
    private var lastYawRaw = 0f
    private var lastPitchRaw = 0f

    fun setBase(state: ViewportState) { base = state }

    /** Makes the current head orientation the neutral position. */
    fun recenter() { refYaw = lastYawRaw; refPitch = lastPitchRaw; filteredYaw = 0f; filteredPitch = 0f }

    /** @return viewport to apply, or null when disabled / not applicable (FIT shows everything anyway). */
    fun onPose(yawRad: Float, pitchRad: Float): ViewportState? {
        lastYawRaw = yawRad; lastPitchRaw = pitchRad
        if (!enabled) return null
        if (refYaw.isNaN()) { refYaw = yawRad; refPitch = pitchRad }
        if (base.fitMode == FitMode.FIT) return null
        val dYaw = wrap(yawRad - refYaw)
        val dPitch = pitchRad - refPitch
        filteredYaw = filteredYaw * smoothing + dYaw * (1 - smoothing)
        filteredPitch = filteredPitch * smoothing + dPitch * (1 - smoothing)
        val ox = (applyDeadZone(filteredYaw) * gain).coerceIn(-maxOffset, maxOffset)
        val oy = (-applyDeadZone(filteredPitch) * gain).coerceIn(-maxOffset, maxOffset) // looking up -> move viewport up (smaller centerY)
        return base.copy(centerX = base.centerX + ox, centerY = base.centerY + oy)
    }

    private fun applyDeadZone(v: Float): Float = if (abs(v) < deadZoneRad) 0f else v - deadZoneRad * (if (v > 0) 1 else -1)

    private fun wrap(a: Float): Float { var r = a; while (r > Math.PI) r -= (2 * Math.PI).toFloat(); while (r < -Math.PI) r += (2 * Math.PI).toFloat(); return r }
}
