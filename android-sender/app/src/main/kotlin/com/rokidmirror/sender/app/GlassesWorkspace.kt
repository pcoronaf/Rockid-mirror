package com.rokidmirror.sender.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.rokidmirror.sender.telemetry.MirrorLog

/**
 * What the glasses show in extended-screen mode.
 *
 * Android refuses `setLaunchDisplayId` onto a virtual display an ordinary app created, and that
 * refusal covers the app's own activities too, not only other apps'. Activities are therefore
 * not an option at all here. Content reaches a secondary display as a window instead: a
 * [Presentation], which is the API meant for exactly this, falling back to an overlay window
 * when the platform declines that as well.
 *
 * The content is an ordinary view hierarchy in the sender's process, so the control pad's
 * gestures are dispatched straight into it, with no accessibility service involved.
 */
class GlassesWorkspace private constructor(
    private val view: WorkspaceView,
    private val dismissAction: () -> Unit,
    val host: String,
) {
    companion object {
        private const val TAG = "Workspace"

        /**
         * @return the workspace, or a failure describing every route the platform refused.
         */
        fun show(activity: Activity, display: Display): Result<GlassesWorkspace> {
            val reasons = StringBuilder()

            // Route 1: a Presentation, the documented way to put content on a second display.
            runCatching {
                val presentation = Presentation(activity, display)
                val view = WorkspaceView(presentation.context)
                presentation.setContentView(view)
                presentation.show()
                return Result.success(GlassesWorkspace(view, { runCatching { presentation.dismiss() } }, "presentation"))
            }.onFailure { reasons.append("presentation: ${it.javaClass.simpleName}: ${it.message}") }

            // Route 2: an overlay window on that display, if the user granted the permission.
            if (!Settings.canDrawOverlays(activity)) {
                reasons.append("; overlay: 'Display over other apps' is not granted")
            } else {
                runCatching {
                    val displayContext = activity.createDisplayContext(display)
                    val windows = displayContext.getSystemService(WindowManager::class.java)
                    val view = WorkspaceView(displayContext)
                    val params = WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                        PixelFormat.OPAQUE,
                    )
                    windows.addView(view, params)
                    return Result.success(GlassesWorkspace(view, { runCatching { windows.removeView(view) } }, "overlay"))
                }.onFailure { reasons.append("; overlay: ${it.javaClass.simpleName}: ${it.message}") }
            }

            MirrorLog.w(TAG, "workspace_unavailable", "reasons" to reasons.toString())
            return Result.failure(IllegalStateException(reasons.toString()))
        }

        fun overlayPermissionIntent(context: Context): android.content.Intent =
            android.content.Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
    }

    fun open(query: String) = view.open(query)
    fun back() = view.back()
    fun home() = view.home()
    fun reload() = view.reload()
    fun tap(x: Float, y: Float) = view.tap(x, y)
    fun longPress(x: Float, y: Float) = view.longPress(x, y)
    fun scroll(x: Float, y: Float, dx: Float, dy: Float) = view.scroll(x, y, dx, dy)
    fun dismiss() { view.destroy(); dismissAction() }
}

/** The workspace's content: a browser surface plus pointer dispatch into it. */
@SuppressLint("ViewConstructor", "SetJavaScriptEnabled")
class WorkspaceView(context: Context) : FrameLayout(context) {
    private companion object { const val TAG = "Workspace" }

    private val main = Handler(Looper.getMainLooper())
    private val web = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        webViewClient = WebViewClient()
        setBackgroundColor(Color.BLACK)
    }

    init {
        setBackgroundColor(Color.BLACK)
        addView(web, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        home()
    }

    fun open(raw: String) = main.post {
        val url = when {
            raw.isBlank() -> return@post
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            raw.contains('.') && !raw.contains(' ') -> "https://$raw"
            else -> "https://duckduckgo.com/?q=" + Uri.encode(raw)
        }
        MirrorLog.i(TAG, "open")
        web.loadUrl(url)
    }

    fun back() = main.post { if (web.canGoBack()) web.goBack() }
    fun reload() = main.post { web.reload() }
    fun home() = main.post { web.loadDataWithBaseURL(null, welcomePage(), "text/html", "utf-8", null) }
    fun destroy() = main.post { runCatching { web.destroy() } }

    fun tap(x: Float, y: Float) = main.post {
        val down = SystemClock.uptimeMillis()
        send(MotionEvent.ACTION_DOWN, x, y, down, down)
        main.postDelayed({ send(MotionEvent.ACTION_UP, x, y, down, SystemClock.uptimeMillis()) }, 60)
    }

    fun longPress(x: Float, y: Float) = main.post {
        val down = SystemClock.uptimeMillis()
        send(MotionEvent.ACTION_DOWN, x, y, down, down)
        main.postDelayed({ send(MotionEvent.ACTION_UP, x, y, down, SystemClock.uptimeMillis()) }, 700)
    }

    fun scroll(x: Float, y: Float, dx: Float, dy: Float) = main.post {
        val down = SystemClock.uptimeMillis()
        send(MotionEvent.ACTION_DOWN, x, y, down, down)
        val steps = 6
        for (i in 1..steps) {
            val fraction = i / steps.toFloat()
            main.postDelayed({ send(MotionEvent.ACTION_MOVE, x + dx * fraction, y + dy * fraction, down, SystemClock.uptimeMillis()) }, (i * 12).toLong())
        }
        main.postDelayed({ send(MotionEvent.ACTION_UP, x + dx, y + dy, down, SystemClock.uptimeMillis()) }, (steps * 12 + 16).toLong())
    }

    private fun send(action: Int, x: Float, y: Float, downTime: Long, eventTime: Long) {
        val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        runCatching { dispatchTouchEvent(event) }
        event.recycle()
    }

    private fun welcomePage(): String = """
        <html><head><meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
          body { background:#000; color:#d6ffd6; font-family: sans-serif; margin:0; padding:16px; }
          h1 { font-size:20px; margin:0 0 12px; color:#fff; }
          p { font-size:14px; line-height:1.45; margin:0 0 10px; }
        </style></head>
        <body>
          <h1>Extended screen</h1>
          <p>This display exists only on the glasses.</p>
          <p>Type an address on the phone to open it here, and use the Mouse pad to scroll and tap.</p>
        </body></html>
    """.trimIndent()
}
