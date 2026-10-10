package com.rokidmirror.sender.privilege

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.cert.OkioFilePrivateKeyStore
import com.rokidmirror.sender.telemetry.MirrorLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okio.Path.Companion.toPath
import java.io.File
import kotlin.coroutines.resume

/** Pure command builder, split out so it can be unit-tested without a device. */
object WirelessDebugCommands {
    const val CONNECT_SERVICE = "_adb-tls-connect._tcp"
    const val PAIR_SERVICE = "_adb-tls-pairing._tcp"
    const val LOOPBACK = "127.0.0.1"

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /**
     * The one-liner the helper is launched with. `app_process` runs it as the shell user; the
     * class path is the app's own APK, so no artifact is pushed to the device, and the process is
     * detached so it outlives the ADB shell that started it.
     */
    fun startHelperCommand(apkPath: String, packageName: String): String =
        "CLASSPATH=${shellQuote(apkPath)} " +
            "nohup /system/bin/setsid /system/bin/app_process /system/bin " +
            "${ShellProtocol.HELPER_MAIN_CLASS} ${shellQuote(packageName)} " +
            ">/data/local/tmp/rokidmirror-helper.log 2>&1 </dev/null &"
}

/**
 * Pairs with the phone's own Wireless debugging endpoint and launches the computer-mode helper.
 *
 * Pairing material (the ADB private key) is kept in the app's private storage, which is excluded
 * from backup and transfer, so the pairing survives app restarts without a secret ever leaving the
 * device or reaching a log. Modelled on Dextop's `DistributionPrivilegeBootstrap` (GPL-3.0).
 */
class WirelessDebug(context: Context) {
    private val appContext = context.applicationContext
    private val nsd = runCatching { appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager }.getOrNull()

    init {
        runCatching {
            val keyPath = File(appContext.filesDir, "adb_identity.pem").path.toPath()
            KadbCert.configure(OkioFilePrivateKeyStore(keyPath))
        }.onFailure { MirrorLog.w(TAG, "cert_store_init_failed", "error" to it.message) }
    }

    val connected: Boolean get() = ShellBridge.available

    /** Pairs using the 6-digit code and the pairing port from the "Pair device with code" dialog. */
    suspend fun pair(code: String, port: Int, host: String = WirelessDebugCommands.LOOPBACK): Result<Unit> =
        runCatching {
            Kadb.pair(host, port, code)
            MirrorLog.i(TAG, "paired", "port" to port)
        }

    /**
     * Connects to the Wireless debugging port and brings up the helper. The port may be given
     * (it is shown on the Wireless debugging screen) or discovered over mDNS.
     */
    suspend fun connectAndLaunchHelper(port: Int?, host: String = WirelessDebugCommands.LOOPBACK): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (ShellBridge.available) return@runCatching
                val resolved = port ?: discoverPort(WirelessDebugCommands.CONNECT_SERVICE)
                    ?: throw IllegalStateException("could not find the Wireless debugging port; enter it from the Wireless debugging screen")
                val kadb = Kadb.tryConnection(host, resolved)
                    ?: throw IllegalStateException("the phone refused the ADB connection on port $resolved; pair again if needed")
                kadb.use {
                    val command = WirelessDebugCommands.startHelperCommand(appContext.applicationInfo.sourceDir, appContext.packageName)
                    val response = it.shell(command)
                    if (response.exitCode != 0) {
                        throw IllegalStateException("the helper did not start (exit ${response.exitCode})")
                    }
                    // app_process is detached; the Binder can take a few seconds to publish.
                    repeat(100) {
                        if (ShellBridge.available) return@runCatching
                        delay(100)
                    }
                    throw IllegalStateException("the helper started but did not connect back")
                }
            }
        }

    /** Best-effort mDNS lookup of an ADB service port; returns null rather than failing. */
    suspend fun discoverPort(serviceType: String): Int? {
        val manager = nsd ?: return null
        return withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val listener = object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(type: String) {}
                    override fun onDiscoveryStopped(type: String) {}
                    override fun onStartDiscoveryFailed(type: String, errorCode: Int) { if (cont.isActive) cont.resume(null) }
                    override fun onStopDiscoveryFailed(type: String, errorCode: Int) {}
                    override fun onServiceLost(info: NsdServiceInfo) {}
                    override fun onServiceFound(info: NsdServiceInfo) {
                        manager.resolveService(info, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
                            override fun onServiceResolved(resolved: NsdServiceInfo) {
                                if (cont.isActive) cont.resume(resolved.port)
                            }
                        })
                    }
                }
                runCatching { manager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener) }
                    .onFailure { if (cont.isActive) cont.resume(null) }
                cont.invokeOnCancellation { runCatching { manager.stopServiceDiscovery(listener) } }
            }
        }
    }

    private companion object {
        const val TAG = "WirelessDebug"
        const val DISCOVERY_TIMEOUT_MS = 6_000L
    }
}
