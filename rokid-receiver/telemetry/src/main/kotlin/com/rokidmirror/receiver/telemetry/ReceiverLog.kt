package com.rokidmirror.receiver.telemetry

import android.util.Log

/** Structured logging facade for the glasses app. Never logs payload bytes or pairing secrets. */
object ReceiverLog {
    @Volatile var minLevel: Int = Log.DEBUG

    fun d(tag: String, event: String, vararg fields: Pair<String, Any?>) = log(Log.DEBUG, tag, event, fields)
    fun i(tag: String, event: String, vararg fields: Pair<String, Any?>) = log(Log.INFO, tag, event, fields)
    fun w(tag: String, event: String, vararg fields: Pair<String, Any?>) = log(Log.WARN, tag, event, fields)
    fun e(tag: String, event: String, error: Throwable? = null, vararg fields: Pair<String, Any?>) {
        val extra = if (error != null) fields.toList() + ("error" to "${error.javaClass.simpleName}: ${error.message}") else fields.toList()
        log(Log.ERROR, tag, event, extra.toTypedArray())
    }

    private fun log(level: Int, tag: String, event: String, fields: Array<out Pair<String, Any?>>) {
        if (level < minLevel) return
        val line = buildString { append(event); for ((k, v) in fields) append(' ').append(k).append('=').append(v) }
        try { Log.println(level, "RokidRecv/$tag", line) } catch (_: Throwable) { }
    }
}
