package com.strike.web

import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.strike.core.Logs
import com.strike.server.HttpServer
import com.strike.server.LOCK_PAGE

private const val TAG = "WebUi"
private const val HOST = "127.0.0.1"

/** Configures the single WebView that hosts every Strike screen. */
object WebUi {

    private var web: WebView? = null

    fun mount(view: WebView) {
        web = view
        val settings = view.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mediaPlaybackRequiresUserGesture = false
        settings.cacheMode = WebSettings.LOAD_NO_CACHE
        view.overScrollMode = WebView.OVER_SCROLL_NEVER
        view.defaultFocusHighlightEnabled = false
        // Without a client the WebView hands every URL to the Activity Manager,
        // which sends in-app links to the browser instead of loading them here.
        view.webViewClient = StrikeWebViewClient
        // The server decides: a locked session gets the keypad instead.
        view.loadUrl(page("/"))
    }

    fun cover() {
        val view = web ?: return
        view.post { view.loadUrl(page(LOCK_PAGE)) }
    }

    private fun page(path: String): String = "http://$HOST:${HttpServer.PORT}$path"
}

private object StrikeWebViewClient : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        request.url.host != HOST

    override fun onPageFinished(view: WebView, url: String) {
        if (url.contains("lock.html")) view.clearHistory()
    }

    /**
     * Decoding the live camera can exhaust the renderer. Claiming the death
     * here is what keeps it from taking the recorder down with the screen;
     * returning false would kill the process.
     */
    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        Logs.w(TAG, "the screen crashed and was reloaded, recording was not affected")
        view.loadUrl("http://$HOST:${HttpServer.PORT}/")
        return true
    }
}
