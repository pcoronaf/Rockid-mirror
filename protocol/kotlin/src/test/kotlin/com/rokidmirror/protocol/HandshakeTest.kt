package com.rokidmirror.protocol

import com.rokidmirror.protocol.control.ControlCodec
import com.rokidmirror.protocol.control.ControlMessage
import com.rokidmirror.protocol.control.ErrorCode
import com.rokidmirror.protocol.control.MessageFactory
import com.rokidmirror.protocol.control.MessageType
import com.rokidmirror.protocol.control.MirrorException
import com.rokidmirror.protocol.control.Payloads
import com.rokidmirror.protocol.crypto.HandshakeOutput
import com.rokidmirror.protocol.crypto.PairingCredential
import com.rokidmirror.protocol.crypto.PreEncoded
import com.rokidmirror.protocol.crypto.ReceiverHandshake
import com.rokidmirror.protocol.crypto.ReceiverIdentity
import com.rokidmirror.protocol.crypto.SenderHandshake
import com.rokidmirror.protocol.crypto.SenderIdentity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Drives both handshake state machines against each other through an in-memory "wire" that
 * models exactly what the transports do (plaintext until told to encrypt).
 */
class HandshakeTest {
    private val senderId = SenderIdentity("phone-1", "Galaxy S25", "0.1")
    private val receiverId = ReceiverIdentity("glasses-1", "Rokid Glasses", "0.1", 47011)
    private var clock = 0L

    private class Wire {
        var senderEncrypted = false
        var receiverEncrypted = false
        val log = mutableListOf<Pair<String, ControlMessage>>()
    }

    private fun run(
        sender: SenderHandshake,
        receiver: ReceiverHandshake,
        codeForPhone: (String) -> String = { it },
    ): Pair<HandshakeOutput?, Wire> {
        val wire = Wire()
        var shownCode: String? = null
        var lastSenderOut: HandshakeOutput? = null
        val toReceiver = ArrayDeque<Pair<ControlMessage, Boolean>>()
        val toSender = ArrayDeque<Pair<ControlMessage, Boolean>>()

        fun senderEmit(o: HandshakeOutput) {
            lastSenderOut = o
            o.sendPlain.forEach { toReceiver += it to wire.senderEncrypted }
            if (o.encryptAfterPlain) wire.senderEncrypted = true
            o.sendEncrypted.forEach { toReceiver += it to true }
            if (o.needPairingCode) senderEmit(sender.providePairingCode(codeForPhone(shownCode!!)))
        }
        fun receiverEmit(o: HandshakeOutput) {
            o.showPairingCode?.let { shownCode = it }
            o.sendPlain.forEach { toSender += it to wire.receiverEncrypted }
            if (o.encryptAfterPlain) wire.receiverEncrypted = true
            o.sendEncrypted.forEach { toSender += it to true }
        }

        toReceiver += sender.start() to false
        while (toReceiver.isNotEmpty() || toSender.isNotEmpty()) {
            while (toReceiver.isNotEmpty()) {
                val (m, enc) = toReceiver.removeFirst()
                wire.log += "S->R" to m
                // receiver expects encryption exactly when it has switched itself
                assertEquals("encryption state mismatch on ${m.type}", wire.receiverEncrypted, enc)
                receiverEmit(receiver.onMessage(m, PreEncoded.bytesOf(m)))
            }
            while (toSender.isNotEmpty()) {
                val (m, enc) = toSender.removeFirst()
                wire.log += "R->S" to m
                assertEquals("encryption state mismatch on ${m.type}", wire.senderEncrypted, enc)
                senderEmit(sender.onMessage(m, PreEncoded.bytesOf(m)))
            }
        }
        return lastSenderOut to wire
    }

    private fun newSender(cred: PairingCredential? = null) = SenderHandshake(senderId, cred, MessageFactory("session-1") { clock })

    @Test
    fun firstTimePairingWithCodeIssuesCredentialAndMatchingKeys() {
        val receiver = ReceiverHandshake(receiverId, { _, _ -> null }, { clock })
        val sender = newSender()
        val (last, wire) = run(sender, receiver)
        assertTrue(last!!.completed)
        assertEquals(SenderHandshake.State.COMPLETE, sender.state)
        assertEquals(ReceiverHandshake.State.COMPLETE, receiver.state)
        assertArrayEquals(sender.keys.controlToReceiver, receiver.keys.controlToReceiver)
        assertArrayEquals(sender.keys.video, receiver.keys.video)
        assertNotNull(receiver.issuedCredential)
        assertEquals(receiver.issuedCredential!!.credentialId, sender.credential!!.credentialId)
        assertArrayEquals(receiver.issuedCredential!!.secret, sender.credential!!.secret)
        assertEquals("glasses-1", sender.credential!!.receiverId)
        assertTrue(wire.senderEncrypted && wire.receiverEncrypted)
        // 20 rounds x (commit, commit, reveal, reveal) + hello/ack + issue
        val pairMessages = wire.log.count { it.second.type == MessageType.PAIR_REQUEST || it.second.type == MessageType.PAIR_CONFIRM }
        assertEquals(20 * 4 + 1, pairMessages)
        // No secret material in plaintext control messages
        val issue = wire.log.last().second
        assertEquals(MessageType.PAIR_CONFIRM, issue.type)
        assertEquals(Payloads.PairStage.ISSUE, ControlCodec.payloadOf(issue, Payloads.PairConfirm.serializer()).stage)
    }

