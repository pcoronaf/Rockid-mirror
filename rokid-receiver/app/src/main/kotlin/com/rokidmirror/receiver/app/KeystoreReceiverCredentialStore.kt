package com.rokidmirror.receiver.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.rokidmirror.protocol.crypto.PairingCredential
import com.rokidmirror.receiver.telemetry.ReceiverLog
import com.rokidmirror.receiver.transport.ReceiverCredentialStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Sender credentials issued by this receiver, encrypted with an Android Keystore AES key. */
class KeystoreReceiverCredentialStore(context: Context) : ReceiverCredentialStore {
    private companion object { const val TAG = "Credentials"; const val ALIAS = "rokidmirror-receiver-credentials" }
    private val prefs = context.getSharedPreferences("sender_credentials", Context.MODE_PRIVATE)

    override fun lookup(senderId: String, credentialId: String): ByteArray? {
        val blob = prefs.getString("$senderId|$credentialId", null) ?: return null
        return try {
            val (iv, ct) = blob.split(":", limit = 2)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            c.doFinal(Base64.decode(ct, Base64.NO_WRAP))
        } catch (e: Exception) {
            ReceiverLog.w(TAG, "unreadable_credential"); null
        }
    }

    override fun store(senderId: String, credential: PairingCredential) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(credential.secret)
        prefs.edit().putString("$senderId|${credential.credentialId}", Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)).apply()
        ReceiverLog.i(TAG, "credential_stored", "sender" to senderId)
    }

    override fun forgetAll() { prefs.edit().clear().apply(); ReceiverLog.i(TAG, "all_forgotten") }
    override fun count(): Int = prefs.all.size

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return gen.generateKey()
    }
}
