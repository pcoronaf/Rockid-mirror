package com.rokidmirror.receiver.telemetry

import com.rokidmirror.protocol.control.Payloads

/** Per-frame receiver timestamps (T3..T7 of the specification), monotonic local ns. */
class FrameTiming(
    val frameId: Long,
    val firstPacketNs: Long,   // T3
    var completeNs: Long = 0,  // T4
    var submitNs: Long = 0,    // T5
    var decodedNs: Long = 0,   // T6
    var presentedNs: Long = 0, // T7
    var bytes: Int = 0,
)

/**
 * Sliding one-second window over completed frames plus lifetime counters. Populates the STATS
 * payload sent to the phone every second. Thread-safe (called from receive, decoder and render
 * callbacks).
 */
class PipelineStats(private val windowNs: Long = 1_000_000_000L) {
    private val inFlight = HashMap<Long, FrameTiming>()
    private val window = ArrayDeque<FrameTiming>()

    var packetsReceived = 0L; private set
    var packetsLost = 0L; private set
    var framesDelivered = 0L; private set
    var framesDropped = 0L; private set
    var framesDecoded = 0L; private set
    var framesSubmitted = 0L; private set
    private var lastKeyframeSuggestions = 0L

    @Synchronized fun onPacketCounters(received: Long, lost: Long, delivered: Long, dropped: Long) {
        packetsReceived = received; packetsLost = lost; framesDelivered = delivered; framesDropped = dropped
    }

    @Synchronized fun onComplete(frameId: Long, firstPacketNs: Long, completeNs: Long, bytes: Int): FrameTiming =
        FrameTiming(frameId, firstPacketNs, completeNs, bytes = bytes).also { inFlight[frameId] = it }

    @Synchronized fun onSubmitted(frameId: Long, nowNs: Long) { inFlight[frameId]?.submitNs = nowNs; framesSubmitted++ }
    @Synchronized fun onDropped(frameId: Long) { inFlight.remove(frameId); framesDropped++ }

    @Synchronized fun onDecoded(frameId: Long, nowNs: Long) {
        framesDecoded++
        val t = inFlight[frameId] ?: return
        t.decodedNs = nowNs
        if (t.presentedNs == 0L) t.presentedNs = nowNs // updated by onPresented when the platform reports it
        window.addLast(t)
        inFlight.remove(frameId)
        trim(nowNs)
        if (inFlight.size > 64) inFlight.keys.sorted().take(inFlight.size - 64).forEach { inFlight.remove(it) }
    }

    @Synchronized fun onPresented(frameId: Long, nowNs: Long) { window.lastOrNull { it.frameId == frameId }?.presentedNs = nowNs }

    @Synchronized fun decodeLagFrames(): Int = (framesSubmitted - framesDecoded).toInt().coerceAtLeast(0)

    @Synchronized fun snapshot(nowNs: Long, clockOffsetNs: Long = 0): Payloads.Stats {
        trim(nowNs)
        val n = window.size
        val sec = windowNs / 1e9f
        fun avg(f: (FrameTiming) -> Long): Float = if (n == 0) -1f else window.sumOf(f) / n / 1e6f
        return Payloads.Stats(
            packetsReceived = packetsReceived, packetsLost = packetsLost,
            framesDelivered = framesDelivered, framesDropped = framesDropped, framesDecoded = framesDecoded,
            decodeLagFrames = decodeLagFrames(),
            reassemblyMs = avg { it.completeNs - it.firstPacketNs },
            decodeMs = avg { it.decodedNs - it.submitNs },
            renderMs = avg { (it.presentedNs - it.decodedNs).coerceAtLeast(0) },
            fps = n / sec,
            bitrateBps = (window.sumOf { it.bytes.toLong() } * 8 / sec).toLong(),
            clockOffsetNs = clockOffsetNs,
        )
    }

    /** Returns true when the reassembler suggested a new keyframe since the last call. */
    @Synchronized fun consumeKeyframeSuggestion(total: Long): Boolean {
        val changed = total > lastKeyframeSuggestions
        lastKeyframeSuggestions = total
        return changed
    }

    private fun trim(nowNs: Long) { while (window.isNotEmpty() && nowNs - window.first().decodedNs > windowNs) window.removeFirst() }

    @Synchronized fun reset() { inFlight.clear(); window.clear(); framesSubmitted = 0; framesDecoded = 0 }
}
