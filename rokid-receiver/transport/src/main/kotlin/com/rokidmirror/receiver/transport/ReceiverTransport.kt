package com.rokidmirror.receiver.transport

import com.rokidmirror.protocol.Protocol
import com.rokidmirror.protocol.control.ControlCodec
import com.rokidmirror.protocol.control.ControlMessage
import com.rokidmirror.protocol.control.ErrorCode
import com.rokidmirror.protocol.control.MessageFactory
import com.rokidmirror.protocol.control.MessageType
import com.rokidmirror.protocol.control.MirrorException
import com.rokidmirror.protocol.control.Payloads
import com.rokidmirror.protocol.crypto.ControlChannelCipher
import com.rokidmirror.protocol.crypto.PairingCredential
import com.rokidmirror.protocol.crypto.PreEncoded
import com.rokidmirror.protocol.crypto.ReceiverHandshake
import com.rokidmirror.protocol.crypto.ReceiverIdentity
import com.rokidmirror.protocol.crypto.VideoCipher
import com.rokidmirror.protocol.transport.ClockSync
import com.rokidmirror.protocol.transport.Framing
import com.rokidmirror.protocol.video.EncodedAccessUnit
import com.rokidmirror.protocol.video.ReassemblyResult
import com.rokidmirror.protocol.video.ReassemblyStats
import com.rokidmirror.protocol.video.Reassembler
import com.rokidmirror.protocol.video.VideoPacketHeader
import com.rokidmirror.receiver.telemetry.ReceiverLog
import kotlinx.serialization.KSerializer
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Persistence of sender credentials issued by this receiver. */
interface ReceiverCredentialStore {
    fun lookup(senderId: String, credentialId: String): ByteArray?
    fun store(senderId: String, credential: PairingCredential)
    fun forgetAll()
    fun count(): Int
}

/** Callbacks from the transport; invoked on transport threads, keep them short. */
interface ReceiverTransportListener {
    fun onListening(controlPort: Int, videoPort: Int)
    fun onSenderConnecting(senderName: String)
    fun onShowPairingCode(code: String)
    fun onPairingFailed(reason: String)
    fun onConnected(senderName: String, sessionId: String)
    fun onControlMessage(message: ControlMessage)
    /** Complete access unit with T3/T4 arrival timestamps (local monotonic ns). */
    fun onAccessUnit(unit: EncodedAccessUnit, firstPacketNs: Long, completeNs: Long)
    fun onReassemblyStats(stats: ReassemblyStats)
    fun onDisconnected(reason: String)
}

/**
 * Receiver side of the LAN transport: TCP control server with the pairing handshake, then an
 * encrypted control channel and AEAD-sealed UDP video datagrams reassembled per session.
 * One sender at a time; unknown senders can only get in through the pairing code.
 */
