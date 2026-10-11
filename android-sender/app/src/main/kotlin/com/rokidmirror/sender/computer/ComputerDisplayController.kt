package com.rokidmirror.sender.computer

import android.view.Surface
import com.rokidmirror.sender.privilege.ShellBridge
import com.rokidmirror.sender.telemetry.MirrorLog
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Creates the "computer screen": a trusted virtual display whose frames are written straight into
 * the mirror encoder's input surface, so the whole existing encode → encrypt → send pipeline
 * carries it to the glasses unchanged, and the phone's own screen is never captured.
 *
 * A trusted display (unlike the one [com.rokidmirror.sender.capture.ExtendedDisplayController]
 * makes) lets other apps be launched onto it and input be injected there. Only the shell user may
 * create one, so the call is forwarded through [ShellBridge]. The display-creation reflection and
 * the degrading flag ladder are adapted from Dextop's `DisplayMirrorBackend` (GPL-3.0).
 */
class ComputerDisplayController {
    private companion object {
        const val TAG = "ComputerDisplay"
        const val MANAGER_INTERFACE = "android.hardware.display.IDisplayManager"
        const val CONFIG = "android.hardware.display.VirtualDisplayConfig"
        const val CONFIG_BUILDER = "android.hardware.display.VirtualDisplayConfig\$Builder"
        const val CALLBACK = "android.hardware.display.IVirtualDisplayCallback"

        const val FLAG_PUBLIC = 1 shl 0
        const val FLAG_PRESENTATION = 1 shl 1
        const val FLAG_OWN_CONTENT_ONLY = 1 shl 3
        const val FLAG_SUPPORTS_TOUCH = 1 shl 6
        const val FLAG_SHOW_SYSTEM_DECORATIONS = 1 shl 9
        const val FLAG_TRUSTED = 1 shl 10
    }

    /** A live computer display; [displayId] is where apps launch and input is injected. */
    inner class Handle internal constructor(
        val displayId: Int,
        /** Which rung of the flag ladder the system accepted; touch injection depends on it. */
        val flags: String,
        private val service: Any,
        private val callback: Any,
        private val releaseMethod: Method,
        private val resizeMethod: Method?,
        private val setSurfaceMethod: Method?,
    ) {
        fun resize(width: Int, height: Int, dpi: Int) {
            val m = resizeMethod ?: return
            runCatching { m.invoke(service, callback, width, height, dpi) }
                .onFailure { MirrorLog.w(TAG, "resize_failed", "error" to it.message) }
        }

        fun setSurface(surface: Surface?) {
            val m = setSurfaceMethod ?: return
            runCatching { m.invoke(service, callback, surface) }
                .onFailure { MirrorLog.w(TAG, "set_surface_failed", "error" to it.message) }
        }

        fun release() {
            runCatching { releaseMethod.invoke(service, callback) }
                .onFailure { MirrorLog.w(TAG, "release_failed", "error" to it.message) }
        }
    }

    private var handle: Handle? = null
    val activeDisplayId: Int? get() = handle?.displayId

    /** Whether the privileged helper is connected and a computer display can be created. */
    fun isAvailable(): Boolean = ShellBridge.available

