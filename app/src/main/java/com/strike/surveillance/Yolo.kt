package com.strike.surveillance

import com.strike.camera.Mosaic
import com.strike.daemon.DaemonLog
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.zip.ZipFile

private const val TAG = "Yolo"
private const val ASSET = "assets/models/yolo26n.tflite"

/** Ultralytics pads with this grey, so the model has seen it. */
private const val PAD = 114f / 255f

/** Rows the end-to-end head emits, each x1,y1,x2,y2,score,class. */
private const val ROW = 6

private const val CONFIDENCE = 0.25f
private const val THREADS = 2

private const val COCO_PERSON = 0
private val COCO_VEHICLE = intArrayOf(2, 5, 7)

class Sighting(
    val seen: String,
    val score: Float,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
)

/**
 * yolo26n, the end-to-end export Overdrive already runs on this head unit:
 * input [1,640,640,3] float, one output of [1,D,6] rows already resolved by
 * NMS inside the graph, in 640-pixel units.
 *
 * CPU only. Overdrive measured the GPU delegate starving the H.265 encoder on
 * this SoC, which showed up as freeze and skip in the clips.
 */
class Yolo(private val apkPath: String?) {

    @Volatile
    private var interpreter: Interpreter? = null
    private var input: ByteBuffer? = null
    private var output: ByteBuffer? = null
    private var rows = 0
    private var side = 0

    val isOpen: Boolean get() = interpreter != null

    fun open(): Boolean {
        if (interpreter != null) return true
        // app_process does not pull JNI in on its own, the same as bmmcamera.
        try {
            System.loadLibrary("tensorflowlite_jni")
        } catch (e: UnsatisfiedLinkError) {
            DaemonLog.e(TAG, "the detector library is not on this build: ${e.message}")
            return false
        }
        val model = readModel() ?: return false
        var fresh: Interpreter? = null
        try {
            fresh = Interpreter(model, Interpreter.Options().setNumThreads(THREADS))
            if (!describes(fresh)) {
                fresh.close()
                return false
            }
            input = ByteBuffer.allocateDirect(side * side * 3 * 4).order(ByteOrder.nativeOrder())
            output = ByteBuffer.allocateDirect(rows * ROW * 4).order(ByteOrder.nativeOrder())
            interpreter = fresh
        } catch (e: RuntimeException) {
            fresh?.close()
            input = null
            output = null
            throw e
        }
        DaemonLog.d(TAG, "detector ready, ${side}px, $rows boxes a frame on $THREADS threads")
        return true
    }

    /**
     * A head this code cannot read must say so rather than parse noise: a
     * misread box reads downstream as nothing seen, on every real event.
     */
    private fun describes(fresh: Interpreter): Boolean {
        val outputTensor = fresh.getOutputTensor(0)
        val shape = outputTensor.shape()
        if (shape.size != 3 || shape[2] != ROW || shape[1] <= 0) {
            DaemonLog.e(TAG, "the model has an output this code cannot read: ${shape.joinToString("x")}")
            return false
        }
        val inputTensor = fresh.getInputTensor(0)
        val inputShape = inputTensor.shape()
        if (inputShape.size != 4 || inputShape[1] != inputShape[2] || inputShape[3] != 3) {
            DaemonLog.e(TAG, "the model wants an input this code cannot fill: ${inputShape.joinToString("x")}")
            return false
        }
        if (inputTensor.dataType() != DataType.FLOAT32 || outputTensor.dataType() != DataType.FLOAT32) {
            DaemonLog.e(
                TAG,
                "the model is ${inputTensor.dataType()} in and ${outputTensor.dataType()} out, not float"
            )
            return false
        }
        rows = shape[1]
        side = inputShape[1]
        return true
    }

    fun close() {
        interpreter?.close()
        interpreter = null
        input = null
        output = null
    }

    /** [minShare] measures a subject against one camera's quadrant of the mosaic. */
    fun look(mosaic: Mosaic, minShare: Float): List<Sighting> {
        val held = interpreter ?: return emptyList()
        val pixels = input ?: return emptyList()
        val boxes = output ?: return emptyList()
        val padX = (side - mosaic.width) / 2
        val padY = (side - mosaic.height) / 2
        if (padX < 0 || padY < 0) return emptyList()

        fill(pixels, mosaic, padX, padY)
        boxes.rewind()
        held.run(pixels, boxes)
        boxes.rewind()
        return sightings(boxes.asFloatBuffer(), mosaic, padX, padY, minShare)
    }

