package com.rokidmirror.tools.inspector

import com.rokidmirror.protocol.control.ControlCodec
import com.rokidmirror.protocol.video.VideoPacketHeader
import java.io.File

/**
 * Decodes captured protocol data for debugging:
 *   packet-inspector header <hex-or-file>     -> prints the 40-byte video packet header fields
 *   packet-inspector control <json-file>      -> validates a control message against the codec
 *   packet-inspector pcap-udp <file.bin>      -> parses a concatenation of raw datagrams written as [u16 len][bytes]
 * Payloads are encrypted on the wire; this tool never needs (or accepts) session keys.
 */
fun main(args: Array<String>) {
    if (args.size < 2) { println("usage: header <hex|file> | control <json-file> | pcap-udp <file>"); return }
    when (args[0]) {
        "header" -> println(Inspector.describeHeader(Inspector.bytesFrom(args[1])))
        "control" -> println(Inspector.describeControl(File(args[1]).readBytes()))
        "pcap-udp" -> Inspector.walkDatagrams(File(args[1]).readBytes()).forEach(::println)
        else -> println("unknown command ${args[0]}")
    }
}

object Inspector {
    fun bytesFrom(arg: String): ByteArray {
        val f = File(arg)
        if (f.exists()) return f.readBytes()
        val hex = arg.filter { !it.isWhitespace() }
        return ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    fun describeHeader(bytes: ByteArray): String {
        // Allow inspecting a bare header by faking the payload length check.
        val h = try { VideoPacketHeader.parse(bytes) } catch (e: Exception) {
            if (bytes.size >= VideoPacketHeader.SIZE) VideoPacketHeader.parse(bytes.copyOf(VideoPacketHeader.SIZE) + ByteArray(declaredPayloadLength(bytes))) else throw e
        }
        return buildString {
            appendLine("magic=0x${VideoPacketHeader.MAGIC.toString(16)} version=${h.protocolVersion} flags=0x${h.flags.toString(16)} (key=${h.isKeyframe} config=${h.isConfig} endOfAu=${h.isEndOfAccessUnit})")
            appendLine("session=${h.sessionShortId} frame=${h.frameId} seq=${h.packetSequence} fragment=${h.fragmentIndex + 1}/${h.fragmentCount} payload=${h.payloadLength}")
            append("captureNs=${h.captureTimestampNs} encodeNs=${h.encodeTimestampNs} encodeLatencyMs=${(h.encodeTimestampNs - h.captureTimestampNs) / 1e6}")
        }
    }

    private fun declaredPayloadLength(b: ByteArray): Int = ((b[22].toInt() and 0xFF) shl 8) or (b[23].toInt() and 0xFF)

    fun describeControl(bytes: ByteArray): String {
        val m = ControlCodec.decode(bytes)
        return "type=${m.type} session=${m.sessionId} seq=${m.sequence} tNs=${m.timestampNs} payloadKeys=${m.payload.keys}"
    }

    fun walkDatagrams(data: ByteArray): List<String> {
        val out = ArrayList<String>(); var p = 0
        while (p + 2 <= data.size) {
            val len = ((data[p].toInt() and 0xFF) shl 8) or (data[p + 1].toInt() and 0xFF); p += 2
            if (p + len > data.size) break
            out += runCatching { describeHeader(data.copyOfRange(p, p + len)).lines().first() }.getOrElse { "bad packet: ${it.message}" }
            p += len
        }
        return out
    }
}
