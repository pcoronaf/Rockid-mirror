package com.rokidmirror.sender.app

import android.content.Context
import android.os.Build
import com.rokidmirror.protocol.crypto.SenderIdentity
import com.rokidmirror.sender.app.session.MirrorSession
import com.rokidmirror.sender.control.ProfileRepository
import com.rokidmirror.sender.discovery.NsdReceiverDiscovery
import java.util.UUID

/**
 * Small composition root (spec: no DI framework in v0.1). Everything long-lived hangs off the
 * application so the foreground service and the activity share one [MirrorSession].
 */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val credentialStore = KeystoreCredentialStore(appContext)
    val profileRepository = ProfileRepository(appContext)
    val discovery = NsdReceiverDiscovery(appContext)
    val identity: SenderIdentity = loadIdentity()
    val session: MirrorSession by lazy { MirrorSession(appContext, this) }

    private fun loadIdentity(): SenderIdentity {
        val prefs = appContext.getSharedPreferences("identity", Context.MODE_PRIVATE)
        val id = prefs.getString("sender_id", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("sender_id", it).apply() }
        val name = "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifBlank { "Android phone" }
        return SenderIdentity(id, name, VERSION)
    }

    companion object { const val VERSION = "0.1.0" }
}
