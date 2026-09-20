package com.rokidmirror.protocol.transport

import com.rokidmirror.protocol.Protocol
import com.rokidmirror.protocol.control.ErrorCode
import com.rokidmirror.protocol.control.MirrorException
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/** Length-prefixed (32-bit big-endian) frames on the TCP control channel. */
object Framing {
    fun write(out: OutputStream, frame: ByteArray) {
        require(frame.size <= Protocol.MAX_CONTROL_FRAME_BYTES)
        val n = frame.size
        out.write(byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()))
        out.write(frame)
        out.flush()
    }

    /** @return the next frame, or null at a clean end of stream. */
    fun read(input: InputStream): ByteArray? {
        val din = if (input is DataInputStream) input else DataInputStream(input)
        val n = try { din.readInt() } catch (_: EOFException) { return null }
        if (n < 0 || n > Protocol.MAX_CONTROL_FRAME_BYTES) throw MirrorException(ErrorCode.UNKNOWN, "control frame too large: $n")
        val buf = ByteArray(n)
        try { din.readFully(buf) } catch (_: EOFException) { return null }
        return buf
    }
}
