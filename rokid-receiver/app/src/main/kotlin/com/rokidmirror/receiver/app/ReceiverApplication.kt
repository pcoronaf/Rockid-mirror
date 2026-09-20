package com.rokidmirror.receiver.app

import android.app.Application
import android.content.Context
import android.os.Build
import com.rokidmirror.protocol.Protocol
import com.rokidmirror.protocol.crypto.ReceiverIdentity
import com.rokidmirror.receiver.telemetry.ReceiverLog
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.UUID

class ReceiverApplication : Application() {
    lateinit var credentials: KeystoreReceiverCredentialStore; private set
    lateinit var identity: ReceiverIdentity; private set

    override fun onCreate() {
        super.onCreate()
        installCrashRecorder()
        credentials = KeystoreReceiverCredentialStore(this)
        val prefs = getSharedPreferences("identity", Context.MODE_PRIVATE)
        val id = prefs.getString("receiver_id", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("receiver_id", it).apply() }
        val name = "Rokid Glasses ${id.takeLast(4)}".let { if (Build.MODEL.isNotBlank()) "${Build.MODEL} ${id.takeLast(4)}" else it }
        identity = ReceiverIdentity(id, name, VERSION, Protocol.DEFAULT_VIDEO_PORT)
    }

    /** The glasses have no visible logcat; keep the last stack trace so the next launch can show it. */
    private fun installCrashRecorder() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                crashFile().writeText("thread=${thread.name}\n$sw")
            }
            ReceiverLog.e("Crash", "uncaught", error, "thread" to thread.name)
            previous?.uncaughtException(thread, error)
        }
    }

    fun crashFile(): File = File(filesDir, "last-crash.txt")

    /** First line of the previous crash, or null. Cleared once shown. */
    fun consumeLastCrash(): String? {
        val f = crashFile()
        if (!f.exists()) return null
        val text = runCatching { f.readText() }.getOrNull()
        runCatching { f.delete() }
        return text?.lineSequence()?.filter { it.isNotBlank() }?.take(3)?.joinToString(" | ")?.take(300)
    }

    companion object { const val VERSION = "0.1.0" }
}
