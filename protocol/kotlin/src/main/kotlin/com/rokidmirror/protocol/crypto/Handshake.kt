package com.rokidmirror.protocol.crypto

import com.rokidmirror.protocol.control.ControlCodec
import com.rokidmirror.protocol.control.ControlMessage
import com.rokidmirror.protocol.control.ErrorCode
import com.rokidmirror.protocol.control.MessageFactory
import com.rokidmirror.protocol.control.MessageType
import com.rokidmirror.protocol.control.MirrorException
import com.rokidmirror.protocol.control.Payloads
import java.security.KeyPair
import java.security.SecureRandom
import java.util.Base64

/**
 * What a transport must do after feeding a message into a handshake:
 * send [sendPlain] as-is, then, if [encryptAfterPlain], switch the channel to the derived
 * session keys, then send [sendEncrypted].
 */
data class HandshakeOutput(
    val sendPlain: List<ControlMessage> = emptyList(),
    val encryptAfterPlain: Boolean = false,
    val sendEncrypted: List<ControlMessage> = emptyList(),
    /** Sender side: the user must enter the code shown on the glasses. */
    val needPairingCode: Boolean = false,
    /** Receiver side: show this code to the user. */
    val showPairingCode: String? = null,
    val completed: Boolean = false,
) {
    companion object { val NONE = HandshakeOutput() }
}

data class SenderIdentity(val senderId: String, val senderName: String, val appVersion: String)
data class ReceiverIdentity(val receiverId: String, val receiverName: String, val appVersion: String, val videoPort: Int)

