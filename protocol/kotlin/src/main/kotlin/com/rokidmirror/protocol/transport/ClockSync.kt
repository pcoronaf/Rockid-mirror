package com.rokidmirror.protocol.transport

/**
 * Estimates RTT and the offset between the peer's monotonic clock and ours from PING/PONG
 * pairs, using the minimum-RTT sample of a sliding window (NTP-style). Offset lets the
 * receiver turn a sender capture timestamp into local time for the T2->T3 network estimate.
 */
class ClockSync(private val windowSize: Int = 16) {
    private class Sample(val rttNs: Long, val offsetNs: Long)
    private val samples = ArrayDeque<Sample>()

    var lastRttNs: Long = -1; private set
    val minRttNs: Long get() = samples.minOfOrNull { it.rttNs } ?: -1
    /** peerClock - localClock in nanoseconds, from the lowest-RTT sample. */
    val offsetNs: Long get() = samples.minByOrNull { it.rttNs }?.offsetNs ?: 0
    val hasEstimate: Boolean get() = samples.isNotEmpty()

    fun onPong(sentLocalNs: Long, peerNs: Long, receivedLocalNs: Long) {
        val rtt = receivedLocalNs - sentLocalNs
        if (rtt < 0) return
        val offset = peerNs - (sentLocalNs + rtt / 2)
        samples.addLast(Sample(rtt, offset))
        while (samples.size > windowSize) samples.removeFirst()
        lastRttNs = rtt
    }

    fun toLocalNs(peerNs: Long): Long = peerNs - offsetNs

    fun reset() { samples.clear(); lastRttNs = -1 }
}
