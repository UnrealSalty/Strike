package com.strike.server.api

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraApiTest {

    @Test
    fun savedProfileSurvivesANewApiInstance() {
        var stored = "auto"
        val api = CameraApi({ stored }, { stored = it; true })
        val reply = api.save("profile=seal_legacy", inCar = true)
        assertEquals(200, reply.status)
        val reopened = CameraApi({ stored }, { error("Reading must not save") })
        val payload = JSONObject(String(reopened.settings(inCar = true).body))
        assertEquals("seal_legacy", payload.getString("profile"))
        assertTrue(payload.getBoolean("canManage"))
    }

    @Test
    fun aBrowserCanReadButCannotChangeTheCameraProfile() {
        var stored = "atto2"
        val api = CameraApi({ stored }, { stored = it; true })
        val payload = JSONObject(String(api.settings(inCar = false).body))
        assertEquals("atto2", payload.getString("profile"))
        assertFalse(payload.getBoolean("canManage"))
        assertEquals(403, api.save("profile=seal_legacy", inCar = false).status)
        assertEquals("atto2", stored)
    }

    @Test
    fun missingAndUnsupportedProfilesLeaveTheSavedChoiceAlone() {
        var stored = "seal_legacy"
        val api = CameraApi({ stored }, { stored = it; true })
        for (body in listOf("", "profile=", "profile=sealion7", "profile=BYD+AUTO", "profile=1")) {
            assertEquals(400, api.save(body, inCar = true).status)
            assertEquals("seal_legacy", stored)
        }
    }

    @Test
    fun aFailedWriteDoesNotReportTheProfileAsSaved() {
        val api = CameraApi({ "auto" }, { false })
        assertEquals(503, api.save("profile=seal_legacy", inCar = true).status)
        val payload = JSONObject(String(api.settings(inCar = true).body))
        assertEquals("auto", payload.getString("profile"))
    }

    @Test
    fun everyOfferedProfileCanBeSavedAndAutomaticCanBeRestored() {
        var stored = "auto"
        val api = CameraApi({ stored }, { stored = it; true })
        val profiles = JSONObject(String(api.settings(inCar = true).body)).getJSONArray("profiles")
        for (i in 0 until profiles.length()) {
            val id = profiles.getJSONObject(i).getString("value")
            assertEquals(200, api.save("profile=$id", inCar = true).status)
            assertEquals(id, stored)
        }
        assertEquals(200, api.save("profile=auto", inCar = true).status)
        assertEquals("auto", stored)
    }
}
