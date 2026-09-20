package com.rokidmirror.protocol.video

/**
 * One encoded video access unit (for H.264: one or more Annex-B NAL units forming a frame, or
 * codec configuration data). Timestamps come from a monotonic clock (`System.nanoTime()` on
 * both Android and the JVM tools), never from wall-clock time.
 */
class EncodedAccessUnit(
    val frameId: Long,
    val isKeyframe: Boolean,
    val isConfig: Boolean,
    /** T0: when the frame was submitted to the encoder (surface timestamp). */
    val captureTimestampNs: Long,
    /** T1: when the encoded access unit became available. */
    val encodeTimestampNs: Long,
    val data: ByteArray,
) {
    val size: Int get() = data.size
    override fun toString(): String =
        "AU(frame=$frameId key=$isKeyframe cfg=$isConfig bytes=${data.size})"
}
