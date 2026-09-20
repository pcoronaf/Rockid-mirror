package com.rokidmirror.sender.telemetry

/** Sender-side pipeline timing (T0 capture, T1 encoded, T2 dispatched), all monotonic ns. */
data class LatencySnapshot(
    val frames: Long,
    val encodeMsAvg: Float,
    val encodeMsMax: Float,
    val dispatchMsAvg: Float,
    val fps: Float,
    val bitrateBps: Long,
    val keyframes: Long,
    val bytes: Long,
)

/** Sliding one-second window of per-frame timings; thread-safe for one producer, many readers. */
class LatencyTracker(private val windowNs: Long = 1_000_000_000L) {
    private class Sample(val t2: Long, val encodeNs: Long, val dispatchNs: Long, val bytes: Int, val key: Boolean)
    private val samples = ArrayDeque<Sample>()
    private var totalFrames = 0L
    private var totalKeyframes = 0L
    private var totalBytes = 0L

    @Synchronized
    fun onDispatched(captureNs: Long, encodeNs: Long, dispatchNs: Long, bytes: Int, keyframe: Boolean) {
        samples.addLast(Sample(dispatchNs, encodeNs - captureNs, dispatchNs - encodeNs, bytes, keyframe))
        totalFrames++
        totalBytes += bytes
        if (keyframe) totalKeyframes++
        while (samples.isNotEmpty() && dispatchNs - samples.first().t2 > windowNs) samples.removeFirst()
    }

    @Synchronized
    fun snapshot(nowNs: Long): LatencySnapshot {
        while (samples.isNotEmpty() && nowNs - samples.first().t2 > windowNs) samples.removeFirst()
        val n = samples.size
        if (n == 0) return LatencySnapshot(totalFrames, 0f, 0f, 0f, 0f, 0, totalKeyframes, totalBytes)
        val windowSec = windowNs / 1e9f
        val bytesInWindow = samples.sumOf { it.bytes.toLong() }
        return LatencySnapshot(
            frames = totalFrames,
            encodeMsAvg = samples.sumOf { it.encodeNs } / n / 1e6f,
            encodeMsMax = samples.maxOf { it.encodeNs } / 1e6f,
            dispatchMsAvg = samples.sumOf { it.dispatchNs } / n / 1e6f,
            fps = n / windowSec,
            bitrateBps = (bytesInWindow * 8 / windowSec).toLong(),
            keyframes = totalKeyframes,
            bytes = totalBytes,
        )
    }

    @Synchronized fun reset() { samples.clear(); totalFrames = 0; totalKeyframes = 0; totalBytes = 0 }
}
