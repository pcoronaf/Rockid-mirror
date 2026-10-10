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

        val createMethod = service.javaClass.methods.singleOrNull { m ->
            m.name == "createVirtualDisplay" &&
                m.parameterTypes.any { configClass.isAssignableFrom(it) } &&
                m.parameterTypes.any { callbackClass.isAssignableFrom(it) }
        } ?: error("this Android build has no compatible createVirtualDisplay")
        val releaseMethod = service.javaClass.methods.single { m ->
            m.name == "releaseVirtualDisplay" && m.parameterTypes.size == 1 &&
                m.parameterTypes[0].isAssignableFrom(callbackClass)
        }
        val resizeMethod = service.javaClass.methods.firstOrNull { m ->
            m.name == "resizeVirtualDisplay" && m.parameterTypes.firstOrNull()?.isAssignableFrom(callbackClass) == true
        }
        val setSurfaceMethod = service.javaClass.methods.firstOrNull { m ->
            m.name == "setVirtualDisplaySurface" && m.parameterTypes.firstOrNull()?.isAssignableFrom(callbackClass) == true
        }

        val builderCtor = builderClass.constructors.single { c ->
            c.parameterTypes.contentEquals(
                arrayOf(String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType),
            )
        }
        val flagLadder = intArrayOf(
            FLAG_PUBLIC or FLAG_PRESENTATION or FLAG_OWN_CONTENT_ONLY or FLAG_SUPPORTS_TOUCH or FLAG_SHOW_SYSTEM_DECORATIONS or FLAG_TRUSTED,
            FLAG_PUBLIC or FLAG_PRESENTATION or FLAG_OWN_CONTENT_ONLY or FLAG_SHOW_SYSTEM_DECORATIONS or FLAG_TRUSTED,
            FLAG_PUBLIC or FLAG_PRESENTATION or FLAG_OWN_CONTENT_ONLY or FLAG_TRUSTED,
        )

        var lastError: Throwable? = null
        for (flags in flagLadder) {
            val callback = newCallback(callbackClass)
            try {
                val builder = builderCtor.newInstance("RokidMirrorComputer", width, height, dpi)
                builderClass.getMethod("setSurface", Surface::class.java).invoke(builder, surface)
                builderClass.getMethod("setFlags", Int::class.javaPrimitiveType).invoke(builder, flags)
                val config = builderClass.getMethod("build").invoke(builder)
                val args = createMethod.parameterTypes.map { p ->
                    when {
                        configClass.isAssignableFrom(p) -> config
                        callbackClass.isAssignableFrom(p) -> callback
                        else -> defaultArg(p)
                    }
                }.toTypedArray()
                val displayId = (createMethod.invoke(service, *args) as? Number)?.toInt() ?: -1
                if (displayId >= 0) {
                    MirrorLog.i(TAG, "computer_display_created", "id" to displayId, "flags" to "0x${flags.toString(16)}", "w" to width, "h" to height)
                    return Handle(displayId, service, callback, releaseMethod, resizeMethod, setSurfaceMethod).also { handle = it }
                }
                runCatching { releaseMethod.invoke(service, callback) }
            } catch (e: Throwable) {
                lastError = e
                runCatching { releaseMethod.invoke(service, callback) }
            }
        }
        throw IllegalStateException("the system refused a trusted display: ${lastError?.message ?: "unknown reason"}", lastError)
    }

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