    @Test
    fun wrongCodeFailsEarlyAndBurnsCode() {
        val receiver = ReceiverHandshake(receiverId, { _, _ -> null }, { clock })
        val sender = newSender()
        try {
            run(sender, receiver) { shown -> "%06d".format((shown.toInt() + 1) % 1_000_000) }
            fail("expected failure")
        } catch (e: MirrorException) {
            assertEquals(ErrorCode.PAIRING_FAILED, e.code)
        }
        assertTrue(receiver.state == ReceiverHandshake.State.FAILED || sender.state == SenderHandshake.State.FAILED)
        assertNull(receiver.issuedCredential)
    }

    @Test
    fun reconnectWithCredentialSkipsCode() {
        val receiver1 = ReceiverHandshake(receiverId, { _, _ -> null }, { clock })
        val sender1 = newSender()
        run(sender1, receiver1)
        val cred = sender1.credential!!
        val store = mapOf(("phone-1" to cred.credentialId) to cred.secret)

        val receiver2 = ReceiverHandshake(receiverId, { s, c -> store[s to c] }, { clock })
        val sender2 = newSender(cred)
        val (last, wire) = run(sender2, receiver2)
        assertTrue(last!!.completed)
        assertTrue(receiver2.authenticatedByCredential)
        assertNull(receiver2.issuedCredential)
        assertFalse(wire.log.any { it.second.type == MessageType.PAIR_REQUEST && ControlCodec.payloadOf(it.second, Payloads.PairRequest.serializer()).mode == Payloads.PairMode.CODE })
        assertArrayEquals(sender2.keys.controlToSender, receiver2.keys.controlToSender)
        assertEquals(cred.credentialId, sender2.credential!!.credentialId)
    }

    @Test
    fun forgottenCredentialFallsBackToCode() {
        val cred = PairingCredential.issue("glasses-1")
        val receiver = ReceiverHandshake(receiverId, { _, _ -> null }, { clock }) // receiver forgot all senders
        val sender = newSender(cred)
        val (last, _) = run(sender, receiver)
        assertTrue(last!!.completed)
        assertNotNull(receiver.issuedCredential)
        assertEquals(receiver.issuedCredential!!.credentialId, sender.credential!!.credentialId)
    }

    @Test
    fun wrongCredentialSecretIsRejected() {
        val good = PairingCredential.issue("glasses-1")
        val bad = PairingCredential("glasses-1", good.credentialId, ByteArray(32) { 9 })
        val receiver = ReceiverHandshake(receiverId, { _, _ -> good.secret }, { clock })
        val sender = newSender(bad)
        try { run(sender, receiver); fail() } catch (e: MirrorException) { assertEquals(ErrorCode.AUTHENTICATION_FAILED, e.code) }
    }

    @Test
    fun manInTheMiddleCannotCompleteCodePairing() {
        // Attacker relays HELLO/HELLO_ACK with its own keys, so transcripts differ on each side.
        // Even knowing the code afterwards is useless: commitments bind the transcript.
        val receiver = ReceiverHandshake(receiverId, { _, _ -> null }, { clock })
        val sender = newSender()
        val attackerToReceiver = newSender() // attacker's own key pair towards the receiver
        val hello = sender.start()
        val ackOut = receiver.onMessage(attackerToReceiver.start(), attackerToReceiver.helloWireBytes())
        val realAck = ackOut.sendPlain.single()
        // Attacker forges an ack to the sender using its own key: sender derives keys with attacker
        val attackerAsReceiver = ReceiverHandshake(receiverId, { _, _ -> null }, { clock })
        val forgedAckOut = attackerAsReceiver.onMessage(hello, ControlCodec.encode(hello))
        val forged = forgedAckOut.sendPlain.single()
        val need = sender.onMessage(forged, PreEncoded.bytesOf(forged))
        assertTrue(need.needPairingCode)
        val code = ackOut.showPairingCode!!
        val commit = sender.providePairingCode(code).sendPlain.single()
        // Attacker forwards the sender's commitment (bound to the wrong transcript) to the receiver.
        val rc = receiver.onMessage(commit, ControlCodec.encode(commit)).sendPlain.single()
        val reveal = sender.onMessage(rc, ControlCodec.encode(rc)).sendPlain.single()
        try {
            receiver.onMessage(reveal, ControlCodec.encode(reveal))
            fail("receiver must reject a commitment made against a different transcript")
        } catch (e: MirrorException) {
            assertEquals(ErrorCode.PAIRING_FAILED, e.code)
        }
        assertEquals(ReceiverHandshake.State.FAILED, receiver.state)
        assertEquals(MessageType.HELLO_ACK, realAck.type)
    }

    @Test
    fun expiredCodeIsRejected() {
        val receiver = ReceiverHandshake(receiverId, { _, _ -> null }, { clock })
        val sender = newSender()
        val hello = sender.start()
        val ack = receiver.onMessage(hello, sender.helloWireBytes()).sendPlain.single()
        sender.onMessage(ack, PreEncoded.bytesOf(ack))
        clock += (Protocol.PAIRING_CODE_TTL_MS + 1) * 1_000_000
        val commit = sender.providePairingCode("123456").sendPlain.single()
        try { receiver.onMessage(commit, ControlCodec.encode(commit)); fail() } catch (e: MirrorException) { assertEquals(ErrorCode.PAIRING_FAILED, e.code) }
    }
}
