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
        // Keep navigation inside the WebView rather than opening the external browser.
        view.webViewClient = StrikeWebViewClient
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

    // Handle renderer death so it does not terminate the host app.
    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        Logs.w(TAG, "the screen crashed and was reloaded, recording was not affected")
        view.loadUrl("http://$HOST:${HttpServer.PORT}/")
        return true
    }
}
