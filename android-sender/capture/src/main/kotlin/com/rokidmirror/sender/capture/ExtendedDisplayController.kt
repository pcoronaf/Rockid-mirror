package com.rokidmirror.sender.capture

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.os.HandlerThread
import android.view.Display
import android.view.Surface
import com.rokidmirror.protocol.control.ErrorCode
import com.rokidmirror.protocol.control.MirrorException
import com.rokidmirror.sender.telemetry.MirrorLog

/** An installed app that can be launched onto the extended display. */
data class LaunchableApp(val packageName: String, val label: String, val icon: Drawable?)

/**
 * Extended-screen mode: a second, independent display that lives only on the glasses.
 *
 * Unlike mirroring this needs no MediaProjection consent. A virtual display created with
 * `VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY` shows nothing but what is explicitly launched onto it,
 * which is exactly a second screen, and that combination is available to ordinary apps.
 *
 * Whether the platform lets this app launch *other* apps onto the display is a policy decision
 * that has tightened over Android versions, so [launch] reports refusals instead of hiding them.
 */
class ExtendedDisplayController(private val context: Context) {
    private companion object { const val TAG = "Extended" }

    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val thread = HandlerThread("extended-display").apply { start() }
    private val handler = Handler(thread.looper)

    private var virtualDisplay: VirtualDisplay? = null

    var displayId: Int = Display.INVALID_DISPLAY
        private set
    var geometry: CaptureGeometry? = null
        private set

    val isActive: Boolean get() = virtualDisplay != null

    fun start(width: Int, height: Int, densityDpi: Int, surface: Surface): Int {
        stop()
        val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
        val vd = try {
            displayManager.createVirtualDisplay("RokidMirrorExtended", width, height, densityDpi, surface, flags, null, handler)
        } catch (e: SecurityException) {
            throw MirrorException(ErrorCode.PLATFORM_API_UNAVAILABLE, "the system refused a public virtual display: ${e.message}", e)
        } ?: throw MirrorException(ErrorCode.PLATFORM_API_UNAVAILABLE, "createVirtualDisplay returned null")
        virtualDisplay = vd
        displayId = vd.display?.displayId ?: Display.INVALID_DISPLAY
        geometry = CaptureGeometry(width, height, densityDpi)
        MirrorLog.i(TAG, "extended_display_created", "id" to displayId, "w" to width, "h" to height, "dpi" to densityDpi)
        return displayId
    }

    fun resize(width: Int, height: Int, densityDpi: Int) {
        virtualDisplay?.resize(width, height, densityDpi)
        geometry = CaptureGeometry(width, height, densityDpi)
    }

    fun setSurface(surface: Surface?) { virtualDisplay?.surface = surface }

    fun stop() {
        virtualDisplay?.let { runCatching { it.release() } }
        virtualDisplay = null
        displayId = Display.INVALID_DISPLAY
        geometry = null
    }

    /** Apps with a launcher entry, visible to us through the manifest's `queries` declaration. */
    fun launchableApps(): List<LaunchableApp> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .asSequence()
            .filter { it.activityInfo.packageName != context.packageName }
            .map { LaunchableApp(it.activityInfo.packageName, it.loadLabel(pm).toString(), runCatching { it.loadIcon(pm) }.getOrNull()) }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    /**
     * Tries to start an app on the extended display. Android refuses activity launches onto a
     * virtual display an ordinary app created, even the app's own, so this is expected to fail
     * and is offered only because the policy differs by device and version.
     */
    fun launch(packageName: String): Result<Unit> {
        val id = displayId
        if (id == Display.INVALID_DISPLAY) return Result.failure(MirrorException(ErrorCode.PLATFORM_API_UNAVAILABLE, "extended display is not running"))
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return Result.failure(MirrorException(ErrorCode.UNKNOWN, "$packageName has no launch activity"))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(id)
        return try {
            context.startActivity(intent, options.toBundle())
            MirrorLog.i(TAG, "launched_on_extended", "package" to packageName, "display" to id)
            Result.success(Unit)
        } catch (e: SecurityException) {
            MirrorLog.w(TAG, "launch_refused", "package" to packageName, "error" to e.message)
            Result.failure(MirrorException(ErrorCode.PLATFORM_API_UNAVAILABLE, "Android refused to launch $packageName on a second display: ${e.message}", e))
        } catch (e: Exception) {
            MirrorLog.w(TAG, "launch_failed", "package" to packageName, "error" to e.message)
            Result.failure(MirrorException(ErrorCode.UNKNOWN, e.message, e))
        }
    }
}
