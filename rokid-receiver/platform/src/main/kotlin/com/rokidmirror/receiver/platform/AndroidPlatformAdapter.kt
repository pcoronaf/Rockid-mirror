package com.rokidmirror.receiver.platform

import android.app.Activity
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.WindowManager
import com.rokidmirror.receiver.telemetry.ReceiverLog
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.math.asin
import kotlin.math.atan2

/**
 * Standard-Android implementation of [RokidPlatformAdapter].
 *
 * Verified from public documentation (see docs/rokid-sdk-findings.md): YodaOS-Sprite is
 * Android 12 / API 32, apps are ordinary APKs, the display is 480x640 @ 240 dpi. Everything
 * below is therefore plain framework API. Items marked UNVERIFIED must be confirmed on a real
 * RV101/RV102 during M0 (temple touch bar key codes, sensor availability); the app logs every
 * key code it receives so the audit can record the real values.
 */
class AndroidPlatformAdapter(private val activity: Activity) : RokidPlatformAdapter {
    private companion object {
        const val TAG = "Platform"
        const val DOUBLE_TAP_WINDOW_MS = 320L
        const val TAP_MAX_MS = 250L
        val TAP_KEYCODES = setOf(
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A,
        )
    }

    private val inputs = MutableSharedFlow<GlassesInput>(extraBufferCapacity = 16)
    private val raw = MutableSharedFlow<String>(extraBufferCapacity = 16)
    private val tapHandler = Handler(Looper.getMainLooper())
    private val tapDetector = TapDetector(DOUBLE_TAP_WINDOW_MS)
    private var pendingSingleTap: Runnable? = null
    // Optional: a stripped-down glasses runtime may not expose every system service.
    private val sensorManager = runCatching { activity.getSystemService(Activity.SENSOR_SERVICE) as? SensorManager }.getOrNull()

    override fun describe(): PlatformDescription = PlatformDescription(
        model = "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})",
        os = "Android ${Build.VERSION.RELEASE} build ${Build.DISPLAY}",
        apiLevel = Build.VERSION.SDK_INT,
        abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "?",
    )

    override fun getDisplayInfo(): DisplayInfo {
        val wm = activity.getSystemService(Activity.WINDOW_SERVICE) as WindowManager
        val bounds = wm.currentWindowMetrics.bounds
        val refresh = activity.display?.refreshRate ?: 60f
        return DisplayInfo(bounds.width(), bounds.height(), activity.resources.displayMetrics.densityDpi, refresh)
    }

    override fun createRenderTarget(): RenderTarget = RenderTarget(SurfaceView(activity))

    /** True when this build could reach the platform's sensor service at all. */
    val sensorServiceAvailable: Boolean get() = sensorManager != null

    override fun setKeepAwake(enabled: Boolean) {
        if (enabled) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun getInputCapabilities() = InputCapabilities(touchBar = Support.UNVERIFIED, hardwareKeys = Support.UNVERIFIED)

    /**
     * A tap is only delivered after [DOUBLE_TAP_WINDOW_MS] so that the first tap of a double tap
     * does not also act. Other Rokid apps exit on a double tap and users expect that here too.
     */
    private fun onTap() {
        when (tapDetector.onTap(android.os.SystemClock.uptimeMillis())) {
            TapDetector.Decision.DOUBLE -> {
                pendingSingleTap?.let { tapHandler.removeCallbacks(it) }
                pendingSingleTap = null
                ReceiverLog.i(TAG, "double_tap")
                inputs.tryEmit(GlassesInput.DoubleTap)
            }
            TapDetector.Decision.ARM_SINGLE -> {
                val runnable = Runnable {
                    pendingSingleTap = null
                    if (tapDetector.onTimer()) inputs.tryEmit(GlassesInput.Select)
                }
                pendingSingleTap = runnable
                tapHandler.postDelayed(runnable, DOUBLE_TAP_WINDOW_MS)
            }
        }
    }

    /**
     * Forward from `Activity.dispatchTouchEvent`. If the temple bar reports taps as touches
     * rather than key events, double tap to exit still works.
     */
    fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            raw.tryEmit("touch up ${event.eventTime - event.downTime}ms src=${event.source}")
        }
        if (event.action == MotionEvent.ACTION_UP && event.eventTime - event.downTime < TAP_MAX_MS) {
            ReceiverLog.d(TAG, "touch_tap", "x" to event.x.toInt(), "y" to event.y.toInt())
            onTap()
            return true
        }
        return false
    }

    override fun getSensorCapabilities(): SensorCapabilities = SensorCapabilities(
        rotationVector = if (rotationSensor() != null) Support.UNVERIFIED else Support.UNAVAILABLE,
        gyroscope = if (sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null) Support.UNVERIFIED else Support.UNAVAILABLE,
    )

    private fun rotationSensor(): Sensor? = sensorManager?.let {
        it.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) ?: it.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    }

    override fun inputEvents(): Flow<GlassesInput> = inputs.asSharedFlow()

    override fun rawInput(): Flow<String> = raw.asSharedFlow()

    /**
     * Call from `Activity.dispatchKeyEvent`. Mapping is a hypothesis (UNVERIFIED): community
     * Rokid apps navigate with D-pad style events from the temple touch bar. Every code is
     * logged so the M0 audit can pin the real mapping down.
     */
    fun onKeyEvent(event: KeyEvent): Boolean {
        ReceiverLog.d(TAG, "key_event", "code" to event.keyCode, "action" to event.action, "repeat" to event.repeatCount, "long" to event.isLongPress, "device" to event.device?.name)
        if (event.action == KeyEvent.ACTION_UP) {
            raw.tryEmit("key ${KeyEvent.keyCodeToString(event.keyCode)} (${event.keyCode}) from ${event.device?.name ?: "?"}")
        }
        if (event.action != KeyEvent.ACTION_UP && !(event.action == KeyEvent.ACTION_DOWN && event.isLongPress)) return event.keyCode != KeyEvent.KEYCODE_BACK
        if (!event.isLongPress && event.keyCode in TAP_KEYCODES) {
            onTap()
            return true
        }
        val input = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A ->
                if (event.isLongPress) GlassesInput.LongPress else GlassesInput.Select
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_TAB -> GlassesInput.Forward
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> GlassesInput.Backward
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> GlassesInput.Back
            else -> GlassesInput.Unknown(event.keyCode)
        }
        inputs.tryEmit(input)
        return input !is GlassesInput.Unknown
    }

    override fun headPose(): Flow<HeadPose> = callbackFlow {
        val sm = sensorManager
        val sensor = rotationSensor()
        if (sm == null || sensor == null) { close(); return@callbackFlow }
        val rotation = FloatArray(9)
        val orientation = FloatArray(3)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rotation, event.values)
                SensorManager.getOrientation(rotation, orientation)
                // orientation[0] = azimuth (yaw), [1] = pitch (positive looking down in Android's frame)
                trySend(HeadPose(yawRad = orientation[0], pitchRad = -orientation[1], timestampNs = event.timestamp))
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        awaitClose { sm.unregisterListener(listener) }
    }
}