    /**
     * Creates the display, trying the fully capable configuration first and dropping optional
     * capabilities if the platform rejects a flag. Throws with the platform's own message on a
     * refusal, so the failure is visible rather than silent.
     */
    fun create(surface: Surface, width: Int, height: Int, dpi: Int): Handle {
        check(surface.isValid) { "the encoder surface is not ready" }
        check(ShellBridge.available) { "the computer-mode helper is not connected" }
        stop()

        val service = ShellBridge.service("display", MANAGER_INTERFACE)
        val configClass = Class.forName(CONFIG)
        val builderClass = Class.forName(CONFIG_BUILDER)
        val callbackClass = Class.forName(CALLBACK)

        // Look the methods up on the IDisplayManager interface (not the proxy instance's class):
        // there can be several createVirtualDisplay overloads, so pick the richest one that takes
        // a VirtualDisplayConfig and a callback, preferring an int return. Methods from the
        // interface still invoke on the asInterface proxy, which implements it.
        val mgrClass = Class.forName(MANAGER_INTERFACE)
        fun named(name: String) = mgrClass.methods.filter { it.name == name }
        val createCandidates = named("createVirtualDisplay").filter { m ->
            m.parameterTypes.any { configClass.isAssignableFrom(it) } &&
                m.parameterTypes.any { callbackClass.isAssignableFrom(it) }
        }
        val createMethod = (createCandidates.filter { it.returnType == Int::class.javaPrimitiveType }
            .ifEmpty { createCandidates })
            .maxByOrNull { it.parameterTypes.size }
            ?: run {
                val seen = named("createVirtualDisplay").joinToString("; ") { m ->
                    "${m.returnType.simpleName}(${m.parameterTypes.joinToString(",") { it.simpleName }})"
                }
                error("no createVirtualDisplay(config,callback) on $MANAGER_INTERFACE; saw: ${seen.ifEmpty { "none" }}")
            }
        val releaseMethod = named("releaseVirtualDisplay").firstOrNull { m ->
            m.parameterTypes.size == 1 && m.parameterTypes[0].isAssignableFrom(callbackClass)
        } ?: error("no releaseVirtualDisplay(callback) on $MANAGER_INTERFACE")
        val resizeMethod = named("resizeVirtualDisplay").firstOrNull { m ->
            m.parameterTypes.firstOrNull()?.isAssignableFrom(callbackClass) == true
        }
        val setSurfaceMethod = named("setVirtualDisplaySurface").firstOrNull { m ->
            m.parameterTypes.firstOrNull()?.isAssignableFrom(callbackClass) == true
        }

        val builderCtor = builderClass.constructors.single { c ->
            c.parameterTypes.contentEquals(
                arrayOf(String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType),
            )
        }
        // Trusted rungs first (what computer mode needs), then one untrusted probe used only to
        // tell "the device blocks trusted displays for shell" apart from "it blocks all virtual
        // displays". The probe is released immediately and never returned.
        val ladder = listOf(
            "trusted+touch+decor" to (FLAG_PUBLIC or FLAG_PRESENTATION or FLAG_OWN_CONTENT_ONLY or FLAG_SUPPORTS_TOUCH or FLAG_SHOW_SYSTEM_DECORATIONS or FLAG_TRUSTED),
            "trusted+decor" to (FLAG_PUBLIC or FLAG_PRESENTATION or FLAG_OWN_CONTENT_ONLY or FLAG_SHOW_SYSTEM_DECORATIONS or FLAG_TRUSTED),
            "trusted" to (FLAG_PUBLIC or FLAG_PRESENTATION or FLAG_OWN_CONTENT_ONLY or FLAG_TRUSTED),
        )

        fun attempt(flags: Int): Pair<Int, Any> {
            val callback = newCallback(callbackClass)
            val builder = builderCtor.newInstance("RokidMirrorComputer", width, height, dpi)
            builderClass.getMethod("setSurface", Surface::class.java).invoke(builder, surface)
            builderClass.getMethod("setFlags", Int::class.javaPrimitiveType).invoke(builder, flags)
            val config = builderClass.getMethod("build").invoke(builder)
            val args = createMethod.parameterTypes.map { p ->
                when {
                    configClass.isAssignableFrom(p) -> config
                    callbackClass.isAssignableFrom(p) -> callback
                    // The modern createVirtualDisplay takes the owner package; the display runs
                    // under the shell identity, so name it rather than passing null (which NPEs).
                    p == String::class.java -> "com.android.shell"
                    else -> defaultArg(p)
                }
            }.toTypedArray()
            val id = (createMethod.invoke(service, *args) as? Number)?.toInt() ?: -1
            return id to callback
        }

        val outcomes = mutableListOf<String>()
        for ((label, flags) in ladder) {
            try {
                val (id, callback) = attempt(flags)
                if (id >= 0) {
                    MirrorLog.i(TAG, "computer_display_created", "id" to id, "flags" to label)
                    return Handle(id, label, service, callback, releaseMethod, resizeMethod, setSurfaceMethod).also { handle = it }
                }
                outcomes += "$label: declined (returned $id)"
                runCatching { releaseMethod.invoke(service, callback) }
            } catch (e: Throwable) {
                val real = rootCause(e)
                outcomes += "$label: ${real.javaClass.simpleName}: ${real.message ?: "no message"}"
                MirrorLog.w(TAG, "display_attempt_failed", "flags" to label, "error" to "${real.javaClass.simpleName}: ${real.message}")
            }
        }

        // Diagnostic-only untrusted probe.
        val untrusted = runCatching { attempt(FLAG_PUBLIC or FLAG_PRESENTATION or FLAG_OWN_CONTENT_ONLY) }
        untrusted.onSuccess { (id, callback) ->
            runCatching { releaseMethod.invoke(service, callback) }
            outcomes += if (id >= 0) "untrusted: OK (so the device blocks TRUSTED displays for shell)" else "untrusted: also declined ($id)"
        }.onFailure { outcomes += "untrusted: ${rootCause(it).javaClass.simpleName}: ${rootCause(it).message ?: "no message"}" }

        throw IllegalStateException("the system refused the computer display — ${outcomes.joinToString(" | ")}")
    }

    private fun rootCause(e: Throwable): Throwable = generateSequence(e) { it.cause }.last()

    fun stop() {
        handle?.release()
        handle = null
    }

    private fun newCallback(callbackClass: Class<*>): Any =
        Proxy.newProxyInstance(callbackClass.classLoader, arrayOf(callbackClass), DisplayCallbackHandler)

    private fun defaultArg(type: Class<*>): Any? = when {
        !type.isPrimitive -> null
        type == Boolean::class.javaPrimitiveType -> false
        type == Int::class.javaPrimitiveType -> 0
        type == Long::class.javaPrimitiveType -> 0L
        type == Float::class.javaPrimitiveType -> 0f
        type == Double::class.javaPrimitiveType -> 0.0
        else -> 0
    }

    /** The display callback does nothing; the display's lifetime is tied to the [Handle]. */
    private object DisplayCallbackHandler : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? = when (method.name) {
            "asBinder" -> android.os.Binder()
            "toString" -> "RokidMirrorDisplayCallback"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.getOrNull(0)
            else -> when (method.returnType) {
                Boolean::class.javaPrimitiveType -> false
                Int::class.javaPrimitiveType -> 0
                Long::class.javaPrimitiveType -> 0L
                else -> null
            }
        }
    }
}
