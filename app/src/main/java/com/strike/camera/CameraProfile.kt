package com.strike.camera

import java.util.Locale

const val CAMERA_PROFILE = "camera.profile"

// Legacy AVMCamera geometry from Overdrive's CameraProfiles; Atto 2 is verified in Strike.
enum class CameraProfile(val id: String, val label: String, val camera: CameraChoice?) {
    AUTO("auto", "Automatic", null),
    ATTO_2("atto2", "Atto 2", RAW_STRIP),
    SEAL("seal_legacy", "Seal (legacy)", CameraChoice(1, "pano_h", 5120, 960)),
    ATTO_3("atto3", "Atto 3", CameraChoice(0, "pano_h", 5120, 960)),
    TANG_2022("tang_2022", "Tang 2022", CameraChoice(2, "pano_h", 5120, 720));

    companion object {
        fun of(id: String?): CameraProfile? = entries.firstOrNull { it.id == id }
    }
}

fun fallbackCamera(model: String?): CameraChoice {
    val name = model.orEmpty().lowercase(Locale.US).replace("-", "").replace("_", "").replace(" ", "")
    val profile = when {
        name.contains("atto2") -> CameraProfile.ATTO_2
        name.contains("atto3") || name.contains("yuanplus") -> CameraProfile.ATTO_3
        else -> CameraProfile.SEAL
    }
    return requireNotNull(profile.camera)
}
