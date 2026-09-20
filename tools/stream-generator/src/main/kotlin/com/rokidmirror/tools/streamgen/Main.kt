package com.rokidmirror.tools.streamgen

import com.rokidmirror.protocol.Protocol
import com.rokidmirror.protocol.control.ControlCodec
import com.rokidmirror.protocol.control.ErrorCode
import com.rokidmirror.protocol.control.MessageFactory
import com.rokidmirror.protocol.control.MessageType
import com.rokidmirror.protocol.control.MirrorException
import com.rokidmirror.protocol.control.Payloads
import com.rokidmirror.protocol.crypto.ControlChannelCipher
import com.rokidmirror.protocol.crypto.PreEncoded
import com.rokidmirror.protocol.crypto.SenderHandshake
import com.rokidmirror.protocol.crypto.SenderIdentity
import com.rokidmirror.protocol.crypto.VideoCipher
import com.rokidmirror.protocol.transport.Framing
import com.rokidmirror.protocol.video.EncodedAccessUnit
import com.rokidmirror.protocol.video.Fragmenter
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/**
 * Replays an Annex-B H.264 elementary stream to a receiver over the real protocol, so the
 * glasses receiver can be brought up and soak-tested (Task D: known test source, 10 minutes)
 * without a phone. Create a source with, e.g.:
 *   ffmpeg -f lavfi -i testsrc2=size=848x480:rate=30 -t 60 -c:v libx264 -profile:v main \
 *          -tune zerolatency -x264-params keyint=30:bframes=0 -bsf:v h264_mp4toannexb -f h264 test480p30.h264
 *
 * Usage: stream-generator --host <receiver-ip> [--port 47010] --file test.h264 [--fps 30] [--width 848 --height 480] [--loop]
 */
fun main(args: Array<String>) {
    var host = ""; var port = Protocol.DEFAULT_CONTROL_PORT; var file: File? = null; var fps = 30; var width = 848; var height = 480; var loop = false
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--host" -> host = args[++i]; "--port" -> port = args[++i].toInt(); "--file" -> file = File(args[++i])
            "--fps" -> fps = args[++i].toInt(); "--width" -> width = args[++i].toInt(); "--height" -> height = args[++i].toInt(); "--loop" -> loop = true
            else -> { System.err.println("unknown arg ${args[i]}"); return }
        }
        i++
    }
    if (host.isBlank() || file == null) { System.err.println("usage: --host <ip> --file <annexb.h264> [--fps 30] [--loop]"); return }
    val units = AnnexB.accessUnits(file.readBytes())
    val sps = units.firstOrNull { it.isConfig } ?: throw IllegalArgumentException("no SPS/PPS found")
    println("loaded ${units.size} access units (${units.count { it.isKeyframe }} keyframes) from ${file.name}")

    val socket = Socket().apply { tcpNoDelay = true; connect(InetSocketAddress(host, port), 4000) }
    val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
    val output = BufferedOutputStream(socket.getOutputStream())
    val factory = MessageFactory(UUID.randomUUID().toString(), System::nanoTime)
    val hs = SenderHandshake(SenderIdentity("streamgen-${UUID.randomUUID().toString().take(6)}", "Stream generator", "tools-0.1"), null, factory)
    var cipher: ControlChannelCipher? = null
    var encrypted = false
    fun write(b: ByteArray) = Framing.write(output, b)
    hs.start()
    write(hs.helloWireBytes())
    fun act(o: com.rokidmirror.protocol.crypto.HandshakeOutput) {
        o.sendPlain.forEach { write(PreEncoded.bytesOf(it)) }
        if (o.encryptAfterPlain) { cipher = ControlChannelCipher(hs.keys.controlToReceiver, hs.keys.controlToSender, ControlChannelCipher.DIRECTION_TO_RECEIVER, ControlChannelCipher.DIRECTION_TO_SENDER); encrypted = true }
        o.sendEncrypted.forEach { write(cipher!!.seal(ControlCodec.encode(it))) }
        if (o.needPairingCode) { print("Enter the pairing code shown on the receiver: "); act(hs.providePairingCode(readLine() ?: "")) }
    }
    while (hs.state != SenderHandshake.State.COMPLETE) {
        val raw = Framing.read(input) ?: throw MirrorException(ErrorCode.NETWORK_LOST, "closed")
        val plain = if (encrypted) cipher!!.open(raw) else raw
        act(hs.onMessage(ControlCodec.decode(plain), plain))
    }
    if (!encrypted) cipher = ControlChannelCipher(hs.keys.controlToReceiver, hs.keys.controlToSender, ControlChannelCipher.DIRECTION_TO_RECEIVER, ControlChannelCipher.DIRECTION_TO_SENDER)
    println("connected to ${hs.helloAck.receiverName}; video port ${hs.helloAck.videoPort}")
    val videoCipher = VideoCipher(hs.keys.video)
    val shortId = SecureRandom().nextInt().toLong() and 0xFFFF_FFFFL
    val fragmenter = Fragmenter(shortId, Protocol.MAX_DATAGRAM_BYTES, VideoCipher.OVERHEAD_BYTES)
    val udp = DatagramSocket(); val target = InetSocketAddress(host, hs.helloAck.videoPort)
    fun <T> send(t: MessageType, s: kotlinx.serialization.KSerializer<T>, p: T) = synchronized(output) { write(cipher!!.seal(ControlCodec.encode(factory.create(t, s, p)))) }

    // control reader: answer PINGs, print receiver stats and keyframe requests
    val keyframeWanted = java.util.concurrent.atomic.AtomicBoolean(false)
    Thread({
        try {
            while (true) {
                val raw = Framing.read(input) ?: break
                val m = ControlCodec.decode(cipher!!.open(raw))
                when (m.type) {
                    MessageType.PING -> send(MessageType.PONG, Payloads.Pong.serializer(), Payloads.Pong(ControlCodec.payloadOf(m, Payloads.Ping.serializer()).sentNs, System.nanoTime()))
                    MessageType.STATS -> { val s = ControlCodec.payloadOf(m, Payloads.Stats.serializer()); println("receiver: %.1f fps lost %d dropped %d asm %.1f dec %.1f rnd %.1f ms lag %d".format(s.fps, s.packetsLost, s.framesDropped, s.reassemblyMs, s.decodeMs, s.renderMs, s.decodeLagFrames)) }
                    MessageType.KEYFRAME_REQUEST -> { keyframeWanted.set(true); println("receiver requested keyframe") }
                    MessageType.CAPABILITIES -> println("capabilities: ${m.payload}")
                    else -> {}
                }
            }
        } catch (e: Exception) { println("control closed: ${e.message}") }
        System.exit(0)
    }, "control").apply { isDaemon = true; start() }

    send(MessageType.STREAM_START, Payloads.StreamStart.serializer(), Payloads.StreamStart("h264", width, height, fps, 2_000_000, "balanced", "stream-generator", shortId))
    val (csd0, csd1) = AnnexB.splitSpsPps(sps.data)
    send(MessageType.STREAM_FORMAT, Payloads.StreamFormat.serializer(), Payloads.StreamFormat("h264", width, height, fps, Base64.getEncoder().encodeToString(csd0), csd1?.let { Base64.getEncoder().encodeToString(it) }, width, height))
    send(MessageType.VIEWPORT_SET, Payloads.ViewportSet.serializer(), Payloads.ViewportSet(1f, 0.5f, 0.5f, "FIT"))

    val frameNs = 1_000_000_000L / fps
    var frameId = 0L; var next = System.nanoTime(); var sent = 0L; var start = System.nanoTime()
    do {
        var idx = 0
        while (idx < units.size) {
            var u = units[idx++]
            if (u.isConfig) continue
            if (keyframeWanted.get() && !u.isKeyframe) { continue } // skip ahead to the next IDR
            keyframeWanted.set(false)
            val data = if (u.isKeyframe) sps.data + u.data else u.data
            val now = System.nanoTime()
            u = EncodedAccessUnit(frameId++, u.isKeyframe, false, now, now, data)
            for (p in fragmenter.fragment(u) { h, plain -> videoCipher.seal(h, plain) }) udp.send(DatagramPacket(p.bytes, p.bytes.size, target))
            sent++
            if (sent % (fps * 10) == 0L) println("sent $sent frames in %.0f s".format((System.nanoTime() - start) / 1e9))
            next += frameNs
            val sleep = next - System.nanoTime()
            if (sleep > 0) Thread.sleep(sleep / 1_000_000, (sleep % 1_000_000).toInt()) else next = System.nanoTime()
        }
    } while (loop)
    send(MessageType.STREAM_STOP, Payloads.StreamStop.serializer(), Payloads.StreamStop("end of file"))
    send(MessageType.GOODBYE, Payloads.Goodbye.serializer(), Payloads.Goodbye("done"))
    println("done: $sent frames")
}

