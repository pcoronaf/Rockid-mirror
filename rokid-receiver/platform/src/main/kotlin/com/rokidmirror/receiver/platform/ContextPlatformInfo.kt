package com.rokidmirror.receiver.platform

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display

/**
 * Platform facts that do not need an Activity, so a background service can answer CAPABILITIES
 * before any window exists. The Activity-bound parts (render target, keep-awake, input) stay in
 * [AndroidPlatformAdapter].
 */
object ContextPlatformInfo {
    fun describe(): PlatformDescription = PlatformDescription(
        model = "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})",
        os = "Android ${Build.VERSION.RELEASE} build ${Build.DISPLAY}",
        apiLevel = Build.VERSION.SDK_INT,
        abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "?",
    )

    fun displayInfo(context: Context): DisplayInfo {
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = manager.getDisplay(Display.DEFAULT_DISPLAY)
            ?: return DisplayInfo(480, 640, 240, 60f)
        val metrics = DisplayMetrics()
        // getRealMetrics is deprecated in favour of WindowMetrics, which needs a visual context.
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        return DisplayInfo(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi, display.refreshRate)
    }

    fun sensors(context: Context): SensorCapabilities {
        val manager = runCatching { context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager }.getOrNull()
        val rotation = manager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) ?: manager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        return SensorCapabilities(
            rotationVector = if (rotation != null) Support.UNVERIFIED else Support.UNAVAILABLE,
            gyroscope = if (manager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null) Support.UNVERIFIED else Support.UNAVAILABLE,
        )
    }
}
