package com.strike.camera

import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.media.Image
import android.media.ImageReader
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.strike.daemon.DaemonLog
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

private const val TAG = "FrameBus"
private const val FRAME_WAIT_MS = 500L
private const val SILENCE_MS = 10_000L

private const val POOL = 6

/** [frame] is the size of [surface], which is what the crop is drawn into. */
class Consumer(
    val name: String,
    val surface: Surface,
    val view: CameraView,
    val frame: Frame
)

// Owns capture and all EGL work on the bus thread.
// Other threads enqueue consumer changes; they must not destroy EGL resources.
class FrameBus(val stripWidth: Int, val stripHeight: Int) {

    private val consumers = CopyOnWriteArrayList<Consumer>()
    private val targets = HashMap<String, EGLSurface>()
    private val retired = ConcurrentLinkedQueue<String>()
    private val draws = HashMap<String, Long>()
    private val reported = HashSet<String>()

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var readback: EGLConfig? = null
    private var pump: EGLSurface = EGL14.EGL_NO_SURFACE

    private var texture = 0
    private var reader: ImageReader? = null
    private var readerThread: HandlerThread? = null
    private var shader: StripShader? = null
    private var thread: Thread? = null
    private var stampedAtNs = 0L

    // The texture samples this buffer until the next one is bound, so the
    // frame it came from cannot be closed before then.
    private var held: Image? = null
    private var heldBuffer: HardwareBuffer? = null

    @Volatile private var running = false
    @Volatile private var frames = 0L
    @Volatile private var frameAtMs = 0L
    @Volatile private var openedAtMs = 0L

    private val arrived = Object()
    private var pending = false

    @Volatile var input: Surface? = null
        private set

    val frameCount: Long get() = frames

    val quietForMs: Long
        get() {
            val since = if (frameAtMs == 0L) openedAtMs else frameAtMs
            return if (since == 0L) 0L else System.currentTimeMillis() - since
        }

    fun start(): Surface? {
        val ready = Object()
        var failed = false
        thread = Thread({
            val opened = try {
                open()
            } catch (t: Throwable) {
                DaemonLog.e(TAG, "the graphics stack refused the camera: ${t.javaClass.simpleName}: ${t.message}")
                false
            }
            synchronized(ready) {
                failed = !opened
                ready.notifyAll()
            }
            if (opened) spin()
            shut()
        }, "framebus").also { it.start() }

        synchronized(ready) {
            while (input == null && !failed) ready.wait(5_000)
        }
        return input
    }

    fun stop() {
        running = false
        thread?.join(2_000)
        thread = null
    }

    // Only the bus thread may release a replaced consumer's EGL surface.
    fun add(consumer: Consumer) {
        consumers.removeAll { it.name == consumer.name }
        retired.add(consumer.name)
        consumers.add(consumer)
    }

    fun remove(name: String) {
        consumers.removeAll { it.name == name }
        retired.add(name)
    }

