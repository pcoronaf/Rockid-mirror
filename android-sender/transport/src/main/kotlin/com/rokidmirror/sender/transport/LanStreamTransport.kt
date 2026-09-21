package com.rokidmirror.sender.transport

import com.rokidmirror.protocol.Protocol
import com.rokidmirror.protocol.control.ControlCodec
import com.rokidmirror.protocol.control.ControlMessage
import com.rokidmirror.protocol.control.ErrorCode
import com.rokidmirror.protocol.control.MessageFactory
import com.rokidmirror.protocol.control.MessageType
import com.rokidmirror.protocol.control.MirrorException
import com.rokidmirror.protocol.control.Payloads
import com.rokidmirror.protocol.crypto.ControlChannelCipher
import com.rokidmirror.protocol.crypto.HandshakeOutput
import com.rokidmirror.protocol.crypto.PairingCredential
import com.rokidmirror.protocol.crypto.PreEncoded
import com.rokidmirror.protocol.crypto.SenderHandshake
import com.rokidmirror.protocol.crypto.SenderIdentity
import com.rokidmirror.protocol.crypto.VideoCipher
import com.rokidmirror.protocol.transport.ClockSync
import com.rokidmirror.protocol.transport.Framing
import com.rokidmirror.protocol.video.EncodedAccessUnit
import com.rokidmirror.protocol.video.Fragmenter
import com.rokidmirror.sender.telemetry.MirrorLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.KSerializer
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Local-network transport: authenticated, encrypted TCP control channel plus AEAD-sealed UDP
 * video datagrams (the specification's non-WebRTC fallback, see docs/protocol.md).
 *
 * Video send path: encoder thread -> bounded queue -> single sender thread. When the queue is
 * full the unit is dropped, and delta frames keep being dropped until the next keyframe so the
 * decoder never sees a broken reference chain (freshness over completeness).
 */
class LanStreamTransport(
    private val identity: SenderIdentity,
    private val clockNs: () -> Long = System::nanoTime,
) : StreamTransport {
    private companion object {
        const val TAG = "Transport"
        const val CONNECT_TIMEOUT_MS = 4000
        const val HANDSHAKE_TIMEOUT_MS = 120_000L // includes the time the user needs to type the code
        const val SEND_QUEUE_UNITS = 6
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<TransportState>(TransportState.Disconnected)
    override val state: StateFlow<TransportState> = _state.asStateFlow()
    private val _statistics = MutableStateFlow(TransportStatistics())
    override val statistics: StateFlow<TransportStatistics> = _statistics.asStateFlow()
    private val _events = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 32)
    override val events: SharedFlow<TransportEvent> = _events.asSharedFlow()

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: BufferedOutputStream? = null
    private var udp: DatagramSocket? = null
    private var udpTarget: InetSocketAddress? = null
    private var controlCipher: ControlChannelCipher? = null
    private var videoCipher: VideoCipher? = null
    private var fragmenter: Fragmenter? = null
    private var factory: MessageFactory? = null
    private val writeMutex = Mutex()
    private val clockSync = ClockSync()
    private var readerJob: Job? = null
    private var pingJob: Job? = null
    private var senderThread: Thread? = null
    private val sendQueue = ArrayBlockingQueue<EncodedAccessUnit>(SEND_QUEUE_UNITS)
    private val awaitingKeyframeAfterDrop = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val packetsSent = AtomicLong(); private val bytesSent = AtomicLong(); private val unitsSent = AtomicLong(); private val unitsDropped = AtomicLong()
    private var capabilities: Payloads.Capabilities? = null
    private var endpoint: ReceiverEndpoint? = null

    override var sessionId: String? = null; private set
    override var sessionShortId: Long = 0; private set

    override suspend fun connect(receiver: ReceiverEndpoint, credential: PairingCredential?, pairingCodeProvider: suspend () -> String) {
        withContext(Dispatchers.IO) {
            endpoint = receiver
            closed.set(false)
            _state.value = TransportState.Connecting(receiver)
            try {
                val s = Socket().apply { tcpNoDelay = true; soTimeout = HANDSHAKE_TIMEOUT_MS.toInt() }
                runInterruptible { s.connect(InetSocketAddress(receiver.host, receiver.controlPort), CONNECT_TIMEOUT_MS) }
                socket = s
                input = DataInputStream(BufferedInputStream(s.getInputStream()))
                output = BufferedOutputStream(s.getOutputStream())
                withTimeout(HANDSHAKE_TIMEOUT_MS) { handshake(receiver, credential, pairingCodeProvider) }
                // Receiver pings every second; 5 s of silence means the link is gone.
                s.soTimeout = Protocol.CONTROL_TIMEOUT_MS.toInt()
                startBackgroundWork()
                _state.value = TransportState.Connected(receiver, null)
                MirrorLog.i(TAG, "connected", "receiver" to receiver.name, "session" to sessionId)
            } catch (e: MirrorException) {
                failAndClose(e.code, e.details ?: e.message, emitEvent = false)
                throw e
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                failAndClose(ErrorCode.PAIRING_FAILED, "handshake timed out", emitEvent = false)
                throw MirrorException(ErrorCode.PAIRING_FAILED, "handshake timed out", e)
            } catch (e: Exception) {
                val code = if (e is java.net.ConnectException || e is java.net.SocketTimeoutException || e is java.net.NoRouteToHostException) ErrorCode.RECEIVER_NOT_FOUND else ErrorCode.NETWORK_LOST
                failAndClose(code, e.message, emitEvent = false)
                throw MirrorException(code, e.message, e)
            }
        }
    }

    private suspend fun handshake(receiver: ReceiverEndpoint, credential: PairingCredential?, pairingCodeProvider: suspend () -> String) {
        val sid = UUID.randomUUID().toString()
        sessionId = sid
        sessionShortId = SecureRandom().nextInt().toLong() and 0xFFFF_FFFFL
        val f = MessageFactory(sid, clockNs).also { factory = it }
        val hs = SenderHandshake(identity, credential, f)
        _state.value = TransportState.Handshaking(receiver, awaitingPairingCode = false)
        hs.start()
        writeFrame(hs.helloWireBytes())
        var encrypted = false
        suspend fun act(out: HandshakeOutput) {
            for (m in out.sendPlain) writeFrame(PreEncoded.bytesOf(m))
            if (out.encryptAfterPlain) { enableEncryption(hs); encrypted = true }
            for (m in out.sendEncrypted) writeFrame(controlCipher!!.seal(ControlCodec.encode(m)))
            if (out.needPairingCode) {
                _state.value = TransportState.Handshaking(receiver, awaitingPairingCode = true)
                val code = pairingCodeProvider()
                _state.value = TransportState.Handshaking(receiver, awaitingPairingCode = false)
                act(hs.providePairingCode(code))
            }
        }
        while (hs.state != SenderHandshake.State.COMPLETE) {
            val raw = runInterruptible { Framing.read(input!!) } ?: throw MirrorException(ErrorCode.NETWORK_LOST, "receiver closed during handshake")
            val plain = if (encrypted) controlCipher!!.open(raw) else raw
            val msg = ControlCodec.decode(plain)
            act(hs.onMessage(msg, plain))
        }
        if (!encrypted) enableEncryption(hs)
        hs.credential?.let { if (it !== credential) _events.tryEmit(TransportEvent.CredentialIssued(it)) }
        videoCipher = VideoCipher(hs.keys.video)
        fragmenter = Fragmenter(sessionShortId, Protocol.MAX_DATAGRAM_BYTES, VideoCipher.OVERHEAD_BYTES)
        udpTarget = InetSocketAddress(InetAddress.getByName(receiver.host), hs.helloAck.videoPort)
        udp = DatagramSocket().apply { sendBufferSize = 1 shl 20 }
    }

    private fun enableEncryption(hs: SenderHandshake) {
        controlCipher = ControlChannelCipher(hs.keys.controlToReceiver, hs.keys.controlToSender, ControlChannelCipher.DIRECTION_TO_RECEIVER, ControlChannelCipher.DIRECTION_TO_SENDER)
    }

    private fun startBackgroundWork() {
        val t = Thread(::videoSendLoop, "video-send").apply { isDaemon = true; priority = Thread.MAX_PRIORITY - 1 }
        senderThread = t
        t.start()
        readerJob = scope.launch { readLoop() }
        pingJob = scope.launch {
            while (isActive && !closed.get()) {
                runCatching { send(MessageType.PING, Payloads.Ping.serializer(), Payloads.Ping(clockNs())) }
                delay(Protocol.PING_INTERVAL_MS)
            }
        }
    }

    private suspend fun readLoop() {
        try {
            while (!closed.get()) {
                val raw = try { runInterruptible { Framing.read(input!!) } } catch (e: java.net.SocketTimeoutException) { throw MirrorException(ErrorCode.NETWORK_LOST, "control timeout") }
                    ?: throw MirrorException(ErrorCode.NETWORK_LOST, "receiver closed the connection")
                val msg = ControlCodec.decode(controlCipher!!.open(raw))
                handle(msg)
            }
        } catch (e: MirrorException) {
            if (!closed.get()) { MirrorLog.w(TAG, "link_lost", "code" to e.code, "details" to e.details); failAndClose(e.code, e.details) }
        } catch (e: Exception) {
            if (!closed.get()) { MirrorLog.w(TAG, "link_lost", "error" to e.message); failAndClose(ErrorCode.NETWORK_LOST, e.message) }
        }
    }

    private fun handle(msg: ControlMessage) {
        when (msg.type) {
            MessageType.CAPABILITIES -> {
                val caps = ControlCodec.payloadOf(msg, Payloads.Capabilities.serializer())
                capabilities = caps
                (_state.value as? TransportState.Connected)?.let { _state.value = it.copy(capabilities = caps) }
                _events.tryEmit(TransportEvent.CapabilitiesReceived(caps))
            }
            MessageType.PONG -> {
                val p = ControlCodec.payloadOf(msg, Payloads.Pong.serializer())
                clockSync.onPong(p.sentNs, p.receiverNs, clockNs())
                updateStats()
            }
            MessageType.PING -> {
                val p = ControlCodec.payloadOf(msg, Payloads.Ping.serializer())
                scope.launch { runCatching { send(MessageType.PONG, Payloads.Pong.serializer(), Payloads.Pong(p.sentNs, clockNs())) } }
            }
            MessageType.KEYFRAME_REQUEST -> {
                val p = ControlCodec.payloadOf(msg, Payloads.KeyframeRequest.serializer())
                _events.tryEmit(TransportEvent.KeyframeRequested(p.reason))
            }
            MessageType.STATS -> {
                val s = ControlCodec.payloadOf(msg, Payloads.Stats.serializer())
                _statistics.value = _statistics.value.copy(receiverStats = s, lossFraction = if (s.packetsReceived + s.packetsLost > 0) s.packetsLost.toFloat() / (s.packetsReceived + s.packetsLost) else 0f)
                _events.tryEmit(TransportEvent.StatsReceived(s))
            }
            MessageType.ERROR -> {
                val e = ControlCodec.payloadOf(msg, Payloads.Error.serializer())
                _events.tryEmit(TransportEvent.ReceiverError(ErrorCode.fromWire(e.code), e.message))
            }
            MessageType.GOODBYE -> throw MirrorException(ErrorCode.NETWORK_LOST, "receiver said goodbye")
            else -> MirrorLog.d(TAG, "ignored_message", "type" to msg.type)
        }
    }

    private fun updateStats() {
        _statistics.value = _statistics.value.copy(
            rttMs = if (clockSync.lastRttNs >= 0) clockSync.lastRttNs / 1e6f else -1f,
            minRttMs = if (clockSync.minRttNs >= 0) clockSync.minRttNs / 1e6f else -1f,
            packetsSent = packetsSent.get(), bytesSent = bytesSent.get(), unitsSent = unitsSent.get(), unitsDroppedAtSender = unitsDropped.get(),
            clockOffsetNs = clockSync.offsetNs,
        )
    }

    override fun sendVideo(unit: EncodedAccessUnit) {
        if (closed.get() || videoCipher == null) return
        if (awaitingKeyframeAfterDrop.get() && !unit.isKeyframe && !unit.isConfig) { unitsDropped.incrementAndGet(); return }
        if (unit.isKeyframe) awaitingKeyframeAfterDrop.set(false)
        if (!sendQueue.offer(unit)) {
            unitsDropped.incrementAndGet()
            if (!unit.isKeyframe) awaitingKeyframeAfterDrop.set(true)
            else { sendQueue.clear(); sendQueue.offer(unit) } // a keyframe always supersedes queued deltas
            MirrorLog.w(TAG, "send_queue_full", "dropped" to unitsDropped.get())
            _events.tryEmit(TransportEvent.KeyframeRequested("sender queue overflow"))
        }
    }

    private fun videoSendLoop() {
        val sock = udp ?: return
        val target = udpTarget ?: return
        val frag = fragmenter ?: return
        val cipher = videoCipher ?: return
        try {
            while (!closed.get()) {
                val unit = sendQueue.take()
                val packets = frag.fragment(unit) { header, plain -> cipher.seal(header, plain) }
                for (p in packets) {
                    sock.send(DatagramPacket(p.bytes, p.bytes.size, target))
                    packetsSent.incrementAndGet(); bytesSent.addAndGet(p.bytes.size.toLong())
                }
                unitsSent.incrementAndGet()
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            if (!closed.get()) { MirrorLog.e(TAG, "video_send_failed", e); scope.launch { failAndClose(ErrorCode.NETWORK_LOST, e.message) } }
        }
    }

    override suspend fun sendControl(message: ControlMessage) = writeMutex.withLock {
        val cipher = controlCipher ?: throw MirrorException(ErrorCode.NETWORK_LOST, "not connected")
        writeLocked(cipher.seal(ControlCodec.encode(message)))
    }

    /**
     * The message number, the cipher counter and the socket write all happen under one lock.
     * Pings, viewport updates, pointer updates and session control come from different
     * coroutines, and interleaving them produced counters the receiver saw out of order.
     */
    override suspend fun <T> send(type: MessageType, serializer: KSerializer<T>, payload: T) = writeMutex.withLock {
        val f = factory ?: throw MirrorException(ErrorCode.NETWORK_LOST, "not connected")
        val cipher = controlCipher ?: throw MirrorException(ErrorCode.NETWORK_LOST, "not connected")
        writeLocked(cipher.seal(ControlCodec.encode(f.create(type, serializer, payload))))
    }

    private suspend fun writeFrame(bytes: ByteArray) = writeMutex.withLock { writeLocked(bytes) }

    /** Caller must hold [writeMutex]. */
    private suspend fun writeLocked(bytes: ByteArray) {
        val out = output ?: throw MirrorException(ErrorCode.NETWORK_LOST, "not connected")
        try { runInterruptible { Framing.write(out, bytes) } } catch (e: java.io.IOException) { throw MirrorException(ErrorCode.NETWORK_LOST, e.message, e) }
    }

    override suspend fun close(reason: String) {
        if (closed.getAndSet(true)) return
        runCatching { withTimeout(500) { factory?.let { f -> writeFrameQuiet(controlCipher?.seal(ControlCodec.encode(f.create(MessageType.GOODBYE, Payloads.Goodbye.serializer(), Payloads.Goodbye(reason))))) } } }
        teardown()
        _state.value = TransportState.Disconnected
        MirrorLog.i(TAG, "closed", "reason" to reason)
    }

    private suspend fun writeFrameQuiet(bytes: ByteArray?) { if (bytes != null) runCatching { writeFrame(bytes) } }

    private suspend fun failAndClose(code: ErrorCode, details: String?, emitEvent: Boolean = true) {
        val wasClosed = closed.getAndSet(true)
        teardown()
        if (!wasClosed) {
            _state.value = TransportState.Failed(endpoint, code, details)
            if (emitEvent) _events.tryEmit(TransportEvent.Disconnected(code, details))
        }
    }

    private suspend fun teardown() {
        senderThread?.interrupt(); senderThread = null
        sendQueue.clear()
        runCatching { socket?.close() }
        runCatching { udp?.close() }
        socket = null; input = null; output = null; udp = null
        pingJob?.cancelAndJoin(); pingJob = null
        val r = readerJob; readerJob = null
        if (r != null && r != kotlinx.coroutines.currentCoroutineContext()[Job]) r.cancelAndJoin()
        controlCipher = null; videoCipher = null; fragmenter = null; factory = null
        clockSync.reset()
        awaitingKeyframeAfterDrop.set(false)
    }
}
