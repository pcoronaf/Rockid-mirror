package com.rokidmirror.sender.computer

import com.rokidmirror.sender.privilege.ShellBridge
import com.rokidmirror.sender.telemetry.MirrorLog

/** Android key codes computer mode sends, so the session and the UI cannot disagree on them. */
object ComputerKeys {
    const val BACK = 4
    const val DPAD_UP = 19
    const val DPAD_DOWN = 20
    const val DPAD_LEFT = 21
    const val DPAD_RIGHT = 22
    const val TAB = 61
    const val ENTER = 66
    const val DEL = 67
    const val ESCAPE = 111
    const val APP_SWITCH = 187
}

/** Pure argument builders for the shell commands computer mode runs; unit-tested off-device. */
object ComputerCommands {
    const val LONG_PRESS_MS = 600
    const val SWIPE_MS = 120

    fun launch(displayId: Int, componentFlattened: String): Array<String> =
        arrayOf("am", "start", "--display", displayId.toString(), "-n", componentFlattened)

    fun home(displayId: Int): Array<String> =
        arrayOf("am", "start", "--display", displayId.toString(), "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME")

    fun tap(displayId: Int, x: Int, y: Int): Array<String> =
        arrayOf("input", "-d", displayId.toString(), "tap", x.toString(), y.toString())

    fun swipe(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Array<String> =
        arrayOf("input", "-d", displayId.toString(), "swipe", x1.toString(), y1.toString(), x2.toString(), y2.toString(), durationMs.toString())

    fun key(displayId: Int, keyCode: Int): Array<String> =
        arrayOf("input", "-d", displayId.toString(), "keyevent", keyCode.toString())

    /**
     * Types a whole string in one go. The argument vector is handed to the process directly, with
     * no shell in between, so spaces and punctuation survive without the %s escaping the `input`
     * command needs when a shell splits the line.
     */
    fun text(displayId: Int, value: String): Array<String> =
        arrayOf("input", "-d", displayId.toString(), "text", value)
}

/**
 * Drives a running computer display: launches an app onto it and injects input there.
 *
 * Input goes through the shell `input` command (tap/swipe/keyevent) on the target display, which
 * works for any app on a trusted display without an accessibility service. A low-latency pointer
 * path comes later; the foundation needs reliable clicks and basic scrolling, which this gives.
 */
class ComputerControl(val displayId: Int, val width: Int, val height: Int) {
    private companion object {
        const val TAG = "ComputerControl"
    }

    private fun run(args: Array<String>): Boolean {
        val result = ShellBridge.execute(*args)
        if (!result.succeeded) MirrorLog.w(TAG, "command_failed", "cmd" to args.firstOrNull(), "err" to result.error)
        return result.succeeded
    }

    fun launch(componentFlattened: String): Boolean = run(ComputerCommands.launch(displayId, componentFlattened))
    fun home(): Boolean = run(ComputerCommands.home(displayId))
    fun back(): Boolean = run(ComputerCommands.key(displayId, ComputerKeys.BACK))

    fun tapAt(normalizedX: Float, normalizedY: Float): Boolean {
        val (x, y) = pixels(normalizedX, normalizedY)
        return run(ComputerCommands.tap(displayId, x, y))
    }

    fun longPressAt(normalizedX: Float, normalizedY: Float): Boolean {
        val (x, y) = pixels(normalizedX, normalizedY)
        return run(ComputerCommands.swipe(displayId, x, y, x, y, ComputerCommands.LONG_PRESS_MS))
    }

    /** A drag from the cursor, [dxPixels]/[dyPixels] in source pixels; used for scrolling. */
    fun scrollFrom(normalizedX: Float, normalizedY: Float, dxPixels: Float, dyPixels: Float): Boolean {
        val (x, y) = pixels(normalizedX, normalizedY)
        val x2 = (x + dxPixels).toInt().coerceIn(0, width - 1)
        val y2 = (y + dyPixels).toInt().coerceIn(0, height - 1)
        return run(ComputerCommands.swipe(displayId, x, y, x2, y2, ComputerCommands.SWIPE_MS))
    }

    fun key(keyCode: Int): Boolean = run(ComputerCommands.key(displayId, keyCode))

    fun type(value: String): Boolean = value.isNotEmpty() && run(ComputerCommands.text(displayId, value))

    private fun pixels(nx: Float, ny: Float): Pair<Int, Int> =
        (nx.coerceIn(0f, 1f) * (width - 1)).toInt() to (ny.coerceIn(0f, 1f) * (height - 1)).toInt()
}