/** Minimal Annex-B splitter: groups NAL units into access units on the first VCL NAL of each picture. */
object AnnexB {
    private fun startCodes(d: ByteArray): List<Int> {
        val out = ArrayList<Int>(); var i = 0
        while (i + 3 <= d.size) {
            if (d[i].toInt() == 0 && d[i + 1].toInt() == 0 && (d[i + 2].toInt() == 1 || (d[i + 2].toInt() == 0 && i + 3 < d.size && d[i + 3].toInt() == 1))) { out += i; i += if (d[i + 2].toInt() == 1) 3 else 4 } else i++
        }
        return out
    }

    private fun nalType(d: ByteArray, start: Int): Int { var p = start; while (d[p].toInt() == 0) p++; return d[p + 1].toInt() and 0x1F }
    private fun firstMbZero(d: ByteArray, start: Int): Boolean { var p = start; while (d[p].toInt() == 0) p++; return (d[p + 2].toInt() and 0x80) != 0 } // ue(v) first_mb_in_slice == 0 <=> leading bit 1

    fun accessUnits(d: ByteArray): List<EncodedAccessUnit> {
        val starts = startCodes(d)
        val nals = starts.mapIndexed { i, s -> s to (starts.getOrNull(i + 1) ?: d.size) }
        val units = ArrayList<EncodedAccessUnit>()
        var current = ArrayList<ByteArray>(); var currentKey = false; var currentConfig = false; var id = 0L
        fun flush() { if (current.isNotEmpty()) { units += EncodedAccessUnit(id++, currentKey, currentConfig, 0, 0, current.reduce { a, b -> a + b }); current = ArrayList(); currentKey = false; currentConfig = false } }
        for ((s, e) in nals) {
            val t = nalType(d, s)
            val bytes = d.copyOfRange(s, e)
            when (t) {
                7, 8 -> { if (!currentConfig) flush(); currentConfig = true; current += bytes }
                1, 5 -> { if (currentConfig || firstMbZero(d, s)) flush(); if (t == 5) currentKey = true; current += bytes }
                9, 6 -> { flush(); /* AUD / SEI start a new AU; attach to next */ current += bytes }
                else -> current += bytes
            }
        }
        flush()
        return units
    }

    fun splitSpsPps(config: ByteArray): Pair<ByteArray, ByteArray?> {
        val s = startCodes(config)
        return if (s.size >= 2) config.copyOfRange(s[0], s[1]) to config.copyOfRange(s[1], config.size) else config to null
    }
}
