package com.strike.web

import android.webkit.JavascriptInterface

/**
 * The only surface the web UI may call natively. Everything else goes over
 * HTTP so the in-car WebView and a phone browser behave identically.
 */
class StrikeBridge {

    @JavascriptInterface
    fun isInCar(): Boolean = TODO()

    @JavascriptInterface
    fun serverPort(): Int = TODO()
}
