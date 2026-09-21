package com.rokidmirror.receiver.platform

import android.view.SurfaceView
import kotlinx.coroutines.flow.Flow

data class DisplayInfo(val width: Int, val height: Int, val densityDpi: Int, val refreshHz: Float)

/** Verification status of a platform feature (spec: never claim an API that was not verified on device). */
enum class Support { VERIFIED, UNVERIFIED, UNAVAILABLE }

data class InputCapabilities(
    /** Temple touch bar. Delivery mechanism (KeyEvents) is documented but not yet verified on RV101/RV102. */
    val touchBar: Support,
    val hardwareKeys: Support,
)

data class SensorCapabilities(
    val rotationVector: Support,
    val gyroscope: Support,
)

data class PlatformDescription(val model: String, val os: String, val apiLevel: Int, val abi: String)

/** The surface video is rendered into plus its host for layout-based zoom/pan. */
class RenderTarget(val surfaceView: SurfaceView)

/** Discrete input gestures the receiver understands. */
sealed class GlassesInput {
    object Select : GlassesInput()
    /** Two taps in quick succession on the temple touch bar; Rokid apps use it to exit. */
    object DoubleTap : GlassesInput()
    object Back : GlassesInput()
    object Forward : GlassesInput()
    object Backward : GlassesInput()
    object LongPress : GlassesInput()
    data class Unknown(val keyCode: Int) : GlassesInput()
}

/** Head orientation in radians; yaw positive = turning right, pitch positive = looking up. */
data class HeadPose(val yawRad: Float, val pitchRad: Float, val timestampNs: Long)

/**
 * Architectural boundary from the specification: every Rokid-specific runtime access goes
 * through this interface. The shipped implementation ([AndroidPlatformAdapter]) uses only
 * standard Android APIs, which is all the video path needs on YodaOS-Sprite (Android 12).
 * A Rokid Glasses SDK backed implementation can be added later without touching the rest.
 */
interface RokidPlatformAdapter {
    fun describe(): PlatformDescription
    fun getDisplayInfo(): DisplayInfo
    fun createRenderTarget(): RenderTarget
    fun setKeepAwake(enabled: Boolean)
    fun getInputCapabilities(): InputCapabilities
    fun getSensorCapabilities(): SensorCapabilities
    fun inputEvents(): Flow<GlassesInput>

    /**
     * Human-readable description of every raw input event, so the temple bar's real key codes
     * can be identified on a device with no visible log.
     */
    fun rawInput(): Flow<String>
    /** Emits nothing when no orientation sensor is available. */
    fun headPose(): Flow<HeadPose>
}
