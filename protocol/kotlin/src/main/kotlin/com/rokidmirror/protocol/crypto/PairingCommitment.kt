package com.rokidmirror.protocol.crypto

import java.security.SecureRandom

/**
 * Bitwise commit/reveal pairing (the construction behind Bluetooth "Passkey Entry").
 *
 * The 6-digit pairing code shown on the glasses is treated as a 20-bit number. For each bit,
 * both peers first commit to `HMAC(nonce_i, role || transcript || i || bit_i)` and only then
 * reveal `nonce_i`. An on-path attacker who does not know the code must guess each bit before
 * the honest peer reveals it, so a full impersonation succeeds with probability 2^-20 and any
 * wrong guess aborts pairing and burns the code. A single whole-code commitment would instead
 * let the attacker brute-force the code offline after one reveal.
 *
 * Commitments are bound to [SessionKeys.transcriptHash], so they cannot be replayed into a
 * different key exchange.
 */
object PairingCommitment {
    const val CODE_DIGITS = 6
    const val CODE_BITS = 20 // 2^20 > 10^6
    private const val NONCE_BYTES = 16

    const val ROLE_SENDER: Byte = 'A'.code.toByte()
    const val ROLE_RECEIVER: Byte = 'B'.code.toByte()

    fun generateCode(random: SecureRandom = SecureRandom()): String =
        "%06d".format(random.nextInt(1_000_000))

    fun formatForDisplay(code: String): String = code.substring(0, 3) + " " + code.substring(3)

    fun normalizeInput(input: String): String? {
        val digits = input.filter { it.isDigit() }
        return if (digits.length == CODE_DIGITS) digits else null
    }

    fun bit(code: String, index: Int): Int = (code.toInt() ushr index) and 1

    fun newNonce(random: SecureRandom = SecureRandom()): ByteArray = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }

    fun commit(nonce: ByteArray, role: Byte, transcriptHash: ByteArray, round: Int, bit: Int): ByteArray =
        Hkdf.hmac(nonce, byteArrayOf(role), transcriptHash, byteArrayOf(round.toByte()), byteArrayOf(bit.toByte()))

    fun verify(commitment: ByteArray, nonce: ByteArray, role: Byte, transcriptHash: ByteArray, round: Int, bit: Int): Boolean =
        nonce.size == NONCE_BYTES && Hkdf.constantTimeEquals(commitment, commit(nonce, role, transcriptHash, round, bit))
}

/** Long-term credential issued by a receiver to a sender after a successful pairing. */
class PairingCredential(val receiverId: String, val credentialId: String, val secret: ByteArray) {
    companion object {
        fun issue(receiverId: String, random: SecureRandom = SecureRandom()): PairingCredential {
            val id = ByteArray(8).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
            val secret = ByteArray(32).also { random.nextBytes(it) }
            return PairingCredential(receiverId, id, secret)
        }

        fun proof(secret: ByteArray, role: Byte, transcriptHash: ByteArray): ByteArray =
            Hkdf.hmac(secret, "rokid-mirror-credential-proof-v1".encodeToByteArray(), byteArrayOf(role), transcriptHash)
    }
}
