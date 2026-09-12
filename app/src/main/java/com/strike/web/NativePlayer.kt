package com.strike.web

import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewOutlineProvider
import android.webkit.CookieManager
import android.widget.FrameLayout
import com.strike.core.Logs
import com.strike.server.HttpServer
import java.io.IOException

private const val TAG = "Player"
private const val ZOOM = 2f
private const val NO_SEEK = -1

// The car WebView cannot decode H.265 and is slow to start H.264, so the head unit's
// decoder paints clips on a surface behind the page. The web modal keeps every control.
class NativePlayer(private val view: TextureView, private val toWeb: (String) -> Unit) :
    TextureView.SurfaceTextureListener {

    private val served = "http://127.0.0.1:${HttpServer.PORT}/"
    private val lock = Any()

    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var clip: String? = null
    private var angle = ""
    private var widthPx = 0
    private var heightPx = 0
    private var cornerPx = 0f
    private var seekingMs = NO_SEEK
    private var queuedMs = NO_SEEK

    @Volatile
    private var muted = false

    @Volatile
    private var ready = false

    init {
        view.surfaceTextureListener = this
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(host: View, outline: Outline) {
                outline.setRoundRect(0, 0, host.width, host.height, cornerPx)
            }
        }
        view.clipToOutline = true
    }

    fun play(url: String, left: Int, top: Int, width: Int, height: Int, corner: Int, angle: String, muted: Boolean) {
        if (!url.startsWith(served)) return
        view.post {
            release()
            clip = url
            this.angle = angle
            this.muted = muted
            place(left, top, width, height, corner)
            view.visibility = View.VISIBLE
            view.surfaceTexture?.let { open(it) }
        }
    }

    fun setRect(left: Int, top: Int, width: Int, height: Int, corner: Int) {
        view.post { place(left, top, width, height, corner) }
    }

    fun setAngle(angle: String) {
        view.post {
            this.angle = angle
            applyAngle()
        }
    }

    fun setPlaying(playing: Boolean) = onPlayer { if (playing) it.start() else it.pause() }

    fun seek(positionMs: Int) = onPlayer { startSeek(it, positionMs) }

    fun setMuted(muted: Boolean) {
        this.muted = muted
        onPlayer { volume(it) }
    }

    fun positionMs(): Int = synchronized(lock) { if (ready) player?.currentPosition ?: 0 else 0 }

    fun durationMs(): Int = synchronized(lock) { if (ready) player?.duration ?: 0 else 0 }

    fun stop() {
        view.post {
            release()
            view.visibility = View.GONE
        }
    }

    // Leaving the screen must not leave the clip's audio playing behind another app.
    fun pause() {
        view.post {
            synchronized(lock) {
                val active = player ?: return@post
                if (!ready || !active.isPlaying) return@post
                active.pause()
            }
            toWeb("_paused()")
        }
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) = open(texture)

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = applyAngle()

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        release()
        return true
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
    }

    private fun open(texture: SurfaceTexture) {
        val wanted = clip ?: return
        synchronized(lock) {
            if (player != null) return
            val fresh = MediaPlayer()
            val target = Surface(texture)
            surface = target
            player = fresh
            fresh.setSurface(target)
            fresh.setOnPreparedListener { prepared ->
                ready = true
                volume(prepared)
                prepared.start()
                toWeb("_ready(${prepared.duration})")
            }
            fresh.setOnInfoListener { _, what, _ ->
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) toWeb("_firstFrame()")
                false
            }
            fresh.setOnSeekCompleteListener { seeked ->
                val settled = synchronized(lock) {
                    seekingMs = NO_SEEK
                    val next = queuedMs
                    queuedMs = NO_SEEK
                    if (next == NO_SEEK || !ready) true else {
                        startSeek(seeked, next)
                        false
                    }
                }
                if (settled) toWeb("_seeked()")
            }
            fresh.setOnCompletionListener { toWeb("_ended()") }
            fresh.setOnErrorListener { _, what, extra ->
                Logs.w(TAG, "a clip could not be played ($what/$extra)")
                fail()
                true
            }
            try {
                // MediaPlayer has its own HTTP stack, so the car cookie has to be carried
                // over by hand or the server answers the clip with 401.
                val headers = mapOf("Cookie" to CookieManager.getInstance().getCookie(wanted).orEmpty())
                fresh.setDataSource(view.context, Uri.parse(wanted), headers)
                fresh.prepareAsync()
            } catch (e: IOException) {
                Logs.w(TAG, "a clip could not be opened")
                fail()
            }
        }
    }

    private fun fail() {
        release()
        view.visibility = View.GONE
        toWeb("_error()")
    }

    // MediaPlayer runs one seek at a time. A dragged scrubber queues hundreds, so only
    // the newest target is kept and the rest are dropped.
    private fun startSeek(active: MediaPlayer, positionMs: Int) {
        if (seekingMs != NO_SEEK) {
            queuedMs = positionMs
            return
        }
        seekingMs = positionMs
        active.seekTo(positionMs.toLong(), MediaPlayer.SEEK_CLOSEST_SYNC)
    }

    private fun release() {
        synchronized(lock) {
            ready = false
            seekingMs = NO_SEEK
            queuedMs = NO_SEEK
            player?.release()
            player = null
            surface?.release()
            surface = null
        }
    }

    private fun place(left: Int, top: Int, width: Int, height: Int, corner: Int) {
        if (width <= 0 || height <= 0) return
        widthPx = width
        heightPx = height
        cornerPx = corner.toFloat()
        val params = FrameLayout.LayoutParams(width, height)
        params.leftMargin = left
        params.topMargin = top
        view.layoutParams = params
        view.invalidateOutline()
        applyAngle()
    }

    private fun applyAngle() {
        if (widthPx <= 0 || heightPx <= 0) return
        val matrix = Matrix()
        originOf(angle)?.let { matrix.setScale(ZOOM, ZOOM, widthPx * it.first, heightPx * it.second) }
        view.setTransform(matrix)
    }

    private fun volume(active: MediaPlayer) {
        val level = if (muted) 0f else 1f
        active.setVolume(level, level)
    }

    private fun onPlayer(action: (MediaPlayer) -> Unit) {
        view.post {
            synchronized(lock) {
                val active = player
                if (active != null && ready) action(active)
            }
        }
    }
}

// Matches the CSS transform-origin that crops each corner of the 2x2 mosaic.
private fun originOf(angle: String): Pair<Float, Float>? = when (angle) {
    "front" -> 0f to 0f
    "right" -> 1f to 0f
    "rear" -> 0f to 1f
    "left" -> 1f to 1f
    else -> null
}
