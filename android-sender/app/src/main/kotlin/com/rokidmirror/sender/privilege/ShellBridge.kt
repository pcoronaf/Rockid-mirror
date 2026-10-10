package com.rokidmirror.sender.privilege

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import com.rokidmirror.sender.telemetry.MirrorLog

/**
 * App-side holder of the live helper Binder, and the only path through which the app reaches a
 * privileged framework service.
 *
 * [serviceBinder] returns a proxy whose every transaction is wrapped and forwarded to the helper,
 * which replays it against the real service as the shell user. [service] wraps that in the usual
 * `IFoo.Stub.asInterface(...)` so callers use ordinary AIDL interfaces. Adapted from Dextop's
 * `DistributionPrivilegeRuntime` (GPL-3.0).
 */
object ShellBridge {
    private const val TAG = "ShellBridge"

    @Volatile private var runtime: IBinder? = null

    /** Invoked when the helper process dies, so the UI can fall back to the pairing prompt. */
    @Volatile var onDied: (() -> Unit)? = null

    val available: Boolean get() = runtime?.isBinderAlive == true

    /** Called by [ShellPrivilegeProvider] once the helper has published its Binder. */
    fun accept(binder: IBinder) {
        runtime = binder
        runCatching {
            binder.linkToDeath({
                if (runtime === binder) {
                    runtime = null
                    MirrorLog.w(TAG, "helper_died")
                    onDied?.invoke()
                }
            }, 0)
        }
        MirrorLog.i(TAG, "helper_accepted")
    }

    fun shutdown() {
        runtime = null
    }

    /** A Binder that forwards any transaction to the named framework service via the helper. */
    fun serviceBinder(name: String): IBinder? {
        val remote = runtime?.takeIf { it.isBinderAlive } ?: return null
        return object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                val request = Parcel.obtain()
                return try {
                    request.writeInterfaceToken(ShellProtocol.DESCRIPTOR)
                    request.writeString(name)
                    request.writeInt(code)
                    request.writeInt(flags)
                    request.appendFrom(data, 0, data.dataSize())
                    remote.transact(ShellProtocol.TRANSACT_SERVICE, request, reply, flags)
                } finally {
                    request.recycle()
                }
            }
        }
    }

    /** `IFoo.Stub.asInterface` over the forwarded service Binder. */
    fun service(name: String, interfaceName: String): Any {
        val binder = serviceBinder(name)
            ?: throw IllegalStateException("the computer-mode helper is not connected")
        return Class.forName("$interfaceName\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)
            ?: throw IllegalStateException("$interfaceName asInterface returned null")
    }

    data class CommandResult(val exitCode: Int, val output: String, val error: String) {
        val succeeded: Boolean get() = exitCode == 0
    }

    /** Runs a command line as the shell user through the helper. */
    fun execute(vararg arguments: String): CommandResult {
        val remote = runtime?.takeIf { it.isBinderAlive }
            ?: return CommandResult(-1, "", "the computer-mode helper is not connected")
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(ShellProtocol.DESCRIPTOR)
            data.writeStringArray(arrayOf(*arguments))
            if (!remote.transact(ShellProtocol.EXECUTE_COMMAND, data, reply, 0)) {
                return CommandResult(-1, "", "the helper rejected the command")
            }
            reply.readException()
            CommandResult(reply.readInt(), reply.readString().orEmpty(), reply.readString().orEmpty())
        } catch (e: Exception) {
            CommandResult(-1, "", e.message ?: e.javaClass.simpleName)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}
