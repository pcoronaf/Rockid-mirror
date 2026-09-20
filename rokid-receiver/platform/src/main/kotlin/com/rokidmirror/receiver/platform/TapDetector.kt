package com.rokidmirror.receiver.platform

/**
 * Separates a single tap from a double tap on the temple touch bar.
 *
 * A single tap cannot act immediately, or the first tap of a double tap would also fire. Each
 * tap therefore arms a timer; a second tap inside the window cancels it and reports a double
 * tap instead. Pure logic with an injected clock so the window boundaries are testable.
 */
class TapDetector(private val windowMs: Long = 320L) {
    enum class Decision {
        /** Arm the single-tap timer; call [onTimer] when it expires. */
        ARM_SINGLE,
        /** Second tap inside the window: report a double tap and cancel the armed timer. */
        DOUBLE,
    }

    private var armedAtMs: Long? = null

    fun onTap(nowMs: Long): Decision {
        val armed = armedAtMs
        if (armed != null && nowMs - armed <= windowMs) {
            armedAtMs = null
            return Decision.DOUBLE
        }
        armedAtMs = nowMs
        return Decision.ARM_SINGLE
    }

    /** @return true when the armed timer should still deliver a single tap. */
    fun onTimer(): Boolean {
        val armed = armedAtMs ?: return false
        armedAtMs = null
        return armed >= 0
    }

    fun reset() { armedAtMs = null }

    val isArmed: Boolean get() = armedAtMs != null
}
