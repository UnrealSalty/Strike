package com.strike.daemon

import android.graphics.Paint
import android.graphics.Typeface
import java.io.File

private const val TAG = "Fonts"

/**
 * app_process never seeds Skia's default face. measureText / drawText then
 * abort the daemon: src == nullptr && gDefaultTypeface == nullptr.
 */
object DaemonFonts {

    private val files = arrayOf(
        "/system/fonts/Roboto-Regular.ttf",
        "/system/fonts/RobotoStatic-Regular.ttf",
        "/system/fonts/NotoSans-Regular.ttf",
        "/system/fonts/DroidSans.ttf",
        "/system/fonts/DroidSansFallback.ttf"
    )

    private var face: Typeface? = null

    fun install() {
        if (face != null) return
        for (path in files) {
            val file = File(path)
            if (!file.isFile) continue
            val loaded = Typeface.createFromFile(file) ?: continue
            face = loaded
            seedDefault(loaded)
            DaemonLog.d(TAG, "text from $path")
            return
        }
        DaemonLog.w(TAG, "no system font, so the deterrent will be a red field only")
    }

    fun apply(paint: Paint) {
        install()
        face?.let { paint.typeface = it }
    }

    val canDraw: Boolean
        get() {
            install()
            return face != null
        }

    private fun seedDefault(loaded: Typeface) {
        try {
            val setDefault = Typeface::class.java.getDeclaredMethod("setDefault", Typeface::class.java)
            setDefault.isAccessible = true
            setDefault.invoke(null, loaded)
        } catch (e: ReflectiveOperationException) {
            // apply() still sets the face on each paint.
        }
    }
}
