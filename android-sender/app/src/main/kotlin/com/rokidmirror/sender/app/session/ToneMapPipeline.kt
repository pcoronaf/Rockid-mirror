package com.rokidmirror.sender.app.session

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.rokidmirror.protocol.control.ErrorCode
import com.rokidmirror.protocol.control.MirrorException
import com.rokidmirror.sender.telemetry.MirrorLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch

/**
 * Optional tone mapping between the captured screen and the encoder.
 *
 * The glasses' panel has very few brightness levels and fakes the rest by scattering lit pixels,
 * so every mid-grey arrives as visible speckle — worst in dark areas, which is why a dark app
 * background looks like sand. Pure black is the one level it renders perfectly, because the pixels
 * are simply off. Pulling near-black down to true black therefore removes the worst of the grain,
 * and on a see-through display it also makes those areas properly transparent.
 *
 * Normally the capture renders straight into the encoder's input surface with no copy. When this
 * is enabled the capture renders here instead, and each frame is drawn through a levels shader
 * into the encoder surface. That costs one GPU pass per frame, which is why it is opt-in.
 *
 * Everything runs on this object's own thread: an EGL context belongs to the thread that made it.
 */
class ToneMapPipeline(
    output: Surface,
    private var width: Int,
    private var height: Int,
) {
    private companion object {
        const val TAG = "ToneMap"
        const val EGL_RECORDABLE_ANDROID = 0x3142
        const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """
        /** The #extension directive has to come before anything else in the source. */
        const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            uniform float uBlack;
            uniform float uWhite;
            uniform float uGamma;
            void main() {
                vec3 c = texture2D(uTexture, vTexCoord).rgb;
                // Everything at or below uBlack becomes true black; the rest is stretched back out
                // so the picture keeps its range instead of simply going dark.
                vec3 v = clamp((c - uBlack) / max(uWhite - uBlack, 0.001), 0.0, 1.0);
                v = pow(v, vec3(1.0 / uGamma));
                gl_FragColor = vec4(v, 1.0);
            }
        """
    }

    private val thread = HandlerThread("tonemap").apply { start() }
    private val handler = Handler(thread.looper)

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var eglConfig: EGLConfig? = null
    private var program = 0
    private var textureId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private val texMatrix = FloatArray(16)

    @Volatile private var black = 0.10f
    @Volatile private var white = 1.0f
    @Volatile private var gamma = 1.0f
    @Volatile private var released = false

    /** Frames drawn through the shader; stays at 0 if the pass is not actually running. */
    @Volatile var framesProcessed: Long = 0; private set

    /** The surface the capture or virtual display must render into. */
    lateinit var inputSurface: Surface
        private set

    private val vertices: FloatBuffer = floats(
        -1f, -1f, 0f, 0f,
        1f, -1f, 1f, 0f,
        -1f, 1f, 0f, 1f,
        1f, 1f, 1f, 1f,
    )

    init {
        val ready = CountDownLatch(1)
        var failure: Throwable? = null
        handler.post {
            try {
                setupEgl(output)
                setupGl()
                val st = SurfaceTexture(textureId).apply {
                    setDefaultBufferSize(width, height)
                    setOnFrameAvailableListener({ handler.post { drawFrame() } }, handler)
                }
                surfaceTexture = st
                inputSurface = Surface(st)
            } catch (e: Throwable) {
                failure = e
            } finally {
                ready.countDown()
            }
        }
        ready.await()
        failure?.let {
            release()
            throw MirrorException(ErrorCode.RENDERER_FAILED, "tone mapping setup failed: ${it.message}", it)
        }
        MirrorLog.i(TAG, "tonemap_started", "w" to width, "h" to height)
    }

    /**
     * [blackPoint] and [whitePoint] are 0..1 input levels; anything at or below the black point
     * becomes true black. [gammaValue] above 1 brightens the midtones. Safe to call while running.
     */
    fun setLevels(blackPoint: Float, whitePoint: Float, gammaValue: Float) {
        black = blackPoint.coerceIn(0f, 0.9f)
        white = whitePoint.coerceIn(black + 0.05f, 1f)
        gamma = gammaValue.coerceIn(0.4f, 2.5f)
    }

    /**
     * Points the pass at a new encoder surface after an encoder restart, keeping the GL context
     * and — crucially — the same [inputSurface]. The capture keeps rendering into the surface it
     * already has, so nothing it depends on is ever released underneath it.
     */
    fun setOutput(output: Surface, newWidth: Int, newHeight: Int) {
        if (released) return
        val done = CountDownLatch(1)
        var failure: Throwable? = null
        handler.post {
            try {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
                eglSurface = EGL14.eglCreateWindowSurface(display, eglConfig, output, intArrayOf(EGL14.EGL_NONE), 0)
                check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed (0x${EGL14.eglGetError().toString(16)})" }
                check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "eglMakeCurrent failed" }
                width = newWidth
                height = newHeight
                surfaceTexture?.setDefaultBufferSize(newWidth, newHeight)
            } catch (e: Throwable) {
                failure = e
            } finally {
                done.countDown()
            }
        }
        done.await()
        failure?.let { throw MirrorException(ErrorCode.RENDERER_FAILED, "tone mapping retarget failed: ${it.message}", it) }
        MirrorLog.i(TAG, "tonemap_retargeted", "w" to newWidth, "h" to newHeight)
    }

    fun release() {
        if (released) return
        released = true
        val done = CountDownLatch(1)
        handler.post {
            runCatching { surfaceTexture?.setOnFrameAvailableListener(null) }
            runCatching { if (::inputSurface.isInitialized) inputSurface.release() }
            runCatching { surfaceTexture?.release() }
            surfaceTexture = null
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                EGL14.eglReleaseThread()
                EGL14.eglTerminate(display)
            }
            display = EGL14.EGL_NO_DISPLAY
            context = EGL14.EGL_NO_CONTEXT
            eglSurface = EGL14.EGL_NO_SURFACE
            done.countDown()
        }
        runCatching { done.await() }
        thread.quitSafely()
    }

    private fun drawFrame() {
        if (released) return
        val st = surfaceTexture ?: return
        try {
            st.updateTexImage()
            st.getTransformMatrix(texMatrix)
            if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) return

            GLES20.glViewport(0, 0, width, height)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(program)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTexMatrix"), 1, false, texMatrix, 0)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uBlack"), black)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uWhite"), white)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uGamma"), gamma)

            val position = GLES20.glGetAttribLocation(program, "aPosition")
            val texCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
            vertices.position(0)
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, vertices)
            GLES20.glEnableVertexAttribArray(position)
            vertices.position(2)
            GLES20.glVertexAttribPointer(texCoord, 2, GLES20.GL_FLOAT, false, 16, vertices)
            GLES20.glEnableVertexAttribArray(texCoord)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            // Carry the capture's own timestamp through, so encoder PTS and the latency figures
            // stay meaningful rather than being restamped by this pass.
            EGLExt.eglPresentationTimeANDROID(display, eglSurface, st.timestamp)
            EGL14.eglSwapBuffers(display, eglSurface)
            framesProcessed++
        } catch (e: Throwable) {
            MirrorLog.w(TAG, "tonemap_frame_failed", "error" to e.message)
        }
    }

    private fun setupEgl(output: Surface) {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "no EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) { "no recordable EGL config" }
        eglConfig = configs[0]
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
        eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], output, intArrayOf(EGL14.EGL_NONE), 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed (0x${EGL14.eglGetError().toString(16)})" }
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "eglMakeCurrent failed" }
    }

    private fun setupGl() {
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER))
        GLES20.glAttachShader(program, compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER.trimStart()))
        GLES20.glLinkProgram(program)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        check(linked[0] == GLES20.GL_TRUE) { "link failed: ${GLES20.glGetProgramInfoLog(program)}" }

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        textureId = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}" }
        return shader
    }

    private fun floats(vararg values: Float): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(values); position(0) }
}
