package com.strike.camera

import android.view.Surface
import com.strike.core.systemProperty
import com.strike.daemon.DaemonLog
import java.io.File

private const val TAG = "Camera"
private const val AVM_CAMERA = "android.hardware.AVMCamera"
private const val BMM_CAMERA_INFO = "android.hardware.BmmCameraInfo"

/** The firmware calls the surface slot a mode going in and a channel coming out. */
private const val PREVIEW_CHANNEL = 0

/**
 * The panoramic strip first: it carries all four angles in one frame, which is
 * the only way to offer a view other than the road ahead. Overdrive's cascade,
 * with its plain front camera as the fallback for cars without the 360 system.
 */
private val TAGS = arrayOf("pano_h", "pano_l", "byd_apa", "apa", "front")

/** Overdrive's tag list, for firmware with no getValidCameraTag. */
private val KNOWN_TAGS =
    listOf("front", "rear", "rvs", "rf", "dms", "face", "pano_h", "pano_l", "byd_apa", "apa")

/** Where the DiLink 5 camera lives, which is a different stack entirely. */
private val AIS_LIBS =
    arrayOf("/vendor/lib64/libais_client.so", "/system/lib64/libais_client.so")

/**
 * bmmcamera.jar is a thin wrapper over libbmmcamera.so, and app_process does
 * not pull JNI in on its own. Overdrive's daemon loads the same five, in this
 * order, before it touches a camera.
 */
private val NATIVE_LIBS = arrayOf("cutils", "utils", "binder", "gui", "bmmcamera")

/** Empty means the factory never mapped the cameras, and then no tag resolves. */
private const val CAM_SORT_PROP = "vehicle.config.cam_sort"

/**
 * Camera 0 carries the four fisheyes side by side and needs no panoramic wake,
 * so it answers on boards where BmmCameraInfo names nothing. Overdrive treats
 * it as the escape hatch and reads it at this size on the Seal and Atto strip.
 */
val RAW_STRIP = CameraChoice(id = 0, tag = "strip", width = 5120, height = 960)

fun loadCameraLibraries() {
    for (name in NATIVE_LIBS) {
        try {
            System.loadLibrary(name)
        } catch (t: Throwable) {
            DaemonLog.w(TAG, "lib$name.so did not load: ${t.javaClass.simpleName}")
        }
    }
}

/**
 * Nothing here is public Android. The classes live in bmmcamera.jar on the
 * boot image and the methods are hidden, so every call is reflection and every
 * failure is a fact about this firmware rather than a bug.
 */
class CameraSource {

    private var camera: Any? = null
    private var previewing = false

