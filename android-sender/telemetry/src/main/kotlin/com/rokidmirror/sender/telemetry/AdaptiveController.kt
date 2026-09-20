package com.rokidmirror.sender.telemetry

/** Inputs sampled once per evaluation period (about 1 s). Negative values mean "unknown". */
data class AdaptiveInputs(
    val lossFraction: Float,
    val rttMs: Float,
    /** Mean encoder latency T1-T0 over the period (backpressure signal for Surface encoders). */
    val encodeLatencyMs: Float,
    val receiverDecodeLagFrames: Int,
    val droppedFramesPerSecond: Float,
    val sendQueueDrops: Int,
)

/** Current stream shape the controller may change. */
data class AdaptiveLevel(val bitrate: Int, val fps: Int, val resolutionStep: Int)

sealed class AdaptiveAction {
    object None : AdaptiveAction()
    data class SetBitrate(val bitrate: Int) : AdaptiveAction()
    data class SetFps(val fps: Int) : AdaptiveAction()
    /** step 0 = negotiated size, 1 = 75%, 2 = 50%. */
    data class SetResolutionStep(val step: Int) : AdaptiveAction()
    data class RequestKeyframe(val reason: String) : AdaptiveAction()
}

/**
 * Deterministic adaptation (spec "Adaptive streaming"): under stress reduce bitrate, then
 * frame rate 60->30, then resolution; request a keyframe after severe loss; recover slowly
 * after a stable interval. No learning, no hidden state beyond the counters below.
 */
class AdaptiveController(
    private val minBitrate: Int,
    private val maxBitrate: Int,
    private val maxFps: Int,
    private val stableIntervalsBeforeUpgrade: Int = 5,
    /** Consecutive stressed periods required before a non-severe degradation. */
    private val stressPeriodsBeforeDegrade: Int = 2,
) {
    companion object {
        const val LOSS_MILD = 0.01f
        const val LOSS_SEVERE = 0.08f
        const val RTT_STRESS_MS = 80f
        const val ENCODE_LATENCY_STRESS_MS = 40f
        const val DECODE_LAG_STRESS_FRAMES = 3
        const val MAX_RESOLUTION_STEP = 2
    }

    var level = AdaptiveLevel(maxBitrate, maxFps, 0); private set
    private var stableCount = 0
    private var stressCount = 0
    private var lastKeyframeRequestPeriod = -10
    private var period = 0

    fun reset(startBitrate: Int, fps: Int) {
        level = AdaptiveLevel(startBitrate.coerceIn(minBitrate, maxBitrate), fps, 0)
        stableCount = 0
        stressCount = 0
    }

    /** Called once per period; returns at most one action so changes stay observable. */
    fun evaluate(input: AdaptiveInputs): AdaptiveAction {
        period++
        val severe = input.lossFraction >= LOSS_SEVERE || input.droppedFramesPerSecond >= 5f
        val stressed = severe || input.lossFraction >= LOSS_MILD ||
            (input.rttMs > RTT_STRESS_MS) || input.encodeLatencyMs > ENCODE_LATENCY_STRESS_MS ||
            input.receiverDecodeLagFrames >= DECODE_LAG_STRESS_FRAMES || input.sendQueueDrops > 0

        if (severe && period - lastKeyframeRequestPeriod >= 2 && input.lossFraction >= LOSS_SEVERE) {
            lastKeyframeRequestPeriod = period
            // Recovery first; bitrate reduction follows on the next period.
            stableCount = 0
            return AdaptiveAction.RequestKeyframe("loss=${"%.2f".format(input.lossFraction)}")
        }
        if (stressed) {
            stableCount = 0
            stressCount++
            // Hysteresis: one bad sample is not a trend. Reacting to every blip walked the
            // stream down to an unusable bitrate and resolution and never recovered.
            if (stressCount < stressPeriodsBeforeDegrade && !severe) return AdaptiveAction.None
            return degrade(severe)
        }
        stressCount = 0
        stableCount++
        if (stableCount >= stableIntervalsBeforeUpgrade) {
            stableCount = 0
            return upgrade()
        }
        return AdaptiveAction.None
    }

    private fun degrade(severe: Boolean): AdaptiveAction {
        val factor = if (severe) 0.6f else 0.8f
        if (level.bitrate > minBitrate) {
            val next = (level.bitrate * factor).toInt().coerceAtLeast(minBitrate)
            level = level.copy(bitrate = next)
            return AdaptiveAction.SetBitrate(next)
        }
        if (level.fps > 30) {
            level = level.copy(fps = 30)
            return AdaptiveAction.SetFps(30)
        }
        if (level.resolutionStep < MAX_RESOLUTION_STEP) {
            level = level.copy(resolutionStep = level.resolutionStep + 1)
            return AdaptiveAction.SetResolutionStep(level.resolutionStep)
        }
        return AdaptiveAction.None
    }

    private fun upgrade(): AdaptiveAction {
        if (level.bitrate < maxBitrate) {
            val next = (level.bitrate * 1.15f).toInt().coerceAtMost(maxBitrate)
            level = level.copy(bitrate = next)
            return AdaptiveAction.SetBitrate(next)
        }
        if (level.resolutionStep > 0) {
            level = level.copy(resolutionStep = level.resolutionStep - 1)
            return AdaptiveAction.SetResolutionStep(level.resolutionStep)
        }
        if (level.fps < maxFps) {
            level = level.copy(fps = maxFps)
            return AdaptiveAction.SetFps(maxFps)
        }
        return AdaptiveAction.None
    }
}
