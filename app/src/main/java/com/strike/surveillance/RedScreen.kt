package com.strike.surveillance

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Binder
import android.os.IBinder
import android.os.SystemClock
import android.view.Surface
import com.strike.daemon.AccGate
import com.strike.daemon.DaemonContext
import com.strike.daemon.DaemonFonts
import com.strike.daemon.DaemonLog
import com.strike.daemon.ParkedRails
import java.io.IOException

private const val TAG = "RedScreen"
private const val LAYER = "StrikeDeterrent"
private const val FALLBACK_WIDTH = 1920
private const val FALLBACK_HEIGHT = 1080
private const val TEXT_MAX = 132f
private const val TEXT_SHARE = 0.8f
private const val WAKE_REASSERT_MS = 5_000L
private const val HOLD_POLL_MS = 200L

private val RED = Color.rgb(190, 20, 20)

/**
 * The message someone standing at the car sees. A WebView cannot appear with
 * ACC off, because BYD only composites its own layer then, so this is a
 * SurfaceControl at the top of the z order from the daemon's uid 2000, which
 * is the same route Overdrive's deterrent takes.
 */
class RedScreen {

    private var control: Any? = null
    private var surface: Surface? = null
    private var power: Any? = null
    private var untilMs = 0L
    private var wokeAtMs = 0L
    private var offLockHeld = false
    private val offToken = Binder()

    val isShowing: Boolean get() = control != null

    @Synchronized
    fun show(message: String, seconds: Int, forced: Boolean = false) {
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

    /**
     * Overdrive's deterrent decides its own teardown, on its own thread, every
     * 200ms. The daemon's supervisor blocks for seconds remounting a card and
     * reopening the strip, so it cannot be what lowers the layer.
     */
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
            hide(darken = ParkedRails.isHeld)
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
        if (darken) sleepPanel() else releaseOffLock()
    }

    /** After the warning, or after rails wake the AP. ACC on must not call this. */
    @Synchronized
    fun sleepPanel() {
        if (control != null || AccGate.isUnsafe) return
        val manager = powerManager() ?: return
        turnBacklightOff(manager)
        if (!offLockHeld) turnBacklightOffWithLock(manager)
    }

