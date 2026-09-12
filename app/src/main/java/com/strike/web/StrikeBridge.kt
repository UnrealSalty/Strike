package com.strike.web

import android.webkit.JavascriptInterface

// Only the car has this bridge. The phone browser plays the same clips over HTTP, so
// nothing here may hold state the page cannot rebuild on its own.
class StrikeBridge(private val player: NativePlayer) {

    @JavascriptInterface
    fun play(url: String, left: Int, top: Int, width: Int, height: Int, corner: Int, angle: String, muted: Boolean) =
        player.play(url, left, top, width, height, corner, angle, muted)

    @JavascriptInterface
    fun setRect(left: Int, top: Int, width: Int, height: Int, corner: Int) =
        player.setRect(left, top, width, height, corner)

    @JavascriptInterface
    fun setAngle(angle: String) = player.setAngle(angle)

    @JavascriptInterface
    fun setPlaying(playing: Boolean) = player.setPlaying(playing)

    @JavascriptInterface
    fun seek(positionMs: Int) = player.seek(positionMs)

    @JavascriptInterface
    fun setMuted(muted: Boolean) = player.setMuted(muted)

    @JavascriptInterface
    fun positionMs(): Int = player.positionMs()

    @JavascriptInterface
    fun durationMs(): Int = player.durationMs()

    @JavascriptInterface
    fun stop() = player.stop()
}
