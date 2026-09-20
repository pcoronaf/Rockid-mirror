package com.rokidmirror.protocol

import com.rokidmirror.protocol.control.ErrorCode
import com.rokidmirror.protocol.control.MirrorException
import com.rokidmirror.protocol.crypto.ControlChannelCipher
import com.rokidmirror.protocol.crypto.Hkdf
import com.rokidmirror.protocol.crypto.KeyExchange
import com.rokidmirror.protocol.crypto.PairingCommitment
import com.rokidmirror.protocol.crypto.SessionKeys
import com.rokidmirror.protocol.crypto.VideoCipher
import com.rokidmirror.protocol.video.VideoPacketHeader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CryptoTest {
    @Test
    fun hkdfRfc5869TestCase1() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = ByteArray(13) { it.toByte() }
        val info = ByteArray(10) { (0xf0 + it).toByte() }
        val okm = Hkdf.expand(Hkdf.extract(salt, ikm), info, 42)
        val expected = "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"
        assertEquals(expected, okm.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun ecdhBothSidesDeriveSameKeys() {
        val a = KeyExchange.generate(); val b = KeyExchange.generate()
        val t = SessionKeys.transcript(byteArrayOf(1), byteArrayOf(2))
        val ka = SessionKeys.derive(KeyExchange.sharedSecret(a, KeyExchange.encodePublic(b)), t)
        val kb = SessionKeys.derive(KeyExchange.sharedSecret(b, KeyExchange.encodePublic(a)), t)
        assertArrayEquals(ka.controlToReceiver, kb.controlToReceiver)
        assertArrayEquals(ka.video, kb.video)
        assertFalse(ka.controlToReceiver.contentEquals(ka.controlToSender))
    }

    @Test
    fun controlCipherEnforcesOrder() {
        val k1 = ByteArray(32) { 1 }; val k2 = ByteArray(32) { 2 }
        val sender = ControlChannelCipher(k1, k2, 1, 2)
        val receiver = ControlChannelCipher(k2, k1, 2, 1)
        val f1 = sender.seal("one".encodeToByteArray())
        val f2 = sender.seal("two".encodeToByteArray())
        assertEquals("one", receiver.open(f1).decodeToString())
        try { receiver.open(f1); fail() } catch (e: MirrorException) { assertEquals(ErrorCode.AUTHENTICATION_FAILED, e.code) }
        assertEquals("two", receiver.open(f2).decodeToString())
        val back = receiver.seal("ack".encodeToByteArray())
        assertEquals("ack", sender.open(back).decodeToString())
    }

    @Test
    fun videoCipherBindsHeader() {
        val c = VideoCipher(ByteArray(32) { 7 })
        val h = VideoPacketHeader(1, 5, 6, 7, 0, 1, 0, 1, 2)
        val plain = ByteArray(100) { it.toByte() }
        val sealed = c.seal(h, plain)
        assertEquals(plain.size + VideoCipher.OVERHEAD_BYTES, sealed.size)
        val wire = h.copy(payloadLength = sealed.size)
        assertArrayEquals(plain, c.open(wire, sealed))
        try { c.open(wire.copy(frameId = 99), sealed); fail() } catch (e: MirrorException) { assertEquals(ErrorCode.AUTHENTICATION_FAILED, e.code) }
    }

    @Test
    fun pairingCommitmentVerifiesOnlyMatchingBit() {
        val t = ByteArray(32) { 3 }
        val n = PairingCommitment.newNonce()
        val c = PairingCommitment.commit(n, PairingCommitment.ROLE_SENDER, t, 4, 1)
        assertTrue(PairingCommitment.verify(c, n, PairingCommitment.ROLE_SENDER, t, 4, 1))
        assertFalse(PairingCommitment.verify(c, n, PairingCommitment.ROLE_SENDER, t, 4, 0))
        assertFalse(PairingCommitment.verify(c, n, PairingCommitment.ROLE_RECEIVER, t, 4, 1))
        assertFalse(PairingCommitment.verify(c, n, PairingCommitment.ROLE_SENDER, t, 5, 1))
    }

    @Test
    fun codeHelpers() {
        val code = PairingCommitment.generateCode()
        assertEquals(6, code.length)
        assertEquals("482 731", PairingCommitment.formatForDisplay("482731"))
        assertEquals("482731", PairingCommitment.normalizeInput("482 731"))
        assertEquals(null, PairingCommitment.normalizeInput("4827"))
        assertEquals(1, PairingCommitment.bit("000001", 0))
        assertEquals(0, PairingCommitment.bit("000001", 1))
        assertEquals(1, PairingCommitment.bit("999999", 19)) // 999999 >= 2^19
    }
}