    private fun paint(onto: Surface, message: String, size: Rect): Boolean {
        val canvas = try {
            onto.lockCanvas(null)
        } catch (e: IllegalArgumentException) {
            DaemonLog.e(TAG, "the panel would not take a canvas: ${e.message}")
            return false
        }
        canvas.drawColor(RED)
        if (DaemonFonts.canDraw) {
            val scale = Math.min(size.width() / 1920f, size.height() / 1080f)
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

    /**
     * The daemon has no Context and so no WindowManager. wm is a shell tool and
     * this process is the shell.
     */
    private fun panelSize(): Rect {
        val printed = read("wm", "size") ?: return Rect(0, 0, FALLBACK_WIDTH, FALLBACK_HEIGHT)
        val match = Regex("(\\d{3,5})x(\\d{3,5})").find(printed)
            ?: return Rect(0, 0, FALLBACK_WIDTH, FALLBACK_HEIGHT)
        return Rect(0, 0, match.groupValues[1].toInt(), match.groupValues[2].toInt())
    }

    /**
     * Overdrive lights the panel from PowerManager, then releases a vendor
     * Off lock via mService.TurnBacklightOnWithLock(IBinder, String). The
     * stub we used first is not that object, which is why the layer sat on
     * a dark panel until ACC came back.
     */
    private fun wakePanel() {
        wokeAtMs = System.currentTimeMillis()
        offLockHeld = false
        val manager = powerManager()
        if (manager != null) {
            turnBacklightOn(manager)
            turnBacklightOnWithLock(manager)
            val status = screenStatus(manager)
            if (status == 0) {
                turnBacklightOnWithLock(manager)
                DaemonLog.w(TAG, "panel still dark after WithLock, status=$status")
            }
        } else {
            powerService()?.let { turnBacklightOn(it) }
        }
    }

    private fun releaseOffLock() {
        if (!offLockHeld) return
        val manager = powerManager() ?: return
        turnBacklightOnWithLock(manager)
        offLockHeld = false
    }

    private fun powerManager(): Any? {
        val ctx = DaemonContext.get() ?: return null
        return try {
            ctx.getSystemService(Context.POWER_SERVICE)
        } catch (e: RuntimeException) {
            null
        }
    }

    private fun powerService(): Any? {
        power?.let { return it }
        return try {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, "power") as? IBinder ?: return null
            Class.forName("android.os.IPowerManager\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
                .also { power = it }
        } catch (e: ReflectiveOperationException) {
            DaemonLog.e(TAG, "this firmware has no power service: ${e.message}")
            null
        }
    }

    private fun screenStatus(power: Any): Int = try {
        val method = power.javaClass.methods.firstOrNull {
            it.name == "getPowerScreenStatus" && it.parameterTypes.isEmpty()
        } ?: return -1
        method.invoke(power) as? Int ?: -1
    } catch (e: ReflectiveOperationException) {
        -1
    }

    private fun turnBacklightOn(power: Any): Boolean {
        for (name in arrayOf("TurnBacklightOn", "turnBacklightOn")) {
            val method = power.javaClass.methods.firstOrNull { it.name == name } ?: continue
            try {
                when (method.parameterTypes.size) {
                    0 -> method.invoke(power)
                    1 -> method.invoke(power, SystemClock.uptimeMillis())
                    else -> continue
                }
            } catch (e: ReflectiveOperationException) {
                continue
            }
            return true
        }
        return false
    }

    private fun turnBacklightOnWithLock(manager: Any): Boolean {
        val service = managerService(manager) ?: return false
        val method = try {
            service.javaClass.getMethod(
                "TurnBacklightOnWithLock",
                IBinder::class.java,
                String::class.java
            )
        } catch (e: NoSuchMethodException) {
            return false
        }
        for (token in arrayOf<IBinder?>(offToken, null)) {
            try {
                method.invoke(service, token, LAYER)
                offLockHeld = false
                return true
            } catch (e: ReflectiveOperationException) {
                continue
            }
        }
        return false
    }

    private fun turnBacklightOff(power: Any): Boolean {
        for (name in arrayOf("TurnBacklightOff", "turnBacklightOff")) {
            val method = power.javaClass.methods.firstOrNull { it.name == name } ?: continue
            try {
                when (method.parameterTypes.size) {
                    0 -> method.invoke(power)
                    1 -> method.invoke(power, SystemClock.uptimeMillis())
                    else -> continue
                }
            } catch (e: ReflectiveOperationException) {
                continue
            }
            return true
        }
        return false
    }

    private fun turnBacklightOffWithLock(manager: Any): Boolean {
        val service = managerService(manager) ?: return false
        val method = service.javaClass.methods.firstOrNull { it.name == "TurnBacklightOffWithLock" }
            ?: return false
        val types = method.parameterTypes
        for (token in arrayOf<IBinder?>(offToken, null)) {
            try {
                when (types.size) {
                    1 -> method.invoke(service, token)
                    2 -> method.invoke(service, token, LAYER)
                    else -> return false
                }
                offLockHeld = true
                return true
            } catch (e: ReflectiveOperationException) {
                continue
            }
        }
        return false
    }

    private fun managerService(manager: Any): Any? = try {
        val field = manager.javaClass.getDeclaredField("mService")
        field.isAccessible = true
        field.get(manager)
    } catch (e: ReflectiveOperationException) {
        null
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

    /**
     * BYD composites only its own layer with ACC off, and that layer sits at
     * 2^30, so anything below it is dropped. Verified in Overdrive's
     * ScreenDeterrent, which reaches the panel the same way.
     */
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

    /**
     * With ACC off the vendor compositor keeps the last frame on the panel, so
     * hiding the layer leaves the red field up until a touch forces a redraw.
     * Overdrive's releaseSurface reparents it off the display first.
     */
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
