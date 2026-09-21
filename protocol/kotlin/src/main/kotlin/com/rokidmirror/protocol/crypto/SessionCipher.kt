package com.rokidmirror.protocol.crypto

import com.rokidmirror.protocol.control.ErrorCode
import com.rokidmirror.protocol.control.MirrorException
import com.rokidmirror.protocol.video.VideoPacketHeader
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** AES-256-GCM helper. Nonces are 96-bit and constructed by the callers below (never random). */
class AeadKey(key: ByteArray) {
    private val spec = SecretKeySpec(key, "AES")

    fun seal(nonce: ByteArray, plaintext: ByteArray, aad: ByteArray?): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, spec, GCMParameterSpec(TAG_BITS, nonce))
        if (aad != null) c.updateAAD(aad)
        return c.doFinal(plaintext)
    }

    fun open(nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray?): ByteArray = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, spec, GCMParameterSpec(TAG_BITS, nonce))
        if (aad != null) c.updateAAD(aad)
        c.doFinal(ciphertext)
    } catch (e: Exception) {
        throw MirrorException(ErrorCode.AUTHENTICATION_FAILED, "AEAD authentication failed", e)
    }

    companion object {
        const val TAG_BITS = 128
        const val TAG_BYTES = 16
        const val NONCE_BYTES = 12
    }
}

/**
 * Encrypts one direction of the ordered control channel. Nonce = direction byte, 3 zero bytes,
 * 64-bit counter. The receiver requires strictly increasing counters (TCP preserves order).
 *
 * Callers must hold the channel's write lock across [seal] AND the socket write. Sealing outside
 * the lock lets two senders take counters in one order and write them in another, which the peer
 * correctly rejects as tampering and drops the link.
 */
class ControlChannelCipher(sendKey: ByteArray, receiveKey: ByteArray, private val sendDirection: Byte, private val receiveDirection: Byte) {
    private val sender = AeadKey(sendKey)
    private val receiver = AeadKey(receiveKey)
    private var sendCounter = 0L
    private var expectedReceiveCounter = 0L

    @Synchronized
    fun seal(plaintext: ByteArray): ByteArray {
        val counter = sendCounter++
        val out = ByteBuffer.allocate(8 + plaintext.size + AeadKey.TAG_BYTES)
        out.putLong(counter)
        out.put(sender.seal(nonce(sendDirection, counter), plaintext, null))
        return out.array()
    }

    @Synchronized
    fun open(frame: ByteArray): ByteArray {
        if (frame.size < 8 + AeadKey.TAG_BYTES) throw MirrorException(ErrorCode.AUTHENTICATION_FAILED, "short encrypted frame")
        val buf = ByteBuffer.wrap(frame)
        val counter = buf.long
        if (counter != expectedReceiveCounter) throw MirrorException(ErrorCode.AUTHENTICATION_FAILED, "control counter out of order")
        val body = ByteArray(frame.size - 8).also { buf.get(it) }
        val plain = receiver.open(nonce(receiveDirection, counter), body, null)
        expectedReceiveCounter++
        return plain
    }

    private fun nonce(direction: Byte, counter: Long): ByteArray =
        ByteBuffer.allocate(AeadKey.NONCE_BYTES).put(direction).put(0).put(0).put(0).putLong(counter).array()

    companion object {
        const val DIRECTION_TO_RECEIVER: Byte = 0x01
        const val DIRECTION_TO_SENDER: Byte = 0x02
    }
}

/**
 * Encrypts video fragment payloads. The cleartext header is associated data, so a forged or
 * replayed header fails authentication. Nonce = 0x56 'V', 3 zero bytes, sessionShortId,
 * packetSequence; unique per session key as long as packetSequence does not wrap (2^32
 * packets, weeks of streaming; sessions rekey on every reconnect).
 */
class VideoCipher(key: ByteArray) {
    private val aead = AeadKey(key)

    fun seal(header: VideoPacketHeader, plaintext: ByteArray): ByteArray =
        aead.seal(nonce(header), plaintext, header.copy(payloadLength = plaintext.size + AeadKey.TAG_BYTES).toByteArray())

    fun open(header: VideoPacketHeader, ciphertext: ByteArray): ByteArray =
        aead.open(nonce(header), ciphertext, header.toByteArray())

    private fun nonce(h: VideoPacketHeader): ByteArray =
        ByteBuffer.allocate(AeadKey.NONCE_BYTES).put(0x56).put(0).put(0).put(0)
            .putInt(h.sessionShortId.toInt()).putInt(h.packetSequence.toInt()).array()

    companion object {
        const val OVERHEAD_BYTES = AeadKey.TAG_BYTES
    }
}
