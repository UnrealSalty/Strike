package com.strike.surveillance

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.view.Surface
import com.strike.daemon.AccGate
import com.strike.daemon.DaemonFonts
import com.strike.daemon.DaemonLog
import com.strike.daemon.ParkedPanel
import com.strike.daemon.ParkedRails
import java.io.IOException
import java.util.zip.ZipFile

private const val TAG = "RedScreen"
private const val LAYER = "StrikeDeterrent"
private const val FALLBACK_WIDTH = 1920
private const val FALLBACK_HEIGHT = 1080
private const val TEXT_MAX = 132f
private const val TEXT_SHARE = 0.8f
private const val WORDMARK = "assets/web/img/wordmark.webp"
private const val WORDMARK_WIDTH = 520f
private const val WORDMARK_MID = 0.26f
private const val WAKE_REASSERT_MS = 5_000L
private const val HOLD_POLL_MS = 200L

private val RED = Color.rgb(190, 20, 20)

// Parked display output requires a shell-owned SurfaceControl layer.
class RedScreen(
    private val panel: ParkedPanel = ParkedPanel(),
    private val keepDark: () -> Boolean = { ParkedRails.isHeld }
) {

    /** app_process has no AssetManager, so the wordmark is read out of the apk. */
    var apkPath: String? = null

    private var control: Any? = null
    private var surface: Surface? = null
    private var wordmark: Bitmap? = null
    private var readWordmark = false
    private var untilMs = 0L
    private var wokeAtMs = 0L
    private var closed = false

    val isShowing: Boolean get() = control != null

    @Synchronized
    fun show(message: String, seconds: Int, forced: Boolean = false) {
        if (closed) return
        if (!forced && AccGate.isUnsafe) return
        untilMs = System.currentTimeMillis() + seconds * 1_000L
        if (control != null) return
        wakePanel()
        val size = panelSize()
        val made = createLayer(size.width(), size.height()) ?: return
        control = made
        val onto = surfaceOn(made)
        if (onto == null) {
            hide()
            return
        }
        surface = onto
        if (!paint(onto, message, size)) {
            hide()
            return
        }
        place(made)
        wakePanel()
        hold(forced)
        DaemonLog.d(TAG, "showing the deterrent for ${seconds}s")
    }

    // Hide on a dedicated timer; storage and camera recovery can block the supervisor.
    private fun hold(forced: Boolean) {
        Thread({
            while (true) {
                Thread.sleep(HOLD_POLL_MS)
                if (!step(forced)) return@Thread
            }
        }, "deterrent-hold").also { it.isDaemon = true }.start()
    }

    @Synchronized
    private fun step(forced: Boolean): Boolean {
        if (control == null) return false
        val now = System.currentTimeMillis()
        if (now >= untilMs || (!forced && AccGate.isUnsafe)) {
            hide(darken = keepDark())
            return false
        }
        if (now - wokeAtMs >= WAKE_REASSERT_MS) wakePanel()
        return true
    }

    @Synchronized
    fun hide(darken: Boolean = false) {
        wokeAtMs = 0L
        surface?.release()
        surface = null
        val held = control
        control = null
        if (held != null) releaseLayer(held)
        if (darken) sleepPanel() else panel.release()
    }

    /** After the warning, or after rails wake the AP. ACC on must not call this. */
    @Synchronized
    fun sleepPanel() {
        if (closed || control != null || AccGate.isUnsafe) return
        panel.darken()
    }

    @Synchronized
    fun close(): Boolean {
        closed = true
        hide()
        return panel.release()
    }

    @Synchronized
    fun releasePanel(): Boolean = panel.release()

    @Synchronized
    fun parkedWake(stillWanted: () -> Boolean): Boolean {
        if (closed || !stillWanted() || AccGate.isUnsafe) return false
        if (control != null) return true
        val woke = ParkedRails.wakeAp()
        if (!stillWanted() || AccGate.isUnsafe) {
            panel.wake()
            return false
        }
        sleepPanel()
        if (!stillWanted() || AccGate.isUnsafe) {
            panel.wake()
            return false
        }
        return woke
    }

    private fun paint(onto: Surface, message: String, size: Rect): Boolean {
        val canvas = try {
            onto.lockCanvas(null)
        } catch (e: IllegalArgumentException) {
            DaemonLog.e(TAG, "the panel would not take a canvas: ${e.message}")
            return false
        }
        canvas.drawColor(RED)
        val scale = Math.min(size.width() / FALLBACK_WIDTH.toFloat(), size.height() / FALLBACK_HEIGHT.toFloat())
        drawWordmark(canvas, size, scale)
        if (DaemonFonts.canDraw) {
            val mid = size.width() / 2f
            val room = size.width() * TEXT_SHARE
            val headline = Paint()
            DaemonFonts.apply(headline)
            headline.isAntiAlias = true
            headline.color = Color.WHITE
            headline.textAlign = Paint.Align.CENTER
            headline.textSize = TEXT_MAX * scale
            val wide = headline.measureText(message)
            if (wide > room) headline.textSize = headline.textSize * room / wide
            canvas.drawText(message, mid, size.height() * 0.48f, headline)
            val sub = Paint()
            DaemonFonts.apply(sub)
            sub.isAntiAlias = true
            sub.color = Color.WHITE
            sub.textAlign = Paint.Align.CENTER
            sub.textSize = 56f * scale
            sub.alpha = 220
            canvas.drawText(SurveillanceSettings.SCREEN_SUBTITLE, mid, size.height() * 0.62f, sub)
        }
        onto.unlockCanvasAndPost(canvas)
        return true
    }

    private fun drawWordmark(canvas: Canvas, size: Rect, scale: Float) {
        val mark = wordmark() ?: return
        val wide = WORDMARK_WIDTH * scale
        val high = wide * mark.height / mark.width
        val mid = size.width() / 2f
        val centre = size.height() * WORDMARK_MID
        val into = Rect(
            Math.round(mid - wide / 2f),
            Math.round(centre - high / 2f),
            Math.round(mid + wide / 2f),
            Math.round(centre + high / 2f)
        )
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        paint.colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(mark, null, into, paint)
    }

    private fun wordmark(): Bitmap? {
        wordmark?.let { return it }
        if (readWordmark) return null
        readWordmark = true
        val path = apkPath ?: return null
        val read = try {
            ZipFile(path).use { apk ->
                val entry = apk.getEntry(WORDMARK)
                if (entry == null) null else apk.getInputStream(entry).use { BitmapFactory.decodeStream(it) }
            }
        } catch (e: IOException) {
            null
        }
        if (read == null) DaemonLog.w(TAG, "the wordmark did not decode; the deterrent shows text only")
        wordmark = read
        return read
    }

    private fun panelSize(): Rect {
        val printed = read("wm", "size") ?: return Rect(0, 0, FALLBACK_WIDTH, FALLBACK_HEIGHT)
        val match = Regex("(\\d{3,5})x(\\d{3,5})").find(printed)
            ?: return Rect(0, 0, FALLBACK_WIDTH, FALLBACK_HEIGHT)
        return Rect(0, 0, match.groupValues[1].toInt(), match.groupValues[2].toInt())
    }

    private fun wakePanel() {
        wokeAtMs = System.currentTimeMillis()
        panel.wake()
    }

    private fun read(vararg command: String): String? = try {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val printed = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        printed
    } catch (e: IOException) {
        null
    } catch (e: InterruptedException) {
        null
    }

    private fun createLayer(width: Int, height: Int): Any? = try {
        val builder = Class.forName("android.view.SurfaceControl\$Builder")
        val made = builder.getDeclaredConstructor().newInstance()
        builder.getMethod("setName", String::class.java).invoke(made, LAYER)
        builder.getMethod("setBufferSize", Int::class.java, Int::class.java)
            .invoke(made, width, height)
        try {
            builder.getMethod("setOpaque", Boolean::class.java).invoke(made, true)
        } catch (e: NoSuchMethodException) {
            // Older firmware has no setter; the buffer is still opaque.
        }
        builder.getMethod("build").invoke(made)
    } catch (e: ReflectiveOperationException) {
        DaemonLog.e(TAG, "this firmware will not give Strike a layer: ${e.message}")
        null
    }

    // The constructor is public in the build SDK and hidden on the head unit's.
    private fun surfaceOn(held: Any): Surface? = try {
        val ctor = Surface::class.java
            .getDeclaredConstructor(Class.forName("android.view.SurfaceControl"))
        ctor.isAccessible = true
        ctor.newInstance(held) as Surface
    } catch (e: ReflectiveOperationException) {
        DaemonLog.e(TAG, "the layer would not become a surface: ${e.message}")
        null
    }

    // Match Overdrive's ScreenDeterrent layer order; BYD's parked compositor rejects lower layers.
    private fun place(held: Any) {
        transact { transaction, controlClass, transactionClass ->
            try {
                transactionClass.getMethod("setLayerStack", controlClass, Int::class.java)
                    .invoke(transaction, held, 0)
            } catch (e: NoSuchMethodException) {
                // Older firmware parents the layer to the default display.
            }
            transactionClass.getMethod("setLayer", controlClass, Int::class.java)
                .invoke(transaction, held, Integer.MAX_VALUE)
            try {
                transactionClass.getMethod("setAlpha", controlClass, Float::class.java)
                    .invoke(transaction, held, 1f)
            } catch (e: NoSuchMethodException) {
                // The layer still shows; alpha stays at the builder default.
            }
            transactionClass.getMethod("show", controlClass).invoke(transaction, held)
        }
    }

    // Detach before releasing; the parked compositor can otherwise retain the last red frame.
    private fun releaseLayer(held: Any) {
        transact { transaction, controlClass, transactionClass ->
            transactionClass.getMethod("hide", controlClass).invoke(transaction, held)
            try {
                transactionClass.getMethod("reparent", controlClass, controlClass)
                    .invoke(transaction, held, null)
            } catch (e: NoSuchMethodException) {
                // Older firmware drops the layer on release alone.
            }
        }
        try {
            Class.forName("android.view.SurfaceControl").getMethod("release").invoke(held)
        } catch (e: ReflectiveOperationException) {
            DaemonLog.w(TAG, "the deterrent layer would not release: ${e.message}")
        }
    }

    private fun transact(ops: (Any, Class<*>, Class<*>) -> Unit) {
        try {
            val controlClass = Class.forName("android.view.SurfaceControl")
            val transactionClass = Class.forName("android.view.SurfaceControl\$Transaction")
            val transaction = transactionClass.getDeclaredConstructor().newInstance()
            ops(transaction, controlClass, transactionClass)
            transactionClass.getMethod("apply").invoke(transaction)
        } catch (e: ReflectiveOperationException) {
            DaemonLog.e(TAG, "the deterrent transaction was refused: ${e.message}")
        }
    }
}
