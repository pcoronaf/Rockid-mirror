package com.rokidmirror.protocol.video

/** Counters maintained by [Reassembler]; snapshot via [Reassembler.stats]. */
data class ReassemblyStats(
    val packetsReceived: Long = 0,
    val packetsLost: Long = 0,
    val packetsDuplicate: Long = 0,
    val framesDelivered: Long = 0,
    val framesDropped: Long = 0,
    /** Delta frames discarded while waiting for a keyframe after loss. */
    val framesSkippedAwaitingKeyframe: Long = 0,
    val keyframeRequestsSuggested: Long = 0,
)

/** Output of one [Reassembler.accept] call. */
sealed class ReassemblyResult {
    /** A complete access unit in decode order. */
    class Complete(val unit: EncodedAccessUnit, val firstPacketArrivalNs: Long, val completeArrivalNs: Long) : ReassemblyResult()
    /** Nothing to deliver yet. */
    object Pending : ReassemblyResult()
    /** Packet ignored (duplicate, stale, wrong session). */
    object Ignored : ReassemblyResult()
}

/**
 * Reassembles datagram fragments into access units and applies the latency-first policy from
 * the specification: no playout buffer, late or incomplete delta frames are dropped, and after
 * any loss delta frames are discarded until the next keyframe.
 *
 * Not thread-safe; drive it from the receive thread.
 */
class Reassembler(
    private val sessionShortId: Long,
    private val timeoutNs: Long,
    private val maxPendingFrames: Int = 8,
) {
    private class Pending(val header: VideoPacketHeader, val firstArrivalNs: Long) {
        val fragments = arrayOfNulls<ByteArray>(header.fragmentCount)
        var received = 0
        var lastArrivalNs = firstArrivalNs
        fun isComplete() = received == header.fragmentCount
        fun assemble(): ByteArray {
            val total = fragments.sumOf { it!!.size }
            val out = ByteArray(total)
            var pos = 0
            for (f in fragments) { System.arraycopy(f!!, 0, out, pos, f.size); pos += f.size }
            return out
        }
    }

    private val pending = java.util.TreeMap<Long, Pending>()
    private var nextExpectedSequence: Long = -1
    private var lastDeliveredFrameId: Long = -1
    private var awaitingKeyframe = true
    private var counters = ReassemblyStats()

    val stats: ReassemblyStats get() = counters
    val isAwaitingKeyframe: Boolean get() = awaitingKeyframe

    /** Call after a decoder reset so the next delivered frame is a keyframe. */
    fun requireKeyframe() { awaitingKeyframe = true }

    /**
     * @param payload decrypted fragment payload
     * @param nowNs monotonic arrival time (T3)
     */
    fun accept(header: VideoPacketHeader, payload: ByteArray, nowNs: Long): ReassemblyResult {
        if (header.sessionShortId != sessionShortId) return ReassemblyResult.Ignored
        counters = counters.copy(packetsReceived = counters.packetsReceived + 1)
        trackSequence(header.packetSequence)

        if (header.frameId <= lastDeliveredFrameId && lastDeliveredFrameId - header.frameId < 0x7FFF_FFFF) {
            return ReassemblyResult.Ignored // stale
        }
        val entry = pending.getOrPut(header.frameId) { Pending(header, nowNs) }
        if (entry.header.fragmentCount != header.fragmentCount) {
            pending.remove(header.frameId)
            counters = counters.copy(framesDropped = counters.framesDropped + 1)
            return ReassemblyResult.Ignored
        }
        if (entry.fragments[header.fragmentIndex] != null) {
            counters = counters.copy(packetsDuplicate = counters.packetsDuplicate + 1)
            return ReassemblyResult.Ignored
        }
        entry.fragments[header.fragmentIndex] = payload
        entry.received++
        entry.lastArrivalNs = nowNs

        expireStale(nowNs)
        return drain(nowNs)
    }

    /** Periodic housekeeping when no packets arrive; may still deliver nothing. */
    fun tick(nowNs: Long): ReassemblyResult {
        expireStale(nowNs)
        return drain(nowNs)
    }

    private fun trackSequence(seq: Long) {
        if (nextExpectedSequence < 0) { nextExpectedSequence = seq + 1; return }
        val gap = ((seq - nextExpectedSequence) and 0xFFFF_FFFFL)
        when {
            gap == 0L -> nextExpectedSequence = (seq + 1) and 0xFFFF_FFFFL
            gap < 0x7FFF_FFFFL -> { // forward jump: packets in between are lost (or reordered; we count on expiry)
                counters = counters.copy(packetsLost = counters.packetsLost + gap)
                nextExpectedSequence = (seq + 1) and 0xFFFF_FFFFL
            }
            else -> { /* late/reordered packet; already counted as lost */ }
        }
    }

    private fun expireStale(nowNs: Long) {
        val it = pending.entries.iterator()
        while (it.hasNext()) {
            val (_, p) = it.next()
            val expired = nowNs - p.firstArrivalNs > timeoutNs && !p.isComplete()
            if (expired || pending.size > maxPendingFrames) {
                it.remove()
                onFrameLost()
                if (pending.size <= maxPendingFrames && !expired) break
            } else break // TreeMap is ordered by frameId; older frames come first
        }
    }

    private fun onFrameLost() {
        counters = counters.copy(framesDropped = counters.framesDropped + 1)
        if (!awaitingKeyframe) {
            awaitingKeyframe = true
            counters = counters.copy(keyframeRequestsSuggested = counters.keyframeRequestsSuggested + 1)
        }
    }

    /** Deliver the oldest frame if it is complete; skip frames that precede a complete newer keyframe. */
    private fun drain(nowNs: Long): ReassemblyResult {
        if (pending.isEmpty()) return ReassemblyResult.Pending
        // If a newer keyframe is complete and older frames are still incomplete, jump ahead.
        val newestCompleteKey = pending.entries.lastOrNull { it.value.isComplete() && it.value.header.isKeyframe && !it.value.header.isConfig }
        if (newestCompleteKey != null && pending.firstKey() != newestCompleteKey.key) {
            val older = pending.headMap(newestCompleteKey.key, false)
            // Keep config units that immediately precede the keyframe; drop everything else.
            val toDrop = older.filterValues { !(it.isComplete() && it.header.isConfig) }
            if (toDrop.isNotEmpty()) {
                toDrop.keys.forEach { pending.remove(it) }
                counters = counters.copy(framesDropped = counters.framesDropped + toDrop.size)
                if (!awaitingKeyframe) awaitingKeyframe = true
            }
        }
        val first = pending.firstEntry() ?: return ReassemblyResult.Pending
        val p = first.value
        if (!p.isComplete()) return ReassemblyResult.Pending
        pending.remove(first.key)
        lastDeliveredFrameId = first.key
        val h = p.header
        if (awaitingKeyframe && !h.isKeyframe && !h.isConfig) {
            counters = counters.copy(framesSkippedAwaitingKeyframe = counters.framesSkippedAwaitingKeyframe + 1)
            return drain(nowNs)
        }
        if (h.isKeyframe) awaitingKeyframe = false
        counters = counters.copy(framesDelivered = counters.framesDelivered + 1)
        val unit = EncodedAccessUnit(
            frameId = h.frameId,
            isKeyframe = h.isKeyframe,
            isConfig = h.isConfig,
            captureTimestampNs = h.captureTimestampNs,
            encodeTimestampNs = h.encodeTimestampNs,
            data = p.assemble(),
        )
        return ReassemblyResult.Complete(unit, p.firstArrivalNs, p.lastArrivalNs)
    }
}
