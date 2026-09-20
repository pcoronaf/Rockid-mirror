package com.rokidmirror.sender.discovery

import kotlinx.coroutines.flow.Flow

/** A receiver found on the local network (mDNS/DNS-SD) or entered manually. */
data class DiscoveredReceiver(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val displayWidth: Int = 0,
    val displayHeight: Int = 0,
    val manual: Boolean = false,
)

/** Discovery boundary: implementations emit the current list whenever it changes. */
interface ReceiverDiscovery {
    fun discover(): Flow<List<DiscoveredReceiver>>
}
