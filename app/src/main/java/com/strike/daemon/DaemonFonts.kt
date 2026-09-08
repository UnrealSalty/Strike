package com.strike.daemon

import android.graphics.Paint
import android.graphics.Typeface
import java.io.File

private const val TAG = "Fonts"

// app_process may lack Skia's default typeface; text rendering can abort without one.
object DaemonFonts {

    private val files = arrayOf(
        "/system/fonts/Roboto-Regular.ttf",
        "/system/fonts/RobotoStatic-Regular.ttf",
        "/system/fonts/NotoSans-Regular.ttf",
        "/system/fonts/DroidSans.ttf",
        "/system/fonts/DroidSansFallback.ttf"
    )

    private var face: Typeface? = null
    private var installed = false

    @Synchronized
    fun install() {
        if (installed) return
        installed = true
        var failure: String? = null
        // Android 12 app_process skips the application bind that initializes the font map.
        if (Typeface.DEFAULT == null) {
            try {
                Typeface::class.java.getDeclaredMethod("loadPreinstalledSystemFontMap").invoke(null)
            } catch (e: ReflectiveOperationException) {
                failure = e.javaClass.simpleName
            } catch (e: RuntimeException) {
                failure = e.javaClass.simpleName
            }
        }
        for (path in files) {
            val file = File(path)
            if (!file.isFile) continue
            val loaded = try {
                Typeface.createFromFile(file)
            } catch (e: RuntimeException) {
                failure = e.javaClass.simpleName
                null
            } ?: continue
            face = loaded
            seedDefault(loaded)
            DaemonLog.d(TAG, "text from $path")
            return
        }
        DaemonLog.w(TAG, "system fonts unavailable${failure?.let { " ($it)" } ?: ""}; " +
            "recording continues, deterrent text is disabled")
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
