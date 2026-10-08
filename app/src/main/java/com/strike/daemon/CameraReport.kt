package com.strike.daemon

import com.strike.camera.CameraChoice
import com.strike.camera.CameraProfile
import com.strike.camera.cameraStack
import org.json.JSONArray
import org.json.JSONObject

// Built from the cached probe only; a second probe while capture starts can wedge the HAL.
internal fun cameraReport(
    profile: CameraProfile,
    cameras: JSONArray,
    probeReason: String?,
    road: CameraChoice?
): JSONObject {
    val report = JSONObject()
    report.put("status", "ok")
    report.put("profile", profile.label)
    report.put("cameras", cameras)
    report.put("probed", probeReason != null)
    if (!probeReason.isNullOrEmpty()) report.put("probeReason", probeReason)
    if (road != null) report.put("road", "${road.tag} id=${road.id} ${road.width}x${road.height}")
    report.put("stack", cameraStack())
    return report
}