class ReceiverTransport(
    private val identity: ReceiverIdentity,
    private val credentials: ReceiverCredentialStore,
    private val capabilities: () -> Payloads.Capabilities,
    private val listener: ReceiverTransportListener,
    private val controlPort: Int = Protocol.DEFAULT_CONTROL_PORT,
    private val clockNs: () -> Long = System::nanoTime,
) {
    private companion object { const val TAG = "Transport" }

    private var server: ServerSocket? = null
    private var udp: DatagramSocket? = null
    private var acceptThread: Thread? = null
    private var udpThread: Thread? = null
    private val running = AtomicBoolean(false)
    private val connection = AtomicReference<Connection?>(null)
    val clockSync = ClockSync()

    val isConnected: Boolean get() = connection.get() != null
    val boundVideoPort: Int get() = udp?.localPort ?: identity.videoPort

    fun start() {
        if (running.getAndSet(true)) return
        server = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(controlPort)) }
        udp = DatagramSocket(null).apply { reuseAddress = true; receiveBufferSize = 2 shl 20; bind(InetSocketAddress(identity.videoPort)) }
        listener.onListening(controlPort, udp!!.localPort)
        acceptThread = Thread(::acceptLoop, "control-accept").apply { isDaemon = true; start() }
        udpThread = Thread(::udpLoop, "video-receive").apply { isDaemon = true; priority = Thread.MAX_PRIORITY - 1; start() }
        ReceiverLog.i(TAG, "listening", "control" to controlPort, "video" to udp!!.localPort)
    }

    fun stop() {
        running.set(false)
        connection.getAndSet(null)?.close("receiver stopped")
        runCatching { server?.close() }; runCatching { udp?.close() }
        server = null; udp = null
    }

    fun disconnectCurrent(reason: String) { connection.getAndSet(null)?.close(reason) }

    fun <T> send(type: MessageType, serializer: KSerializer<T>, payload: T) { connection.get()?.send(type, serializer, payload) }
    fun requestKeyframe(reason: String, lastFrameId: Long = -1) = send(MessageType.KEYFRAME_REQUEST, Payloads.KeyframeRequest.serializer(), Payloads.KeyframeRequest(reason, lastFrameId))
    fun sendStats(stats: Payloads.Stats) = send(MessageType.STATS, Payloads.Stats.serializer(), stats)
    fun sendError(code: ErrorCode, message: String) = send(MessageType.ERROR, Payloads.Error.serializer(), Payloads.Error(code.name, message, code.recoverable))
    fun ping() = send(MessageType.PING, Payloads.Ping.serializer(), Payloads.Ping(clockNs()))

    /** Called on STREAM_START so the reassembler binds to the announced session short id. */
    fun bindVideoSession(sessionShortId: Long) { connection.get()?.bindVideo(sessionShortId) }
    fun reassemblyStats(): ReassemblyStats? = connection.get()?.reassembler?.stats
    fun requireKeyframe() { connection.get()?.reassembler?.requireKeyframe() }

    private fun acceptLoop() {
        while (running.get()) {
            val socket = try { server?.accept() ?: break } catch (_: SocketException) { break } catch (e: Exception) { ReceiverLog.w(TAG, "accept_failed", "error" to e.message); continue }
            socket.tcpNoDelay = true
            val existing = connection.get()
            if (existing != null) {
                // Busy: refuse politely. A paired phone reconnecting after a link loss usually
                // arrives after our read loop noticed the old socket died; if not, the old one
                // is replaced once its control timeout fires.
                ReceiverLog.w(TAG, "rejected_second_sender", "from" to socket.inetAddress.hostAddress)
                runCatching {
                    val f = MessageFactory("none", clockNs)
                    Framing.write(socket.getOutputStream(), ControlCodec.encode(f.create(MessageType.ERROR, Payloads.Error.serializer(), Payloads.Error(ErrorCode.RESOURCE_EXHAUSTED.name, "receiver busy with another sender", true))))
                }
                runCatching { socket.close() }
                continue
            }
            val conn = Connection(socket)
            connection.set(conn)
            Thread({ conn.run() }, "control-session").apply { isDaemon = true; start() }
        }
    }

    private fun udpLoop() {
        val sock = udp ?: return
        val buf = ByteArray(2048)
        val packet = DatagramPacket(buf, buf.size)
        while (running.get()) {
            try { sock.receive(packet) } catch (_: SocketException) { break } catch (e: Exception) { ReceiverLog.w(TAG, "udp_receive_failed", "error" to e.message); continue }
            val now = clockNs()
            val conn = connection.get() ?: continue
            if (packet.address != conn.peerAddress) continue // only the authenticated sender's host
            conn.onDatagram(buf, packet.length, now)
        }
    }

    /** One authenticated sender session. */
    private inner class Connection(private val socket: Socket) {
        val peerAddress: InetAddress = socket.inetAddress
        private val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        private val output = BufferedOutputStream(socket.getOutputStream())
        private val writeLock = Any()
        private var cipher: ControlChannelCipher? = null
        private var videoCipher: VideoCipher? = null
        private lateinit var factory: MessageFactory
        @Volatile var reassembler: Reassembler? = null; private set
        private val closed = AtomicBoolean(false)
        private var senderName = "?"
        private var badPackets = 0L

        fun run() {
            try {
                handshake()
                readLoop()
            } catch (e: MirrorException) {
                ReceiverLog.w(TAG, "session_ended", "code" to e.code, "details" to e.details)
                if (e.code == ErrorCode.PAIRING_FAILED || e.code == ErrorCode.AUTHENTICATION_FAILED) listener.onPairingFailed(e.details ?: e.code.name)
                close(e.details ?: e.code.name)
            } catch (e: Exception) {
                if (!closed.get()) ReceiverLog.w(TAG, "session_error", "error" to e.message)
                close(e.message ?: "error")
            }
        }

        private fun handshake() {
            socket.soTimeout = Protocol.PAIRING_CODE_TTL_MS.toInt() + 5000
            val hs = ReceiverHandshake(identity.copy(videoPort = boundVideoPort), credentials::lookup, clockNs)
            var encrypted = false
            while (hs.state != ReceiverHandshake.State.COMPLETE) {
                val raw = Framing.read(input) ?: throw MirrorException(ErrorCode.NETWORK_LOST, "sender closed during handshake")
                val plain = if (encrypted) cipher!!.open(raw) else raw
                val msg = ControlCodec.decode(plain)
                if (msg.type == MessageType.HELLO) {
                    senderName = runCatching { ControlCodec.payloadOf(msg, Payloads.Hello.serializer()).senderName }.getOrDefault("?")
                    listener.onSenderConnecting(senderName)
                }
                val out = try { hs.onMessage(msg, plain) } catch (e: MirrorException) {
                    hs.errorMessage(e)?.let { em -> runCatching { writeRaw(if (encrypted) cipher!!.seal(ControlCodec.encode(em)) else ControlCodec.encode(em)) } }
                    throw e
                }
                out.showPairingCode?.let { listener.onShowPairingCode(it) }
                for (m in out.sendPlain) writeRaw(PreEncoded.bytesOf(m))
                if (out.encryptAfterPlain) {
                    cipher = ControlChannelCipher(hs.keys.controlToSender, hs.keys.controlToReceiver, ControlChannelCipher.DIRECTION_TO_SENDER, ControlChannelCipher.DIRECTION_TO_RECEIVER)
                    encrypted = true
                }
                for (m in out.sendEncrypted) writeRaw(cipher!!.seal(ControlCodec.encode(m)))
            }
            factory = hs.factory
            videoCipher = VideoCipher(hs.keys.video)
            hs.issuedCredential?.let { credentials.store(hs.hello.senderId, it) }
            socket.soTimeout = Protocol.CONTROL_TIMEOUT_MS.toInt()
            send(MessageType.CAPABILITIES, Payloads.Capabilities.serializer(), capabilities())
            listener.onConnected(senderName, factory.sessionId)
            ReceiverLog.i(TAG, "sender_authenticated", "sender" to senderName, "byCredential" to hs.authenticatedByCredential)
        }

        private fun readLoop() {
            while (!closed.get()) {
                val raw = try { Framing.read(input) } catch (e: java.net.SocketTimeoutException) { throw MirrorException(ErrorCode.NETWORK_LOST, "control timeout") }
                    ?: throw MirrorException(ErrorCode.NETWORK_LOST, "sender closed the connection")
                val msg = ControlCodec.decode(cipher!!.open(raw))
                when (msg.type) {
                    MessageType.PING -> { val p = ControlCodec.payloadOf(msg, Payloads.Ping.serializer()); send(MessageType.PONG, Payloads.Pong.serializer(), Payloads.Pong(p.sentNs, clockNs())) }
                    MessageType.PONG -> { val p = ControlCodec.payloadOf(msg, Payloads.Pong.serializer()); clockSync.onPong(p.sentNs, p.receiverNs, clockNs()) }
                    MessageType.GOODBYE -> throw MirrorException(ErrorCode.NETWORK_LOST, "sender said goodbye")
                    MessageType.STREAM_START -> {
                        val s = ControlCodec.payloadOf(msg, Payloads.StreamStart.serializer())
                        bindVideo(s.sessionShortId)
                        listener.onControlMessage(msg)
                    }
                    else -> listener.onControlMessage(msg)
                }
            }
        }

        fun bindVideo(sessionShortId: Long) {
            reassembler = Reassembler(sessionShortId, Protocol.REASSEMBLY_TIMEOUT_MS * 1_000_000)
            ReceiverLog.i(TAG, "video_session_bound", "shortId" to sessionShortId)
        }

        fun onDatagram(buf: ByteArray, length: Int, nowNs: Long) {
            val r = reassembler ?: return
            val vc = videoCipher ?: return
            try {
                val header = VideoPacketHeader.parse(buf, 0, length)
                val cipherText = buf.copyOfRange(VideoPacketHeader.SIZE, length)
                val plain = vc.open(header, cipherText)
                when (val res = r.accept(header, plain, nowNs)) {
                    is ReassemblyResult.Complete -> listener.onAccessUnit(res.unit, res.firstPacketArrivalNs, res.completeArrivalNs)
                    else -> {}
                }
                listener.onReassemblyStats(r.stats)
            } catch (e: MirrorException) {
                if (++badPackets % 100 == 1L) ReceiverLog.w(TAG, "bad_video_packet", "count" to badPackets, "details" to e.details)
            }
        }

        fun <T> send(type: MessageType, serializer: KSerializer<T>, payload: T) {
            val c = cipher ?: return
            if (closed.get()) return
            try { writeRaw(c.seal(ControlCodec.encode(factory.create(type, serializer, payload)))) } catch (e: Exception) { close("write failed: ${e.message}") }
        }

        private fun writeRaw(bytes: ByteArray) = synchronized(writeLock) { Framing.write(output, bytes) }

        fun close(reason: String) {
            if (closed.getAndSet(true)) return
            runCatching { socket.close() }
            connection.compareAndSet(this, null)
            listener.onDisconnected(reason)
            ReceiverLog.i(TAG, "connection_closed", "reason" to reason)
        }
    }
}