    private fun fill(pixels: ByteBuffer, mosaic: Mosaic, padX: Int, padY: Int) {
        pixels.rewind()
        val floats = pixels.asFloatBuffer()
        var at = 0
        for (y in 0 until side) {
            val inside = y >= padY && y < padY + mosaic.height
            for (x in 0 until side) {
                if (!inside || x < padX || x >= padX + mosaic.width) {
                    floats.put(at, PAD)
                    floats.put(at + 1, PAD)
                    floats.put(at + 2, PAD)
                } else {
                    val from = ((y - padY) * mosaic.width + (x - padX)) * 4
                    floats.put(at, (mosaic.rgba[from].toInt() and 0xFF) / 255f)
                    floats.put(at + 1, (mosaic.rgba[from + 1].toInt() and 0xFF) / 255f)
                    floats.put(at + 2, (mosaic.rgba[from + 2].toInt() and 0xFF) / 255f)
                }
                at += 3
            }
        }
    }

    /**
     * One quadrant is the reference frame, not the whole mosaic: a person
     * standing at the front camera fills a quarter of the picture at most.
     */
    private fun sightings(
        boxes: FloatBuffer,
        mosaic: Mosaic,
        padX: Int,
        padY: Int,
        minShare: Float
    ): List<Sighting> {
        val quadrantWidth = mosaic.width / 2f
        val quadrantHeight = mosaic.height / 2f
        val units = unitsOf(boxes)
        val sightings = ArrayList<Sighting>()
        for (row in 0 until rows) {
            val at = row * ROW
            val score = boxes.get(at + 4)
            if (score < CONFIDENCE) continue
            val seen = named(Math.round(boxes.get(at + 5))) ?: continue
            val left = boxes.get(at) * units - padX
            val top = boxes.get(at + 1) * units - padY
            val width = (boxes.get(at + 2) - boxes.get(at)) * units
            val height = (boxes.get(at + 3) - boxes.get(at + 1)) * units
            val share =
                if (seen == PERSON) height / quadrantHeight else width / quadrantWidth
            if (share < minShare) continue
            val next = Sighting(
                seen = seen,
                score = score,
                x = clamp(left, mosaic.width),
                y = clamp(top, mosaic.height),
                width = clamp(width, mosaic.width),
                height = clamp(height, mosaic.height)
            )
            sightings.add(next)
        }
        return sightings
    }

    /**
     * Ultralytics end-to-end exports have shipped both 0..1 and input-pixel
     * corners. Decided for the whole tensor, from rows that pass confidence,
     * because a sub-2px pixel box would read as a normalised one and become a
     * phantom the size of the frame. Verified in Overdrive, whose detector
     * probes the same way and measures pixels from this asset.
     */
    private fun unitsOf(boxes: FloatBuffer): Float {
        for (row in 0 until rows) {
            val at = row * ROW
            if (boxes.get(at + 4) < CONFIDENCE) continue
            if (boxes.get(at + 2) > 1.5f || boxes.get(at + 3) > 1.5f) return 1f
        }
        return side.toFloat()
    }

    private fun named(classId: Int): String? = when {
        classId == COCO_PERSON -> PERSON
        COCO_VEHICLE.contains(classId) -> VEHICLE
        else -> null
    }

    private fun clamp(value: Float, limit: Int): Int =
        Math.max(0, Math.min(limit, Math.round(value)))

    /**
     * The daemon has no Context and so no asset manager, but the apk is on its
     * classpath and world readable, so the model comes out of the zip.
     */
    private fun readModel(): ByteBuffer? {
        val path = apkPath
        if (path == null) {
            DaemonLog.e(TAG, "the daemon was started without the apk path, so the model cannot be read")
            return null
        }
        return try {
            ZipFile(path).use { apk ->
                val entry = apk.getEntry(ASSET)
                if (entry == null) {
                    DaemonLog.e(TAG, "$ASSET is not in the apk")
                    return null
                }
                val model = ByteBuffer.allocateDirect(entry.size.toInt()).order(ByteOrder.nativeOrder())
                apk.getInputStream(entry).use { stream ->
                    val chunk = ByteArray(64 * 1024)
                    while (true) {
                        val read = stream.read(chunk)
                        if (read < 0) break
                        model.put(chunk, 0, read)
                    }
                }
                model.rewind()
                model
            }
        } catch (e: IOException) {
            DaemonLog.e(TAG, "cannot read the model out of the apk: ${e.message}")
            null
        }
    }
}