internal object B64 {
    fun enc(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
    fun dec(s: String?): ByteArray = try {
        Base64.getDecoder().decode(s ?: throw MirrorException(ErrorCode.PAIRING_FAILED, "missing field"))
    } catch (e: IllegalArgumentException) {
        throw MirrorException(ErrorCode.PAIRING_FAILED, "bad base64", e)
    }
}

/**
 * Sender (phone) side of the connection handshake. Transport-agnostic: feed it decoded
 * [ControlMessage]s and act on [HandshakeOutput]. See docs/protocol.md "Handshake".
 */
class SenderHandshake(
    private val identity: SenderIdentity,
    private val storedCredential: PairingCredential?,
    val factory: MessageFactory,
    private val random: SecureRandom = SecureRandom(),
) {
    enum class State { INIT, AWAIT_HELLO_ACK, AWAIT_CODE, AWAIT_RECEIVER_COMMIT, AWAIT_RECEIVER_REVEAL, AWAIT_CREDENTIAL_CONFIRM, AWAIT_ISSUE, COMPLETE, FAILED }

    var state: State = State.INIT; private set
    private val keyPair: KeyPair = KeyExchange.generate()
    private var helloBytes: ByteArray? = null

    lateinit var keys: SessionKeys; private set
    lateinit var helloAck: Payloads.HelloAck; private set
    /** Credential to persist after completion (new or the one that authenticated). */
    var credential: PairingCredential? = storedCredential; private set

    private var code: String? = null
    private var round = 0
    private var myNonce: ByteArray? = null
    private var receiverCommitment: ByteArray? = null

    fun start(): ControlMessage {
        check(state == State.INIT)
        val payload = Payloads.Hello(
            senderId = identity.senderId,
            senderName = identity.senderName,
            appVersion = identity.appVersion,
            ephemeralPublicKey = B64.enc(KeyExchange.encodePublic(keyPair)),
            credentialId = storedCredential?.credentialId,
            nonce = B64.enc(PairingCommitment.newNonce(random)),
        )
        val msg = factory.create(MessageType.HELLO, Payloads.Hello.serializer(), payload)
        helloBytes = ControlCodec.encode(msg)
        state = State.AWAIT_HELLO_ACK
        return msg
    }

    /** Exact bytes of HELLO as written to the wire (part of the transcript). */
    fun helloWireBytes(): ByteArray = checkNotNull(helloBytes)

    /**
     * @param rawBytes the exact bytes received for [message] (needed for HELLO_ACK transcript).
     */
    fun onMessage(message: ControlMessage, rawBytes: ByteArray): HandshakeOutput {
        try {
            if (message.type == MessageType.ERROR) {
                val e = ControlCodec.payloadOf(message, Payloads.Error.serializer())
                throw MirrorException(ErrorCode.fromWire(e.code), "receiver: ${e.message}")
            }
            return when (state) {
                State.AWAIT_HELLO_ACK -> onHelloAck(expect(message, MessageType.HELLO_ACK), rawBytes)
                State.AWAIT_RECEIVER_COMMIT -> onReceiverCommit(confirm(message))
                State.AWAIT_RECEIVER_REVEAL -> onReceiverReveal(confirm(message))
                State.AWAIT_CREDENTIAL_CONFIRM -> onCredentialConfirm(confirm(message))
                State.AWAIT_ISSUE -> onIssue(confirm(message))
                else -> throw MirrorException(ErrorCode.PAIRING_FAILED, "unexpected ${message.type} in $state")
            }
        } catch (e: MirrorException) {
            state = State.FAILED
            throw e
        }
    }

    fun providePairingCode(input: String): HandshakeOutput {
        check(state == State.AWAIT_CODE) { "not waiting for a code" }
        val normalized = PairingCommitment.normalizeInput(input)
            ?: throw MirrorException(ErrorCode.PAIRING_FAILED, "code must be ${PairingCommitment.CODE_DIGITS} digits")
        code = normalized
        round = 0
        return HandshakeOutput(sendPlain = listOf(commitMessage()))
    }

    private fun expect(m: ControlMessage, t: MessageType): ControlMessage =
        if (m.type == t) m else throw MirrorException(ErrorCode.PAIRING_FAILED, "expected $t, got ${m.type}")

    private fun confirm(m: ControlMessage): Payloads.PairConfirm {
        val p = ControlCodec.payloadOf(expect(m, MessageType.PAIR_CONFIRM), Payloads.PairConfirm.serializer())
        if (!p.accepted) throw MirrorException(ErrorCode.PAIRING_FAILED, p.reason ?: "rejected by receiver")
        return p
    }

    private fun onHelloAck(message: ControlMessage, rawBytes: ByteArray): HandshakeOutput {
        helloAck = ControlCodec.payloadOf(message, Payloads.HelloAck.serializer())
        val shared = KeyExchange.sharedSecret(keyPair, B64.dec(helloAck.ephemeralPublicKey))
        keys = SessionKeys.derive(shared, SessionKeys.transcript(helloWireBytes(), rawBytes))
        val cred = storedCredential
        return if (!helloAck.pairingRequired && cred != null && cred.receiverId == helloAck.receiverId) {
            state = State.AWAIT_CREDENTIAL_CONFIRM
            val proof = PairingCredential.proof(cred.secret, PairingCommitment.ROLE_SENDER, keys.transcriptHash)
            val req = Payloads.PairRequest(mode = Payloads.PairMode.CREDENTIAL, stage = Payloads.PairStage.CREDENTIAL, credentialId = cred.credentialId, proof = B64.enc(proof))
            HandshakeOutput(sendPlain = listOf(factory.create(MessageType.PAIR_REQUEST, Payloads.PairRequest.serializer(), req)))
        } else {
            state = State.AWAIT_CODE
            HandshakeOutput(needPairingCode = true)
        }
    }

    private fun commitMessage(): ControlMessage {
        val nonce = PairingCommitment.newNonce(random).also { myNonce = it }
        val c = PairingCommitment.commit(nonce, PairingCommitment.ROLE_SENDER, keys.transcriptHash, round, PairingCommitment.bit(code!!, round))
        state = State.AWAIT_RECEIVER_COMMIT
        return factory.create(
            MessageType.PAIR_REQUEST, Payloads.PairRequest.serializer(),
            Payloads.PairRequest(mode = Payloads.PairMode.CODE, stage = Payloads.PairStage.COMMIT, round = round, commitment = B64.enc(c)),
        )
    }

    private fun onReceiverCommit(p: Payloads.PairConfirm): HandshakeOutput {
        if (p.stage != Payloads.PairStage.COMMIT || p.round != round) throw MirrorException(ErrorCode.PAIRING_FAILED, "bad commit round")
        receiverCommitment = B64.dec(p.commitment)
        state = State.AWAIT_RECEIVER_REVEAL
        val reveal = Payloads.PairRequest(mode = Payloads.PairMode.CODE, stage = Payloads.PairStage.REVEAL, round = round, nonce = B64.enc(myNonce!!))
        return HandshakeOutput(sendPlain = listOf(factory.create(MessageType.PAIR_REQUEST, Payloads.PairRequest.serializer(), reveal)))
    }

    private fun onReceiverReveal(p: Payloads.PairConfirm): HandshakeOutput {
        if (p.stage != Payloads.PairStage.REVEAL || p.round != round) throw MirrorException(ErrorCode.PAIRING_FAILED, "bad reveal round")
        val ok = PairingCommitment.verify(receiverCommitment!!, B64.dec(p.nonce), PairingCommitment.ROLE_RECEIVER, keys.transcriptHash, round, PairingCommitment.bit(code!!, round))
        if (!ok) throw MirrorException(ErrorCode.PAIRING_FAILED, "receiver commitment mismatch at bit $round")
        round++
        return if (round < PairingCommitment.CODE_BITS) {
            HandshakeOutput(sendPlain = listOf(commitMessage()))
        } else {
            // Receiver is authenticated; it now switches to encryption and issues a credential.
            state = State.AWAIT_ISSUE
            HandshakeOutput(encryptAfterPlain = true)
        }
    }

    private fun onCredentialConfirm(p: Payloads.PairConfirm): HandshakeOutput {
        if (p.stage != Payloads.PairStage.CREDENTIAL) throw MirrorException(ErrorCode.AUTHENTICATION_FAILED, "bad credential stage")
        val expected = PairingCredential.proof(storedCredential!!.secret, PairingCommitment.ROLE_RECEIVER, keys.transcriptHash)
        if (!Hkdf.constantTimeEquals(expected, B64.dec(p.proof))) throw MirrorException(ErrorCode.AUTHENTICATION_FAILED, "receiver proof mismatch")
        state = State.COMPLETE
        return HandshakeOutput(encryptAfterPlain = true, completed = true)
    }

    private fun onIssue(p: Payloads.PairConfirm): HandshakeOutput {
        if (p.stage != Payloads.PairStage.ISSUE) throw MirrorException(ErrorCode.PAIRING_FAILED, "expected credential issue")
        credential = PairingCredential(helloAck.receiverId, p.credentialId ?: throw MirrorException(ErrorCode.PAIRING_FAILED, "no credential id"), B64.dec(p.credentialSecret))
        state = State.COMPLETE
        return HandshakeOutput(completed = true)
    }
}

/**
 * Receiver (glasses) side. [credentialLookup] returns the secret for a (senderId, credentialId)
 * pair or null when unknown; unknown senders must pair with a code and can never stream
 * silently.
 */
class ReceiverHandshake(
    private val identity: ReceiverIdentity,
    private val credentialLookup: (senderId: String, credentialId: String) -> ByteArray?,
    private val clockNs: () -> Long,
    private val random: SecureRandom = SecureRandom(),
) {
    enum class State { AWAIT_HELLO, AWAIT_PAIR_REQUEST, AWAIT_SENDER_COMMIT, AWAIT_SENDER_REVEAL, COMPLETE, FAILED }

    var state: State = State.AWAIT_HELLO; private set
    private val keyPair: KeyPair = KeyExchange.generate()

    lateinit var factory: MessageFactory; private set
    lateinit var keys: SessionKeys; private set
    lateinit var hello: Payloads.Hello; private set
    /** Set after a successful CODE pairing; the caller must persist it for [hello].senderId. */
    var issuedCredential: PairingCredential? = null; private set
    /** True when the sender authenticated with a previously issued credential. */
    var authenticatedByCredential: Boolean = false; private set

    private var storedSecret: ByteArray? = null
    private var pairingCode: String? = null
    private var codeIssuedAtNs: Long = 0
    private var round = 0
    private var myNonce: ByteArray? = null
    private var senderCommitment: ByteArray? = null

    fun onMessage(message: ControlMessage, rawBytes: ByteArray): HandshakeOutput {
        try {
            if (message.type == MessageType.ERROR || message.type == MessageType.GOODBYE) {
                throw MirrorException(ErrorCode.PAIRING_FAILED, "sender aborted (${message.type})")
            }
            return when (state) {
                State.AWAIT_HELLO -> onHello(message, rawBytes)
                State.AWAIT_PAIR_REQUEST -> onPairRequest(request(message))
                State.AWAIT_SENDER_COMMIT -> onSenderCommit(request(message))
                State.AWAIT_SENDER_REVEAL -> onSenderReveal(request(message))
                else -> throw MirrorException(ErrorCode.PAIRING_FAILED, "unexpected ${message.type} in $state")
            }
        } catch (e: MirrorException) {
            state = State.FAILED
            throw e
        }
    }

    /** Message to send to the peer before closing when the handshake failed. */
    fun errorMessage(e: MirrorException): ControlMessage? {
        if (!::factory.isInitialized) return null
        return factory.create(MessageType.ERROR, Payloads.Error.serializer(), Payloads.Error(e.code.name, e.code.userMessage, e.code.recoverable))
    }

    private fun request(m: ControlMessage): Payloads.PairRequest {
        if (m.type != MessageType.PAIR_REQUEST) throw MirrorException(ErrorCode.PAIRING_FAILED, "expected PAIR_REQUEST, got ${m.type}")
        return ControlCodec.payloadOf(m, Payloads.PairRequest.serializer())
    }

    private fun onHello(message: ControlMessage, rawBytes: ByteArray): HandshakeOutput {
        if (message.type != MessageType.HELLO) throw MirrorException(ErrorCode.PAIRING_FAILED, "expected HELLO")
        hello = ControlCodec.payloadOf(message, Payloads.Hello.serializer())
        factory = MessageFactory(message.sessionId, clockNs)
        storedSecret = hello.credentialId?.let { credentialLookup(hello.senderId, it) }
        val ack = Payloads.HelloAck(
            receiverId = identity.receiverId,
            receiverName = identity.receiverName,
            appVersion = identity.appVersion,
            ephemeralPublicKey = B64.enc(KeyExchange.encodePublic(keyPair)),
            videoPort = identity.videoPort,
            pairingRequired = storedSecret == null,
            nonce = B64.enc(PairingCommitment.newNonce(random)),
        )
        val ackMessage = factory.create(MessageType.HELLO_ACK, Payloads.HelloAck.serializer(), ack)
        val ackBytes = ControlCodec.encode(ackMessage)
        val shared = KeyExchange.sharedSecret(keyPair, B64.dec(hello.ephemeralPublicKey))
        keys = SessionKeys.derive(shared, SessionKeys.transcript(rawBytes, ackBytes))
        state = State.AWAIT_PAIR_REQUEST
        var code: String? = null
        if (storedSecret == null) {
            code = PairingCommitment.generateCode(random)
            pairingCode = code
            codeIssuedAtNs = clockNs()
        }
        return HandshakeOutput(sendPlain = listOf(PreEncoded.wrap(ackMessage, ackBytes)), showPairingCode = code)
    }

    private fun onPairRequest(p: Payloads.PairRequest): HandshakeOutput = when (p.mode) {
        Payloads.PairMode.CREDENTIAL -> {
            val secret = storedSecret ?: throw MirrorException(ErrorCode.AUTHENTICATION_FAILED, "unknown credential")
            if (p.stage != Payloads.PairStage.CREDENTIAL) throw MirrorException(ErrorCode.AUTHENTICATION_FAILED, "bad stage")
            val expected = PairingCredential.proof(secret, PairingCommitment.ROLE_SENDER, keys.transcriptHash)
            if (!Hkdf.constantTimeEquals(expected, B64.dec(p.proof))) throw MirrorException(ErrorCode.AUTHENTICATION_FAILED, "sender proof mismatch")
            authenticatedByCredential = true
            state = State.COMPLETE
            val proof = PairingCredential.proof(secret, PairingCommitment.ROLE_RECEIVER, keys.transcriptHash)
            val confirm = Payloads.PairConfirm(stage = Payloads.PairStage.CREDENTIAL, accepted = true, proof = B64.enc(proof))
            HandshakeOutput(
                sendPlain = listOf(factory.create(MessageType.PAIR_CONFIRM, Payloads.PairConfirm.serializer(), confirm)),
                encryptAfterPlain = true,
                completed = true,
            )
        }
        Payloads.PairMode.CODE -> {
            if (pairingCode == null) throw MirrorException(ErrorCode.PAIRING_FAILED, "no pairing code active")
            round = 0
            onSenderCommit(p)
        }
    }

    private fun onSenderCommit(p: Payloads.PairRequest): HandshakeOutput {
        if (p.stage != Payloads.PairStage.COMMIT || p.round != round) throw MirrorException(ErrorCode.PAIRING_FAILED, "bad commit round")
        if (clockNs() - codeIssuedAtNs > com.rokidmirror.protocol.Protocol.PAIRING_CODE_TTL_MS * 1_000_000) {
            throw MirrorException(ErrorCode.PAIRING_FAILED, "pairing code expired")
        }
        senderCommitment = B64.dec(p.commitment)
        val nonce = PairingCommitment.newNonce(random).also { myNonce = it }
        val c = PairingCommitment.commit(nonce, PairingCommitment.ROLE_RECEIVER, keys.transcriptHash, round, PairingCommitment.bit(pairingCode!!, round))
        state = State.AWAIT_SENDER_REVEAL
        val confirm = Payloads.PairConfirm(stage = Payloads.PairStage.COMMIT, accepted = true, round = round, commitment = B64.enc(c))
        return HandshakeOutput(sendPlain = listOf(factory.create(MessageType.PAIR_CONFIRM, Payloads.PairConfirm.serializer(), confirm)))
    }

    private fun onSenderReveal(p: Payloads.PairRequest): HandshakeOutput {
        if (p.stage != Payloads.PairStage.REVEAL || p.round != round) throw MirrorException(ErrorCode.PAIRING_FAILED, "bad reveal round")
        val ok = PairingCommitment.verify(senderCommitment!!, B64.dec(p.nonce), PairingCommitment.ROLE_SENDER, keys.transcriptHash, round, PairingCommitment.bit(pairingCode!!, round))
        if (!ok) {
            pairingCode = null // burn the code: one wrong guess ends this code's life
            throw MirrorException(ErrorCode.PAIRING_FAILED, "sender commitment mismatch at bit $round")
        }
        val reveal = Payloads.PairConfirm(stage = Payloads.PairStage.REVEAL, accepted = true, round = round, nonce = B64.enc(myNonce!!))
        val revealMessage = factory.create(MessageType.PAIR_CONFIRM, Payloads.PairConfirm.serializer(), reveal)
        round++
        return if (round < PairingCommitment.CODE_BITS) {
            state = State.AWAIT_SENDER_COMMIT
            HandshakeOutput(sendPlain = listOf(revealMessage))
        } else {
            pairingCode = null
            val cred = PairingCredential.issue(identity.receiverId, random).also { issuedCredential = it }
            state = State.COMPLETE
            val issue = Payloads.PairConfirm(stage = Payloads.PairStage.ISSUE, accepted = true, credentialId = cred.credentialId, credentialSecret = B64.enc(cred.secret))
            HandshakeOutput(
                sendPlain = listOf(revealMessage),
                encryptAfterPlain = true,
                sendEncrypted = listOf(factory.create(MessageType.PAIR_CONFIRM, Payloads.PairConfirm.serializer(), issue)),
                completed = true,
            )
        }
    }
}

/**
 * The HELLO_ACK bytes are part of the transcript, so the receiver must send exactly the bytes it
 * hashed. Transports call [PreEncoded.bytesOf] to obtain them instead of re-encoding.
 */
object PreEncoded {
    private val cache = java.util.WeakHashMap<ControlMessage, ByteArray>()
    @Synchronized fun wrap(message: ControlMessage, bytes: ByteArray): ControlMessage { cache[message] = bytes; return message }
    @Synchronized fun bytesOf(message: ControlMessage): ByteArray = cache[message] ?: ControlCodec.encode(message)
}
