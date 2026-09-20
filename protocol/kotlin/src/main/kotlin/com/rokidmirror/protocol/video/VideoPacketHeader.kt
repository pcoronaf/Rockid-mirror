package com.rokidmirror.protocol.video

import com.rokidmirror.protocol.Protocol
import com.rokidmirror.protocol.control.ErrorCode
import com.rokidmirror.protocol.control.MirrorException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Fixed 40-byte big-endian header in front of every video datagram fragment.
 *
 * ```
 * magic             16 bits   0x524D ("RM")
 * protocolVersion    8 bits
 * flags              8 bits   see [Flags]
 * sessionShortId    32 bits
 * frameId           32 bits
 * packetSequence    32 bits
 * fragmentIndex     16 bits
 * fragmentCount     16 bits
 * payloadLength     16 bits   length of the (encrypted) payload that follows
 * reserved          16 bits   must be zero
 * captureTimestamp  64 bits   ns, monotonic sender clock
 * encodeTimestamp   64 bits   ns, monotonic sender clock
 * ```
 * The header is transmitted in the clear and authenticated as AEAD associated data.
 */
data class VideoPacketHeader(
    val flags: Int,
    val sessionShortId: Long,
    val frameId: Long,
    val packetSequence: Long,
    val fragmentIndex: Int,
    val fragmentCount: Int,
    val payloadLength: Int,
    val captureTimestampNs: Long,
    val encodeTimestampNs: Long,
    val protocolVersion: Int = Protocol.PROTOCOL_VERSION,
) {
    object Flags {
        const val KEYFRAME = 0x01
        const val CONFIG = 0x02
        const val END_OF_ACCESS_UNIT = 0x04
        const val RETRANSMISSION = 0x08
    }

    val isKeyframe: Boolean get() = flags and Flags.KEYFRAME != 0
    val isConfig: Boolean get() = flags and Flags.CONFIG != 0
    val isEndOfAccessUnit: Boolean get() = flags and Flags.END_OF_ACCESS_UNIT != 0

    fun writeTo(buffer: ByteBuffer) {
        buffer.order(ByteOrder.BIG_ENDIAN)
        buffer.putShort(MAGIC.toShort())
        buffer.put(protocolVersion.toByte())
        buffer.put(flags.toByte())
        buffer.putInt(sessionShortId.toInt())
        buffer.putInt(frameId.toInt())
        buffer.putInt(packetSequence.toInt())
        buffer.putShort(fragmentIndex.toShort())
        buffer.putShort(fragmentCount.toShort())
        buffer.putShort(payloadLength.toShort())
        buffer.putShort(0)
        buffer.putLong(captureTimestampNs)
        buffer.putLong(encodeTimestampNs)
    }

    fun toByteArray(): ByteArray = ByteArray(SIZE).also { writeTo(ByteBuffer.wrap(it)) }

    companion object {
        const val SIZE = 40
        const val MAGIC = 0x524D

        /** @throws MirrorException on a corrupt or incompatible header. */
        fun parse(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): VideoPacketHeader {
            if (length < SIZE) throw MirrorException(ErrorCode.UNKNOWN, "short video packet ($length bytes)")
            val b = ByteBuffer.wrap(bytes, offset, length).order(ByteOrder.BIG_ENDIAN)
            val magic = b.short.toInt() and 0xFFFF
            if (magic != MAGIC) throw MirrorException(ErrorCode.UNKNOWN, "bad video packet magic 0x${magic.toString(16)}")
            val version = b.get().toInt() and 0xFF
            if (version != Protocol.PROTOCOL_VERSION) throw MirrorException(ErrorCode.PROTOCOL_VERSION_MISMATCH, "video packet v$version")
            val flags = b.get().toInt() and 0xFF
            val session = b.int.toLong() and 0xFFFF_FFFFL
            val frame = b.int.toLong() and 0xFFFF_FFFFL
            val seq = b.int.toLong() and 0xFFFF_FFFFL
            val fragIndex = b.short.toInt() and 0xFFFF
            val fragCount = b.short.toInt() and 0xFFFF
            val payloadLength = b.short.toInt() and 0xFFFF
            b.short // reserved
            val captureTs = b.long
            val encodeTs = b.long
            if (fragCount == 0 || fragIndex >= fragCount) throw MirrorException(ErrorCode.UNKNOWN, "bad fragment index $fragIndex/$fragCount")
            if (payloadLength != length - SIZE) throw MirrorException(ErrorCode.UNKNOWN, "payload length mismatch $payloadLength vs ${length - SIZE}")
            return VideoPacketHeader(flags, session, frame, seq, fragIndex, fragCount, payloadLength, captureTs, encodeTs, version)
        }
    }
}
