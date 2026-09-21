package com.rokidmirror.sender.app

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.rokidmirror.sender.telemetry.MirrorLog

/**
 * What the glasses show in extended-screen mode.
 *
 * Android will not let an ordinary app move *another* app's activity onto a virtual display it
 * created: the launch is refused as a permission denial, and no user-grantable permission lifts
 * that. An app's own activities are allowed, so the second screen carries this workspace, and
 * the phone's control pad drives it.
 *
 * Because this runs in the sender's own process, pointer input is dispatched straight into the
 * view hierarchy. That needs no accessibility service and works even when the pointer service
 * is off.
 */
class GlassesWorkspaceActivity : Activity() {
    companion object {
        private const val TAG = "Workspace"
        const val EXTRA_URL = "url"
        const val START_PAGE = "about:blank"

        @Volatile
        var instance: GlassesWorkspaceActivity? = null
            private set

        val isRunning: Boolean get() = instance != null
    }

    private lateinit var web: WebView
    private val main = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.builtInZoomControls = false
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    MirrorLog.w(TAG, "page_error", "code" to error.errorCode)
                }
            }
            setBackgroundColor(Color.BLACK)
        }
        root.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        web.loadDataWithBaseURL(null, welcomePage(), "text/html", "utf-8", null)
        intent?.getStringExtra(EXTRA_URL)?.let { open(it) }
        MirrorLog.i(TAG, "workspace_started", "display" to (display?.displayId ?: -1))
    }

    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        intent?.getStringExtra(EXTRA_URL)?.let { open(it) }
    }

    override fun onDestroy() {
        instance = null
        runCatching { web.destroy() }
        super.onDestroy()
    }

    /** Loads a page, adding a scheme when the phone sent a bare host. */
    fun open(raw: String) = main.post {
        val url = when {
            raw.isBlank() -> return@post
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            raw.contains('.') && !raw.contains(' ') -> "https://$raw"
            else -> "https://duckduckgo.com/?q=" + android.net.Uri.encode(raw)
        }
        MirrorLog.i(TAG, "open", "hasUrl" to true)
        web.loadUrl(url)
    }

    fun back() = main.post { if (web.canGoBack()) web.goBack() }
    fun reload() = main.post { web.reload() }
    fun home() = main.post { web.loadDataWithBaseURL(null, welcomePage(), "text/html", "utf-8", null) }

    // ---- pointer input, dispatched straight into our own hierarchy ----------------------

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

    /** A swipe from (x, y) by (dx, dy), which the page reads as a scroll. */
    fun scroll(x: Float, y: Float, dx: Float, dy: Float) = main.post {
        val down = SystemClock.uptimeMillis()
        send(MotionEvent.ACTION_DOWN, x, y, down, down)
        val steps = 6
        for (i in 1..steps) {
            val fraction = i / steps.toFloat()
            main.postDelayed({
                send(MotionEvent.ACTION_MOVE, x + dx * fraction, y + dy * fraction, down, SystemClock.uptimeMillis())
            }, (i * 12).toLong())
        }
        main.postDelayed({ send(MotionEvent.ACTION_UP, x + dx, y + dy, down, SystemClock.uptimeMillis()) }, (steps * 12 + 16).toLong())
    }

    private fun send(action: Int, x: Float, y: Float, downTime: Long, eventTime: Long) {
        val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
        event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        runCatching { window.decorView.dispatchTouchEvent(event) }
        event.recycle()
    }

    private fun welcomePage(): String = """
        <html><head><meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
          body { background:#000; color:#d6ffd6; font-family: sans-serif; margin:0; padding:16px; }
          h1 { font-size:20px; margin:0 0 12px; color:#fff; }
          p { font-size:14px; line-height:1.45; margin:0 0 10px; }
          code { color:#9effa0; }
        </style></head>
        <body>
          <h1>Extended screen</h1>
          <p>This display exists only on the glasses.</p>
          <p>Type an address on the phone to open it here, and use the Mouse pad to scroll and tap.</p>
          <p>Android does not allow other apps to be moved to a second display, so this workspace
             is what the glasses can show.</p>
        </body></html>
    """.trimIndent()
}
