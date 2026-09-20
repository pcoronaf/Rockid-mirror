package com.rokidmirror.protocol.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** HKDF (RFC 5869) with HMAC-SHA256, implemented on JCA primitives available on API 21+. */
object Hkdf {
    private const val HMAC = "HmacSHA256"

    fun hmac(key: ByteArray, vararg data: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(if (key.isEmpty()) ByteArray(32) else key, HMAC))
        for (d in data) mac.update(d)
        return mac.doFinal()
    }

    fun extract(salt: ByteArray, ikm: ByteArray): ByteArray = hmac(salt, ikm)

    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length <= 255 * 32)
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            previous = hmac(prk, previous, info, byteArrayOf(counter.toByte()))
            val n = minOf(previous.size, length - pos)
            System.arraycopy(previous, 0, out, pos, n)
            pos += n
            counter++
        }
        return out
    }

    fun derive(ikm: ByteArray, salt: ByteArray, info: String, length: Int): ByteArray =
        expand(extract(salt, ikm), info.encodeToByteArray(), length)

    /** Constant-time comparison. */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var r = 0
        for (i in a.indices) r = r or (a[i].toInt() xor b[i].toInt())
        return r == 0
    }
}
