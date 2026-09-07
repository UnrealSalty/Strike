package com.strike.web

import android.webkit.JavascriptInterface

// Keep device-only calls here; shared app and browser features use HTTP.
class StrikeBridge {

    @JavascriptInterface
    fun isInCar(): Boolean = TODO()

    @JavascriptInterface
    fun serverPort(): Int = TODO()
}
