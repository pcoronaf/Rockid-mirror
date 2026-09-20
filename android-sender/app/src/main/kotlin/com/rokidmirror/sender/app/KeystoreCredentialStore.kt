package com.rokidmirror.sender.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.rokidmirror.protocol.crypto.PairingCredential
import com.rokidmirror.sender.telemetry.MirrorLog
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores receiver credentials encrypted with a non-exportable AES key in the Android Keystore
 * (spec: platform-appropriate secure storage). Backup/transfer is excluded in the manifest.
 */
class KeystoreCredentialStore(context: Context) {
    private companion object { const val TAG = "CredentialStore"; const val ALIAS = "rokidmirror-credentials"; const val PREFS = "credentials" }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(receiverId: String): PairingCredential? {
        val blob = prefs.getString(receiverId, null) ?: return null
        return try {
            val (ivB64, ctB64) = blob.split(":", limit = 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(ivB64, Base64.NO_WRAP)))
            val plain = cipher.doFinal(Base64.decode(ctB64, Base64.NO_WRAP)).decodeToString()
            val (credentialId, secretB64) = plain.split(":", limit = 2)
            PairingCredential(receiverId, credentialId, Base64.decode(secretB64, Base64.NO_WRAP))
        } catch (e: Exception) {
            MirrorLog.w(TAG, "credential_unreadable", "receiver" to receiverId)
            prefs.edit().remove(receiverId).apply()
            null
        }
    }

    fun save(credential: PairingCredential) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val plain = credential.credentialId + ":" + Base64.encodeToString(credential.secret, Base64.NO_WRAP)
        val ct = cipher.doFinal(plain.encodeToByteArray())
        prefs.edit().putString(credential.receiverId, Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)).apply()
        MirrorLog.i(TAG, "credential_saved", "receiver" to credential.receiverId)
    }

    fun forget(receiverId: String) { prefs.edit().remove(receiverId).apply() }
    fun knownReceiverIds(): Set<String> = prefs.all.keys

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }
}
