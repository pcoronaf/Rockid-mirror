package com.rokidmirror.tools.mockreceiver

import com.rokidmirror.protocol.Protocol
import com.rokidmirror.protocol.control.ControlCodec
import com.rokidmirror.protocol.control.ErrorCode
import com.rokidmirror.protocol.control.MessageType
import com.rokidmirror.protocol.control.MirrorException
import com.rokidmirror.protocol.control.Payloads
import com.rokidmirror.protocol.crypto.ControlChannelCipher
import com.rokidmirror.protocol.crypto.PairingCommitment
import com.rokidmirror.protocol.crypto.PairingCredential
import com.rokidmirror.protocol.crypto.PreEncoded
import com.rokidmirror.protocol.crypto.ReceiverHandshake
import com.rokidmirror.protocol.crypto.ReceiverIdentity
import com.rokidmirror.protocol.crypto.VideoCipher
import com.rokidmirror.protocol.transport.Framing
import com.rokidmirror.protocol.video.ReassemblyResult
import com.rokidmirror.protocol.video.Reassembler
import com.rokidmirror.protocol.video.VideoPacketHeader
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/**
 * Desktop mock receiver: lets the Android sender be tested end to end without the glasses.
 * Speaks the full protocol (pairing, encryption, reassembly), prints stats once a second and
 * can dump the elementary stream to a file you can play with `ffplay -fflags nobuffer out.h264`.
 *
 * Usage: mock-receiver [--port 47010] [--video-port 47011] [--name "Desk mock"] [--dump out.h264] [--no-mdns]
 */
fun main(args: Array<String>) {
    var port = Protocol.DEFAULT_CONTROL_PORT
    var videoPort = Protocol.DEFAULT_VIDEO_PORT
    var name = "Mock receiver ${InetAddress.getLocalHost().hostName}"
    var dump: File? = null
    var mdns = true
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--port" -> port = args[++i].toInt()
            "--video-port" -> videoPort = args[++i].toInt()
            "--name" -> name = args[++i]
            "--dump" -> dump = File(args[++i])
            "--no-mdns" -> mdns = false
            else -> { System.err.println("unknown arg ${args[i]}"); return }
        }
        i++
    }
    val credentials = HashMap<String, ByteArray>() // in memory: pairing is repeated after restart
    val identity = ReceiverIdentity("mock-" + UUID.randomUUID().toString().take(8), name, "tools-0.1", videoPort)
    val udp = DatagramSocket(videoPort).apply { receiveBufferSize = 2 shl 20 }
    val server = ServerSocket(port)
    val capabilities = Payloads.Capabilities(
        display = Payloads.DisplaySize(480, 640, 240, 60f),
        codecs = listOf(Payloads.CodecCapability("h264", listOf("baseline", "main", "high"), hardware = false, lowLatency = false)),
        maxDecode = Payloads.DecodeLimit(1920, 1080, 60),
        receiverModel = "desktop mock", receiverOs = System.getProperty("os.name") ?: "jvm",
    )
    var jm: JmDNS? = null
    if (mdns) {
        jm = JmDNS.create(InetAddress.getLocalHost())
        jm.registerService(ServiceInfo.create(Protocol.SERVICE_TYPE + "local.", name, port, 0, 0, mapOf("id" to identity.receiverId, "name" to name, "w" to "480", "h" to "640", "v" to "1")))
        println("mDNS: advertising ${Protocol.SERVICE_TYPE} '$name' on ${InetAddress.getLocalHost().hostAddress}:$port")
    }
    println("listening control tcp/$port video udp/$videoPort; dump=${dump?.path ?: "none"}")
    Runtime.getRuntime().addShutdownHook(Thread { jm?.unregisterAllServices(); jm?.close() })

    val current = AtomicReference<Session?>(null)
    Thread({
        val buf = ByteArray(2048); val p = DatagramPacket(buf, buf.size)
        while (true) { udp.receive(p); current.get()?.onDatagram(buf, p.length, System.nanoTime()) }
    }, "udp").apply { isDaemon = true }.start()

    while (true) {
        val socket = server.accept()
        socket.tcpNoDelay = true
        val s = Session(socket, identity, credentials, capabilities, dump)
        current.set(s)
        try { s.run() } catch (e: MirrorException) { println("session ended: ${e.code} ${e.details ?: ""}") } catch (e: Exception) { println("session ended: ${e.message}") }
        current.set(null)
        runCatching { socket.close() }
        println("waiting for next sender…")
    }
}