    /**
     * The HAL writes straight into the encoder's input surface. Overdrive puts
     * GL in between because it crops a 5120 wide mosaic; one camera needs no
     * compositor.
     */
    fun open(choice: CameraChoice, frameRateFps: Int, target: Surface): Boolean {
        val avm = classOrNull(AVM_CAMERA) ?: return fail("the car's camera library is not on this firmware")
        return try {
            val opened = avm.getDeclaredConstructor(Int::class.javaPrimitiveType)
                .also { it.isAccessible = true }
                .newInstance(choice.id)
            if (call(avm, opened, "open") != true) return fail("camera ${choice.id} refused to open")
            camera = opened
            call(avm, opened, "setCameraFps", Int::class.javaPrimitiveType to frameRateFps)
            avm.getDeclaredMethod("addPreviewSurface", Surface::class.java, Int::class.javaPrimitiveType)
                .also { it.isAccessible = true }
                .invoke(opened, target, PREVIEW_CHANNEL)
            call(avm, opened, "startPreview")
            previewing = true
            true
        } catch (t: Throwable) {
            close()
            fail("camera ${choice.id} failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** Always in this order, or the next open finds the camera still held. */
    fun close() {
        val held = camera ?: return
        val avm = classOrNull(AVM_CAMERA)
        if (avm != null) {
            call(avm, held, "disablePreviewCallback", Int::class.javaPrimitiveType to PREVIEW_CHANNEL)
            if (previewing) call(avm, held, "stopPreview")
            call(avm, held, "close")
        }
        camera = null
        previewing = false
    }

    private fun call(avm: Class<*>, target: Any, name: String, argument: Pair<Class<*>?, Any>? = null): Any? =
        try {
            if (argument == null) {
                avm.getDeclaredMethod(name).also { it.isAccessible = true }.invoke(target)
            } else {
                avm.getDeclaredMethod(name, argument.first)
                    .also { it.isAccessible = true }
                    .invoke(target, argument.second)
            }
        } catch (t: Throwable) {
            DaemonLog.w(TAG, "$name failed: ${t.javaClass.simpleName}")
            null
        }

    private fun fail(reason: String): Boolean {
        DaemonLog.e(TAG, reason)
        return false
    }
}

class CameraChoice(val id: Int, val tag: String, val width: Int, val height: Int)

/** Empty is an answer, so it comes with the reason the firmware gave. */
class CameraInventory(val cameras: List<CameraChoice>, val reason: String)

/**
 * Which camera stack this firmware has. DiLink 3 and 4 answer through
 * bmmcamera.jar; DiLink 5 has neither class and reaches the cameras through
 * Qualcomm's AIS client library instead, which Strike cannot drive yet.
 */
fun cameraStack(): String {
    val info = classOrNull(BMM_CAMERA_INFO) != null
    val avm = classOrNull(AVM_CAMERA) != null
    val ais = AIS_LIBS.filter { File(it).isFile }
    return "BmmCameraInfo=$info AVMCamera=$avm " +
        "$CAM_SORT_PROP=${systemProperty(CAM_SORT_PROP) ?: "empty"} " +
        "ais=${if (ais.isEmpty()) "none" else ais.joinToString()}"
}

/**
 * Which of the cameras already discovered to record from. Takes the list
 * rather than probing, because asking the firmware again on the path that
 * opens the camera is what wedges the HAL.
 */
fun roadCamera(found: List<CameraChoice>): CameraChoice =
    TAGS.firstNotNullOfOrNull { tag -> found.firstOrNull { it.tag == tag } }
        ?: found.firstOrNull()
        ?: RAW_STRIP

/**
 * Every camera this firmware admits to, in the order it names them. Which
 * angles exist is a property of the car's trim, so it is discovered rather
 * than assumed: a panoramic strip carries four views, a plain front camera one.
 */
fun cameras(): CameraInventory {
    val bmm = classOrNull(BMM_CAMERA_INFO)
        ?: return CameraInventory(emptyList(), "this firmware has no camera library Strike can drive")
    val tags = validTags(bmm)
    val found = ArrayList<CameraChoice>()
    val refused = ArrayList<String>()
    for (tag in tags) {
        val id = cameraId(bmm, tag)
        if (id == null) {
            refused.add(tag)
            continue
        }
        val width = size(bmm, "getDefaultPreviewWidth", id)
        val height = size(bmm, "getDefaultPreviewHeight", id)
        if (width == null || height == null || width <= 0 || height <= 0) {
            refused.add("$tag(id $id, no size)")
            continue
        }
        found.add(CameraChoice(id, tag, width, height))
    }
    val reason = if (found.isNotEmpty()) {
        ""
    } else {
        "the camera library named none of ${tags.size} cameras: ${refused.joinToString()}"
    }
    return CameraInventory(found, reason)
}

// getValidCameraTag is the firmware's own list. Older builds lack it, so the
// names Strike already knows about are tried one by one instead.
private fun validTags(bmm: Class<*>): List<String> {
    val answered = try {
        bmm.getDeclaredMethod("getValidCameraTag").also { it.isAccessible = true }.invoke(null)
    } catch (t: Throwable) {
        null
    }
    val tags = when (answered) {
        is Array<*> -> answered.mapNotNull { it as? String }
        is Collection<*> -> answered.mapNotNull { it as? String }
        else -> emptyList()
    }
    return if (tags.isNotEmpty()) tags else KNOWN_TAGS
}

private fun cameraId(bmm: Class<*>, tag: String): Int? {
    val id = try {
        bmm.getDeclaredMethod("getCameraId", String::class.java)
            .also { it.isAccessible = true }
            .invoke(null, tag)
    } catch (t: Throwable) {
        null
    }
    return if (id is Int && id >= 0) id else null
}

private fun size(bmm: Class<*>, getter: String, id: Int): Int? {
    val value = try {
        bmm.getDeclaredMethod(getter, Int::class.javaPrimitiveType)
            .also { it.isAccessible = true }
            .invoke(null, id)
    } catch (t: Throwable) {
        null
    }
    return value as? Int
}

private fun classOrNull(name: String): Class<*>? = try {
    Class.forName(name)
} catch (e: ClassNotFoundException) {
    null
}
