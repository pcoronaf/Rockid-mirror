package com.rokidmirror.sender.privilege

import android.content.AttributionSource
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.Process
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * The shell-level helper, launched through `app_process` so it runs as the `shell` user.
 *
 * It does exactly two jobs: forward a system-service transaction (so the app can create a trusted
 * display and inject input) and run a command line (used to launch an app onto the glasses
 * display). It publishes its Binder to the app's [ShellPrivilegeProvider] and then idles. It holds
 * no app state, no screen content, and nothing is logged about what flows through it.
 *
 * The publish handshake and transaction forwarding are adapted from Dextop's
 * `EmbeddedPrivilegeServer` (GPL-3.0, github.com/NarYuki/Dextop).
 */
object ShellHelperMain {
    @JvmStatic
    fun main(arguments: Array<String>) {
        val packageName = arguments.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: error("missing package name")
        Looper.prepareMainLooper()
        runCatching {
            HiddenApiBypass.addHiddenApiExemptions("Landroid/", "Lcom/android/")
        }
        val server = RuntimeBinder()
        val handler = Handler(Looper.getMainLooper())
        lateinit var publishAndWatch: () -> Unit
        publishAndWatch = {
            val response = runCatching {
                publishBinder(ShellProtocol.providerAuthority(packageName), server)
            }.getOrNull()
            val lifecycle = response
                ?.takeIf { it.getBoolean("accepted") }
                ?.getBinder(ShellProtocol.PROVIDER_LIFECYCLE_KEY)
            if (lifecycle == null) {
                // The app process may be absent while the shell process is healthy; keep retrying.
                handler.postDelayed(publishAndWatch, 1_500L)
            } else {
                runCatching {
                    lifecycle.linkToDeath({ handler.postDelayed(publishAndWatch, 500L) }, 0)
                }.onFailure { handler.postDelayed(publishAndWatch, 500L) }
            }
        }
        publishAndWatch()
        Looper.loop()
    }

    private fun systemService(name: String): IBinder? =
        Class.forName("android.os.ServiceManager")
            .getDeclaredMethod("getService", String::class.java)
            .invoke(null, name) as? IBinder

    private fun publishBinder(authority: String, binder: Binder): Bundle? {
        val activityManager = Class.forName("android.app.ActivityManager")
            .getDeclaredMethod("getService")
            .invoke(null)
        val acquire = activityManager.javaClass.interfaces
            .flatMap { it.methods.asIterable() }
            .first { it.name == "getContentProviderExternal" }
        val acquired = acquire.invoke(activityManager, authority, 0, null, authority)
            ?: error("content provider unavailable: $authority")
        val provider = runCatching {
            acquired.javaClass.getDeclaredField("provider").apply { isAccessible = true }.get(acquired)
        }.getOrElse { acquired }
        val extras = Bundle().apply { putBinder(ShellProtocol.PROVIDER_BINDER_KEY, binder) }
        val call = Class.forName("android.content.IContentProvider").methods
            .filter { it.name == "call" }
            .maxByOrNull { it.parameterTypes.size } ?: error("IContentProvider.call not found")
        var stringIndex = 0
        val callArguments = call.parameterTypes.map { type ->
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && type == AttributionSource::class.java ->
                    AttributionSource.Builder(Process.myUid()).setPackageName("com.android.shell").build()
                type == String::class.java -> when (stringIndex++) {
                    0 -> authority
                    1 -> ShellProtocol.PROVIDER_METHOD
                    else -> null
                }
                type == Bundle::class.java -> extras
                type == Int::class.javaPrimitiveType -> 0
                else -> null
            }
        }.toTypedArray()
        return call.invoke(provider, *callArguments) as? Bundle
    }

    private fun runCommand(command: Array<String>): CommandResult {
        if (command.isEmpty()) return CommandResult(-1, "", "empty command")
        return try {
            val process = ProcessBuilder(*command).redirectErrorStream(false).start()
            val out = process.inputStream.bufferedReader().use { it.readText() }
            val err = process.errorStream.bufferedReader().use { it.readText() }
            val exit = process.waitFor()
            CommandResult(exit, out.trim(), err.trim())
        } catch (e: Exception) {
            CommandResult(-1, "", e.message ?: e.javaClass.simpleName)
        }
    }

    private data class CommandResult(val exitCode: Int, val output: String, val error: String)

    private class RuntimeBinder : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            data.enforceInterface(ShellProtocol.DESCRIPTOR)
            return when (code) {
                ShellProtocol.TRANSACT_SERVICE -> {
                    val serviceName = data.readString() ?: error("missing service name")
                    val remoteCode = data.readInt()
                    val remoteFlags = data.readInt()
                    val target = systemService(serviceName) ?: error("service unavailable: $serviceName")
                    val nested = Parcel.obtain()
                    try {
                        nested.appendFrom(data, data.dataPosition(), data.dataAvail())
                        nested.setDataPosition(0)
                        target.transact(remoteCode, nested, reply, remoteFlags)
                    } finally {
                        nested.recycle()
                    }
                }
                ShellProtocol.EXECUTE_COMMAND -> {
                    val result = runCommand(data.createStringArray() ?: emptyArray())
                    reply?.writeNoException()
                    reply?.writeInt(result.exitCode)
                    reply?.writeString(result.output)
                    reply?.writeString(result.error)
                    true
                }
                else -> super.onTransact(code, data, reply, flags)
            }
        }
    }
}
