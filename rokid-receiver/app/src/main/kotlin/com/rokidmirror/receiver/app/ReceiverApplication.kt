package com.rokidmirror.receiver.app

import android.app.Application
import android.content.Context
import android.os.Build
import com.rokidmirror.protocol.Protocol
import com.rokidmirror.protocol.crypto.ReceiverIdentity
import java.util.UUID

class ReceiverApplication : Application() {
    lateinit var credentials: KeystoreReceiverCredentialStore; private set
    lateinit var identity: ReceiverIdentity; private set

    override fun onCreate() {
        super.onCreate()
        credentials = KeystoreReceiverCredentialStore(this)
        val prefs = getSharedPreferences("identity", Context.MODE_PRIVATE)
        val id = prefs.getString("receiver_id", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("receiver_id", it).apply() }
        val name = "Rokid Glasses ${id.takeLast(4)}".let { if (Build.MODEL.isNotBlank()) "${Build.MODEL} ${id.takeLast(4)}" else it }
        identity = ReceiverIdentity(id, name, VERSION, Protocol.DEFAULT_VIDEO_PORT)
    }

    companion object { const val VERSION = "0.1.0" }
}
