package com.rokidmirror.sender.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.rokidmirror.protocol.Protocol
import com.rokidmirror.sender.telemetry.MirrorLog
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * DNS-SD discovery through Android's NsdManager. The receiver advertises
 * `_rokidmirror._tcp` with TXT records `id`, `name`, `w`, `h`, `v`.
 * Resolution is serialized because NsdManager rejects concurrent resolves.
 */
class NsdReceiverDiscovery(private val context: Context) : ReceiverDiscovery {
    private companion object { const val TAG = "Discovery" }

    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    override fun discover(): Flow<List<DiscoveredReceiver>> = callbackFlow {
        val found = ConcurrentHashMap<String, DiscoveredReceiver>()
        val resolveQueue = ConcurrentLinkedQueue<NsdServiceInfo>()
        val resolving = AtomicBoolean(false)
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val multicastLock = wifi?.createMulticastLock("rokidmirror-discovery")?.apply { setReferenceCounted(false); runCatching { acquire() } }

        fun publish() { trySend(found.values.sortedBy { it.name }) }

        fun resolveNext() {
            if (!resolving.compareAndSet(false, true)) return
            val next = resolveQueue.poll()
            if (next == null) { resolving.set(false); return }
            @Suppress("DEPRECATION")
            nsd.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    MirrorLog.w(TAG, "resolve_failed", "service" to serviceInfo.serviceName, "code" to errorCode)
                    resolving.set(false); resolveNext()
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val host = serviceInfo.host?.hostAddress
                    if (host != null) {
                        val txt = serviceInfo.attributes.mapValues { (_, v) -> v?.toString(Charsets.UTF_8) ?: "" }
                        val receiver = DiscoveredReceiver(
                            id = txt["id"] ?: serviceInfo.serviceName,
                            name = txt["name"] ?: serviceInfo.serviceName,
                            host = host,
                            port = serviceInfo.port,
                            displayWidth = txt["w"]?.toIntOrNull() ?: 0,
                            displayHeight = txt["h"]?.toIntOrNull() ?: 0,
                        )
                        found[serviceInfo.serviceName] = receiver
                        MirrorLog.i(TAG, "receiver_resolved", "name" to receiver.name, "port" to receiver.port)
                        publish()
                    }
                    resolving.set(false); resolveNext()
                }
            })
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { MirrorLog.e(TAG, "discovery_start_failed", null, "code" to errorCode); close() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String) { MirrorLog.i(TAG, "discovery_started") }
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!serviceInfo.serviceType.startsWith(Protocol.SERVICE_TYPE.trimEnd('.'))) return
                resolveQueue += serviceInfo
                resolveNext()
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                found.remove(serviceInfo.serviceName)
                publish()
            }
        }
        publish()
        nsd.discoverServices(Protocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        awaitClose {
            runCatching { nsd.stopServiceDiscovery(listener) }
            runCatching { multicastLock?.release() }
        }
    }
}
