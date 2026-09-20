package com.rokidmirror.receiver.transport

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.rokidmirror.protocol.Protocol
import com.rokidmirror.receiver.telemetry.ReceiverLog

/** Advertises the receiver over DNS-SD so the phone finds it without manual IP entry. */
class NsdAdvertiser(context: Context) {
    private companion object { const val TAG = "Nsd" }
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var listener: NsdManager.RegistrationListener? = null

    fun start(receiverId: String, name: String, controlPort: Int, displayWidth: Int, displayHeight: Int) {
        stop()
        val info = NsdServiceInfo().apply {
            serviceName = name.take(60)
            serviceType = Protocol.SERVICE_TYPE
            port = controlPort
            setAttribute("id", receiverId)
            setAttribute("name", name.take(60))
            setAttribute("w", displayWidth.toString())
            setAttribute("h", displayHeight.toString())
            setAttribute("v", Protocol.PROTOCOL_VERSION.toString())
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { ReceiverLog.e(TAG, "register_failed", null, "code" to errorCode) }
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) { ReceiverLog.i(TAG, "registered", "name" to serviceInfo.serviceName) }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
        }
        listener = l
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
    }

    fun stop() { listener?.let { runCatching { nsd.unregisterService(it) } }; listener = null }
}
