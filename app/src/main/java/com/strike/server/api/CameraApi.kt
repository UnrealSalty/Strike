package com.strike.server.api

import com.strike.camera.CameraProfile
import com.strike.server.JSON
import com.strike.server.Response
import com.strike.server.TEXT
import com.strike.server.formValue
import org.json.JSONArray
import org.json.JSONObject

class CameraApi(private val readProfile: () -> String, private val saveProfile: (String) -> Boolean) {

    fun settings(inCar: Boolean): Response {
        val profile = CameraProfile.of(readProfile()) ?: CameraProfile.AUTO
        val profiles = JSONArray()
        for (option in CameraProfile.entries) {
            profiles.put(JSONObject().put("value", option.id).put("label", option.label))
        }
        val payload = JSONObject()
            .put("profile", profile.id)
            .put("profiles", profiles)
            .put("canManage", inCar)
        return Response(200, JSON, payload.toString().toByteArray())
    }

    fun save(body: String, inCar: Boolean): Response {
        if (!inCar) return Response(403, TEXT, "Edit camera settings from the car".toByteArray())
        val profile = CameraProfile.of(formValue(body, "profile"))
            ?: return Response(400, TEXT, "Choose a camera profile".toByteArray())
        if (!saveProfile(profile.id)) {
            return Response(503, TEXT, "Could not save the camera profile. Check shell access.".toByteArray())
        }
        return settings(inCar)
    }
}
