package com.rokidmirror.protocol.crypto

import com.rokidmirror.protocol.control.ErrorCode
import com.rokidmirror.protocol.control.MirrorException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement

/**
 * Ephemeral ECDH on P-256 using only JCA symbols present on Android 5+ and every JDK.
 * (X25519 via "XDH" only reached Android in API 33, which the glasses do not have.)
 */
object KeyExchange {
    fun generate(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    fun encodePublic(pair: KeyPair): ByteArray = pair.public.encoded

    fun decodePublic(encoded: ByteArray): PublicKey = try {
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded))
    } catch (e: Exception) {
        throw MirrorException(ErrorCode.AUTHENTICATION_FAILED, "invalid peer public key", e)
    }

    fun sharedSecret(own: KeyPair, peerEncoded: ByteArray): ByteArray = try {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(own.private)
        ka.doPhase(decodePublic(peerEncoded), true)
        ka.generateSecret()
    } catch (e: MirrorException) {
        throw e
    } catch (e: Exception) {
        throw MirrorException(ErrorCode.AUTHENTICATION_FAILED, "ECDH failed", e)
    }

    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        for (p in parts) md.update(p)
        return md.digest()
    }
}

/** Per-session symmetric keys derived from the handshake transcript. */
class SessionKeys(
    val controlToReceiver: ByteArray,
    val controlToSender: ByteArray,
    val video: ByteArray,
    /** Binds pairing commitments and credential proofs to this exact key exchange. */
    val transcriptHash: ByteArray,
) {
    companion object {
        private const val KEY_LEN = 32

        fun derive(sharedSecret: ByteArray, transcriptHash: ByteArray): SessionKeys {
            val prk = Hkdf.extract(transcriptHash, sharedSecret)
            return SessionKeys(
                controlToReceiver = Hkdf.expand(prk, "rokid-mirror v1 control s2r".encodeToByteArray(), KEY_LEN),
                controlToSender = Hkdf.expand(prk, "rokid-mirror v1 control r2s".encodeToByteArray(), KEY_LEN),
                video = Hkdf.expand(prk, "rokid-mirror v1 video s2r".encodeToByteArray(), KEY_LEN),
                transcriptHash = transcriptHash,
            )
        }

        /** Transcript = domain separator + length-prefixed HELLO and HELLO_ACK bytes as sent on the wire. */
        fun transcript(helloBytes: ByteArray, helloAckBytes: ByteArray): ByteArray =
            KeyExchange.sha256(
                "rokid-mirror-transcript-v1".encodeToByteArray(),
                lengthPrefix(helloBytes.size), helloBytes,
                lengthPrefix(helloAckBytes.size), helloAckBytes,
            )

        private fun lengthPrefix(n: Int) = byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte())
    }
}
