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

private val TAGS = arrayOf("pano_h", "pano_l", "byd_apa", "apa", "front")

/** Overdrive's tag list, for firmware with no getValidCameraTag. */
private val KNOWN_TAGS =
    listOf("front", "rear", "rvs", "rf", "dms", "face", "pano_h", "pano_l", "byd_apa", "apa")

// AIS is detected for diagnostics; capture through this API is not implemented.
private val AIS_LIBS =
    arrayOf("/vendor/lib64/libais_client.so", "/system/lib64/libais_client.so")

// app_process must load the BYD camera JNI libraries before calling bmmcamera.jar.
private val NATIVE_LIBS = arrayOf("cutils", "utils", "binder", "gui", "bmmcamera")

/** Empty means the factory never mapped the cameras, and then no tag resolves. */
private const val CAM_SORT_PROP = "vehicle.config.cam_sort"

// Camera 0 is verified on Atto 2; unnamed legacy head units default to camera 1.
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

// Hidden BYD camera APIs from bmmcamera.jar.
class CameraSource {

    private var camera: Any? = null
    private var previewing = false

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

class CameraInventory(val cameras: List<CameraChoice>, val reason: String)

fun cameraStack(): String {
    val info = classOrNull(BMM_CAMERA_INFO) != null
    val avm = classOrNull(AVM_CAMERA) != null
    val ais = AIS_LIBS.filter { File(it).isFile }
    return "BmmCameraInfo=$info AVMCamera=$avm " +
        "$CAM_SORT_PROP=${systemProperty(CAM_SORT_PROP) ?: "empty"} " +
        "ais=${if (ais.isEmpty()) "none" else ais.joinToString()}"
}

// Reuse discovery results: probing again during camera startup can wedge the HAL.
fun roadCamera(found: List<CameraChoice>, model: String? = null): CameraChoice? {
    if (found.isEmpty()) return fallbackCamera(model)
    if (found.singleOrNull() === RAW_STRIP) return RAW_STRIP
    return TAGS.firstNotNullOfOrNull { tag -> found.firstOrNull { it.tag == tag } }
}

fun cameras(profile: CameraProfile = CameraProfile.AUTO): CameraInventory {
    profile.camera?.let { return CameraInventory(listOf(it), "") }
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

// Older firmware lacks getValidCameraTag.
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
