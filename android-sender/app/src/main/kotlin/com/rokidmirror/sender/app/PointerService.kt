package com.rokidmirror.sender.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import com.rokidmirror.sender.telemetry.MirrorLog

/**
 * Touch injection for mouse mode.
 *
 * Android gives no-root apps exactly one way to deliver a tap to another app: an accessibility
 * service with gesture dispatch. This one is deliberately the smallest such service possible.
 * Its config requests no event types and no window-content access, so it cannot read the screen;
 * [onAccessibilityEvent] is empty and nothing here inspects the UI. It only plays back the
 * gestures the user makes on the phone's own control pad while wearing the glasses.
 *
 * The user must enable it by hand in Settings; the app cannot grant it.
 */
class PointerService : AccessibilityService() {
    companion object {
        private const val TAG = "Pointer"
        const val TAP_MS = 60L
        const val LONG_PRESS_MS = 650L
        const val SCROLL_MS = 160L

        @Volatile
        var instance: PointerService? = null
            private set

        fun isRunning(): Boolean = instance != null

        /** True when the user has switched the service on in Settings. */
        fun isEnabledInSettings(context: Context): Boolean {
            val expected = ComponentName(context, PointerService::class.java).flattenToString()
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            return enabled.split(':').any { it.equals(expected, ignoreCase = true) || it.endsWith("/${PointerService::class.java.name}") }
        }

        fun settingsIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * Which display gestures go to. Extended-screen mode points this at the virtual display so
     * clicks land there rather than on the phone. Requires API 30; below that only the default
     * display can be targeted.
     */
    @Volatile
    var targetDisplayId: Int = android.view.Display.DEFAULT_DISPLAY

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        MirrorLog.i(TAG, "pointer_service_connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        MirrorLog.i(TAG, "pointer_service_disconnected")
        return super.onUnbind(intent)
    }

    override fun onDestroy() { instance = null; super.onDestroy() }

    /** No events are requested and none are handled: this service never reads the screen. */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    fun tap(x: Float, y: Float) = stroke(pathTo(x, y), TAP_MS, "tap")

    fun longPress(x: Float, y: Float) = stroke(pathTo(x, y), LONG_PRESS_MS, "long_press")

    /** Scrolls content at (x, y): a swipe of [dyPixels] (negative scrolls the page down). */
    fun scroll(x: Float, y: Float, dxPixels: Float, dyPixels: Float) {
        val path = Path().apply {
            moveTo(x, y)
            lineTo((x + dxPixels).coerceAtLeast(0f), (y + dyPixels).coerceAtLeast(0f))
        }
        stroke(path, SCROLL_MS, "scroll")
    }

    /** Press, move, release: drags a slider or an icon. */
    fun drag(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long = 400L) {
        val path = Path().apply { moveTo(fromX, fromY); lineTo(toX, toY) }
        stroke(path, durationMs, "drag")
    }

    fun back() = global(GLOBAL_ACTION_BACK, "back")
    fun home() = global(GLOBAL_ACTION_HOME, "home")
    fun recents() = global(GLOBAL_ACTION_RECENTS, "recents")

    private fun pathTo(x: Float, y: Float) = Path().apply { moveTo(x, y) }

    private fun stroke(path: Path, durationMs: Long, what: String): Boolean {
        val builder = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R && targetDisplayId != android.view.Display.DEFAULT_DISPLAY) {
            runCatching { builder.setDisplayId(targetDisplayId) }
                .onFailure { MirrorLog.w(TAG, "set_display_failed", "display" to targetDisplayId, "error" to it.message) }
        }
        val gesture = builder.build()
        val ok = dispatchGesture(gesture, null, null)
        MirrorLog.d(TAG, "gesture", "what" to what, "dispatched" to ok)
        return ok
    }

    private fun global(action: Int, what: String): Boolean {
        val ok = performGlobalAction(action)
        MirrorLog.d(TAG, "global_action", "what" to what, "ok" to ok)
        return ok
    }
}
