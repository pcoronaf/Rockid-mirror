package com.rokidmirror.protocol.video

import java.nio.ByteBuffer

/** A ready-to-send datagram: cleartext header followed by the (optionally sealed) payload. */
class VideoPacket(val header: VideoPacketHeader, val bytes: ByteArray)

/**
 * Splits access units into MTU-sized datagrams. `seal` lets the transport encrypt each
 * fragment payload with the header as associated data; it must not change the header.
 */
class Fragmenter(
    private val sessionShortId: Long,
    private val maxDatagramBytes: Int,
    /** Bytes added by the transport's AEAD tag; subtracted from the fragment payload budget. */
    private val sealOverheadBytes: Int = 0,
) {
    private var packetSequence: Long = 0
    val nextSequence: Long get() = packetSequence

    private val maxFragmentPayload: Int = maxDatagramBytes - VideoPacketHeader.SIZE - sealOverheadBytes

    init {
        require(maxFragmentPayload > 0) { "datagram budget too small" }
    }

    fun fragment(unit: EncodedAccessUnit, seal: (header: VideoPacketHeader, plain: ByteArray) -> ByteArray = { _, p -> p }): List<VideoPacket> {
        val total = unit.data.size
        val count = if (total == 0) 1 else (total + maxFragmentPayload - 1) / maxFragmentPayload
        require(count <= 0xFFFF) { "access unit too large: $total bytes" }
        var flags = 0
        if (unit.isKeyframe) flags = flags or VideoPacketHeader.Flags.KEYFRAME
        if (unit.isConfig) flags = flags or VideoPacketHeader.Flags.CONFIG
        val packets = ArrayList<VideoPacket>(count)
        for (i in 0 until count) {
            val start = i * maxFragmentPayload
            val end = minOf(total, start + maxFragmentPayload)
            val plain = unit.data.copyOfRange(start, end)
            val packetFlags = if (i == count - 1) flags or VideoPacketHeader.Flags.END_OF_ACCESS_UNIT else flags
            val provisional = VideoPacketHeader(
                flags = packetFlags,
                sessionShortId = sessionShortId,
                frameId = unit.frameId and 0xFFFF_FFFFL,
                packetSequence = packetSequence and 0xFFFF_FFFFL,
                fragmentIndex = i,
                fragmentCount = count,
                payloadLength = 0,
                captureTimestampNs = unit.captureTimestampNs,
                encodeTimestampNs = unit.encodeTimestampNs,
            )
            val sealed = seal(provisional, plain)
            val header = provisional.copy(payloadLength = sealed.size)
            val out = ByteBuffer.allocate(VideoPacketHeader.SIZE + sealed.size)
            header.writeTo(out)
            out.put(sealed)
            packets += VideoPacket(header, out.array())
            packetSequence++
        }
        return packets
    }
}
