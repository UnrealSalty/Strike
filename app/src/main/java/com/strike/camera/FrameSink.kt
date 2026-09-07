package com.strike.camera

import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import com.strike.daemon.DaemonLog

private const val TAG = "FrameSink"
private const val NAME = "detector"
private const val POOL = 2

/** Small enough to read back cheaply, and it fits inside YOLO's 640 square. */
const val SINK_WIDTH = 640
const val SINK_HEIGHT = 480

/** All four angles, top row first, four bytes a pixel. */
class Mosaic(val width: Int, val height: Int, val rgba: ByteArray, val atMs: Long)

/**
 * The only CPU view of the camera. Every other consumer hands FrameBus an
 * encoder surface, so a detector that needs pixels has to bring its own
 * ImageReader and read them back off the GPU.
 *
 * The bus blocks on a full queue, so every frame is drained whether or not it
 * is wanted. Only one in [everyMs] is copied out.
 */
class FrameSink(private val everyMs: Long) {

    private val frames = Any()
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var takenAtMs = 0L
    private var warnedRead = false

    private var latest: Mosaic? = null

    fun start(bus: FrameBus) {
        if (reader != null) return
        val fresh = newReader()
        val spinner = HandlerThread("detect-frames").also { it.start() }
        fresh.setOnImageAvailableListener({ drain(fresh) }, Handler(spinner.looper))
        synchronized(frames) {
            reader = fresh
            thread = spinner
            warnedRead = false
        }
        bus.add(Consumer(NAME, fresh.surface, CameraView.ALL, Frame(SINK_WIDTH, SINK_HEIGHT)))
        DaemonLog.d(TAG, "reading back ${SINK_WIDTH}x$SINK_HEIGHT every ${everyMs}ms")
    }

    fun stop(bus: FrameBus?) {
        bus?.remove(NAME)
        synchronized(frames) {
            val held = reader
            reader = null
            held?.setOnImageAvailableListener(null, null)
            held?.close()
            thread?.quitSafely()
            thread = null
            latest = null
            takenAtMs = 0L
        }
    }

    /** The newest frame that was copied out, or null when none is fresh. */
    fun take(): Mosaic? = synchronized(frames) {
        val held = latest
        latest = null
        held
    }

    private fun drain(from: ImageReader): Unit = synchronized(frames) {
        if (reader !== from) return
        val image = try {
            from.acquireLatestImage() ?: return
        } catch (e: IllegalStateException) {
            warnRead(e)
            return
        }
        try {
            val now = System.currentTimeMillis()
            if (now - takenAtMs < everyMs) return
            takenAtMs = now
            val plane = image.planes[0]
            val buffer = plane.buffer
            val stride = plane.rowStride
            val rowBytes = SINK_WIDTH * 4
            val pixels = ByteArray(rowBytes * SINK_HEIGHT)
            var into = 0
            for (y in 0 until SINK_HEIGHT) {
                buffer.position(y * stride)
                buffer.get(pixels, into, rowBytes)
                into += rowBytes
            }
            latest = Mosaic(SINK_WIDTH, SINK_HEIGHT, pixels, now)
        } catch (e: RuntimeException) {
            warnRead(e)
        } finally {
            image.close()
        }
    }

    private fun warnRead(failure: RuntimeException) {
        if (warnedRead) return
        warnedRead = true
        DaemonLog.e(TAG, "could not read a camera frame: ${failure.message}")
    }

    /**
     * RGBA rather than PRIVATE: this is the one reader whose buffers the CPU
     * has to be able to map.
     */
    private fun newReader(): ImageReader =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ImageReader.newInstance(
                SINK_WIDTH, SINK_HEIGHT, PixelFormat.RGBA_8888, POOL,
                HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_CPU_READ_OFTEN
            )
        } else {
            ImageReader.newInstance(SINK_WIDTH, SINK_HEIGHT, PixelFormat.RGBA_8888, POOL)
        }
}