    private fun open(): Boolean {
        if (!CameraTexture.isLoaded) return false
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
            DaemonLog.e(TAG, "the graphics driver would not start")
            return false
        }
        config = chooseConfig(recordable = true) ?: return false
        readback = chooseConfig(recordable = false)
        val attributes = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, attributes, 0)
        if (context == EGL14.EGL_NO_CONTEXT) {
            DaemonLog.e(TAG, "no graphics context: ${EGL14.eglGetError()}")
            return false
        }
        pump = EGL14.eglCreatePbufferSurface(
            display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
        )
        if (!EGL14.eglMakeCurrent(display, pump, pump, context)) {
            DaemonLog.e(TAG, "cannot draw on this context: ${EGL14.eglGetError()}")
            return false
        }

        val compiled = StripShader.compile() ?: return false
        shader = compiled
        texture = compiled.newTexture()
        DaemonLog.d(TAG, "graphics: " + CameraTexture.report())

        // The callback must not land on this thread: it waits for the frame
        // the callback announces, so sharing one thread would deadlock.
        val spinner = HandlerThread("frames").also { it.start() }
        readerThread = spinner
        val fresh = newReader()
        fresh.setOnImageAvailableListener({ announce() }, Handler(spinner.looper))
        reader = fresh
        input = fresh.surface
        return true
    }

    private fun newReader(): ImageReader =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ImageReader.newInstance(
                stripWidth, stripHeight, ImageFormat.PRIVATE, POOL,
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
            )
        } else {
            ImageReader.newInstance(stripWidth, stripHeight, ImageFormat.PRIVATE, POOL)
        }

    /** RECORDABLE is required for a MediaCodec surface on Adreno. */
    private fun chooseConfig(recordable: Boolean): EGLConfig? {
        val attributes = if (recordable) {
            intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGLExt.EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
            )
        } else {
            intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE
            )
        }
        val found = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attributes, 0, found, 0, 1, count, 0) || count[0] == 0) {
            if (recordable) DaemonLog.e(TAG, "this driver has no surface the encoder can share")
            return null
        }
        return found[0]
    }

    private fun announce() {
        synchronized(arrived) {
            pending = true
            arrived.notifyAll()
        }
    }

    private fun spin() {
        var complained = false
        running = true
        openedAtMs = System.currentTimeMillis()
        while (running) {
            val arrivedNow = synchronized(arrived) {
                if (!pending) arrived.wait(FRAME_WAIT_MS)
                val had = pending
                pending = false
                had
            }
            if (!arrivedNow) {
                if (!complained && frames == 0L &&
                    System.currentTimeMillis() - openedAtMs > SILENCE_MS
                ) {
                    complained = true
                    DaemonLog.e(TAG, "the camera opened but has sent no frame in ${SILENCE_MS / 1000}s")
                }
                continue
            }
            if (bind()) {
                frames++
                frameAtMs = System.currentTimeMillis()
                paint(stamp())
            }
        }
    }

    private fun bind(): Boolean {
        val fresh = reader?.acquireLatestImage() ?: return false
        val buffer = fresh.hardwareBuffer
        if (buffer == null) {
            fresh.close()
            return false
        }
        val bound = CameraTexture.bind(buffer, texture)
        release()
        if (!bound) {
            buffer.close()
            fresh.close()
            return false
        }
        held = fresh
        heldBuffer = buffer
        return true
    }

    private fun release() {
        heldBuffer?.close()
        heldBuffer = null
        held?.close()
        held = null
    }

    // Some HAL frames have zero or repeated timestamps; use the capture clock for muxing.
    private fun stamp(): Long {
        val now = System.nanoTime()
        stampedAtNs = if (now <= stampedAtNs) stampedAtNs + 1_000L else now
        return stampedAtNs
    }

    private fun paint(timestampNs: Long) {
        val program = shader ?: return
        retire()
        for (consumer in consumers) {
            val target = targetFor(consumer) ?: continue
            if (!EGL14.eglMakeCurrent(display, target, target, context)) {
                fault(consumer.name, "cannot be drawn on")
                continue
            }
            program.draw(texture, tilesOf(consumer.view), consumer.frame)
            EGLExt.eglPresentationTimeANDROID(display, target, timestampNs)
            if (!EGL14.eglSwapBuffers(display, target)) {
                fault(consumer.name, "would not take the frame")
                continue
            }
            drew(consumer.name)
        }
    }

    private fun retire() {
        while (true) {
            val name = retired.poll() ?: return
            targets.remove(name)?.let { EGL14.eglDestroySurface(display, it) }
            draws.remove(name)
            reported.remove(name)
        }
    }

    private fun drew(name: String) {
        val count = (draws[name] ?: 0L) + 1L
        draws[name] = count
        if (count == 1L) DaemonLog.d(TAG, "$name took its first frame")
        else if (count % 240 == 0L) DaemonLog.d(TAG, "$name took $count frames")
    }

    private fun fault(name: String, what: String) {
        if (!reported.add(name)) return
        DaemonLog.e(TAG, "$name $what: ${EGL14.eglGetError()}")
    }

    private fun targetFor(consumer: Consumer): EGLSurface? {
        targets[consumer.name]?.let { return it }
        if (!consumer.surface.isValid) return null
        val made = windowOn(consumer.surface, config) ?: windowOn(consumer.surface, readback)
        if (made == null) {
            DaemonLog.e(TAG, "${consumer.name} cannot take frames: ${EGL14.eglGetError()}")
            consumers.remove(consumer)
            return null
        }
        targets[consumer.name] = made
        return made
    }

    private fun windowOn(surface: Surface, which: EGLConfig?): EGLSurface? {
        if (which == null) return null
        val made = EGL14.eglCreateWindowSurface(
            display, which, surface, intArrayOf(EGL14.EGL_NONE), 0
        )
        return if (made == null || made == EGL14.EGL_NO_SURFACE) null else made
    }

    private fun shut() {
        for (target in targets.values) EGL14.eglDestroySurface(display, target)
        targets.clear()
        shader?.release()
        shader = null
        release()
        reader?.close()
        reader = null
        readerThread?.quitSafely()
        readerThread = null
        input = null
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
            )
            if (pump != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, pump)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        pump = EGL14.EGL_NO_SURFACE
    }
}

internal fun clear() {
    GLES20.glClearColor(0f, 0f, 0f, 1f)
    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
}