private class Session(
    private val socket: Socket,
    private val identity: ReceiverIdentity,
    private val credentials: MutableMap<String, ByteArray>,
    private val capabilities: Payloads.Capabilities,
    dumpFile: File?,
) {
    private val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
    private val output = BufferedOutputStream(socket.getOutputStream())
    private var cipher: ControlChannelCipher? = null
    private var videoCipher: VideoCipher? = null
    @Volatile private var reassembler: Reassembler? = null
    private val dump = dumpFile?.let { FileOutputStream(it) }
    private lateinit var hs: ReceiverHandshake
    private var unitsInWindow = 0; private var bytesInWindow = 0L; private var windowStart = System.nanoTime(); private var keyframes = 0
    private var lastFrameId = -1L

    fun run() {
        hs = ReceiverHandshake(identity, { s, c -> credentials["$s|$c"] }, System::nanoTime)
        var encrypted = false
        while (hs.state != ReceiverHandshake.State.COMPLETE) {
            val raw = Framing.read(input) ?: throw MirrorException(ErrorCode.NETWORK_LOST, "closed in handshake")
            val plain = if (encrypted) cipher!!.open(raw) else raw
            val msg = ControlCodec.decode(plain)
            val out = try { hs.onMessage(msg, plain) } catch (e: MirrorException) { hs.errorMessage(e)?.let { write(if (encrypted) cipher!!.seal(ControlCodec.encode(it)) else ControlCodec.encode(it)) }; throw e }
            out.showPairingCode?.let { println("\n=== PAIRING CODE: ${PairingCommitment.formatForDisplay(it)} === (enter on the phone)\n") }
            out.sendPlain.forEach { write(PreEncoded.bytesOf(it)) }
            if (out.encryptAfterPlain) { cipher = ControlChannelCipher(hs.keys.controlToSender, hs.keys.controlToReceiver, ControlChannelCipher.DIRECTION_TO_SENDER, ControlChannelCipher.DIRECTION_TO_RECEIVER); encrypted = true }
            out.sendEncrypted.forEach { write(cipher!!.seal(ControlCodec.encode(it))) }
        }
        hs.issuedCredential?.let { credentials["${hs.hello.senderId}|${it.credentialId}"] = it.secret; println("issued credential ${it.credentialId} to ${hs.hello.senderName}") }
        videoCipher = VideoCipher(hs.keys.video)
        println("authenticated ${hs.hello.senderName} (credential=${hs.authenticatedByCredential}); session ${hs.factory.sessionId}")
        send(MessageType.CAPABILITIES, Payloads.Capabilities.serializer(), capabilities)
        socket.soTimeout = Protocol.CONTROL_TIMEOUT_MS.toInt()
        val statsThread = Thread({ try { while (!socket.isClosed) { Thread.sleep(1000); runCatching { reportStats() } } } catch (_: InterruptedException) { } }, "stats").apply { isDaemon = true; start() }
        try {
            while (true) {
                val raw = Framing.read(input) ?: throw MirrorException(ErrorCode.NETWORK_LOST, "sender closed")
                val msg = ControlCodec.decode(cipher!!.open(raw))
                when (msg.type) {
                    MessageType.PING -> { val p = ControlCodec.payloadOf(msg, Payloads.Ping.serializer()); send(MessageType.PONG, Payloads.Pong.serializer(), Payloads.Pong(p.sentNs, System.nanoTime())) }
                    MessageType.STREAM_START -> { val s = ControlCodec.payloadOf(msg, Payloads.StreamStart.serializer()); reassembler = Reassembler(s.sessionShortId, Protocol.REASSEMBLY_TIMEOUT_MS * 1_000_000); println("STREAM_START ${s.codec} ${s.width}x${s.height}@${s.fps} ${s.bitrate / 1000}kbps source='${s.sourceName}'") }
                    MessageType.STREAM_FORMAT -> { val f = ControlCodec.payloadOf(msg, Payloads.StreamFormat.serializer()); println("STREAM_FORMAT ${f.width}x${f.height} source ${f.sourceWidth}x${f.sourceHeight} csd=${f.csd0 != null}") }
                    MessageType.GOODBYE -> throw MirrorException(ErrorCode.NETWORK_LOST, "goodbye")
                    MessageType.PONG -> {}
                    else -> println("${msg.type} ${msg.payload}")
                }
            }
        } finally { statsThread.interrupt(); dump?.close() }
    }

    fun onDatagram(buf: ByteArray, len: Int, now: Long) {
        val r = reassembler ?: return
        try {
            val h = VideoPacketHeader.parse(buf, 0, len)
            val plain = videoCipher!!.open(h, buf.copyOfRange(VideoPacketHeader.SIZE, len))
            val res = r.accept(h, plain, now)
            if (res is ReassemblyResult.Complete) {
                val u = res.unit
                if (lastFrameId >= 0 && u.frameId != lastFrameId + 1) println("frame gap ${lastFrameId} -> ${u.frameId}")
                lastFrameId = u.frameId
                unitsInWindow++; bytesInWindow += u.size; if (u.isKeyframe) keyframes++
                dump?.write(u.data)
            }
            if (r.stats.keyframeRequestsSuggested > 0 && r.isAwaitingKeyframe) send(MessageType.KEYFRAME_REQUEST, Payloads.KeyframeRequest.serializer(), Payloads.KeyframeRequest("loss"))
        } catch (e: MirrorException) { println("bad packet: ${e.details}") }
    }

    private fun reportStats() {
        val r = reassembler ?: return
        val now = System.nanoTime(); val sec = (now - windowStart) / 1e9f
        val s = r.stats
        println("%.1f fps  %.0f kbps  key %d  pkts %d lost %d dup %d  frames %d dropped %d skipped %d".format(unitsInWindow / sec, bytesInWindow * 8 / sec / 1000, keyframes, s.packetsReceived, s.packetsLost, s.packetsDuplicate, s.framesDelivered, s.framesDropped, s.framesSkippedAwaitingKeyframe))
        send(MessageType.STATS, Payloads.Stats.serializer(), Payloads.Stats(s.packetsReceived, s.packetsLost, s.framesDelivered, s.framesDropped, s.framesDelivered, 0, 0.5f, 0f, 0f, fps = unitsInWindow / sec, bitrateBps = (bytesInWindow * 8 / sec).toLong()))
        unitsInWindow = 0; bytesInWindow = 0; windowStart = now
    }

    private fun <T> send(type: MessageType, ser: kotlinx.serialization.KSerializer<T>, payload: T) { val c = cipher ?: return; write(c.seal(ControlCodec.encode(hs.factory.create(type, ser, payload)))) }
    @Synchronized private fun write(bytes: ByteArray) = Framing.write(output, bytes)
}
