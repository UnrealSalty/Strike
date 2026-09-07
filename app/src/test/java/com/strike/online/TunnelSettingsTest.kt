package com.strike.online

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Base64
import kotlin.io.path.createTempDirectory

internal fun sampleTunnelToken(): String = Base64.getEncoder().encodeToString(
    JSONObject().put("a", "test-account").put("t", "test-tunnel")
        .put("s", "test-secret-not-a-credential").toString().toByteArray())

class TunnelSettingsTest {
    @Test fun remoteAccessDefaultsOff() {
        val saved = fresh()
        assertFalse(saved.enabled)
        assertEquals("off", saved.mode)
        assertEquals("", saved.token)
    }

    @Test fun savingAndReloadingKeepsTheTokenAndSchedule() {
        val file = File(createTempDirectory().toFile(), "online.json")
        val saved = TunnelSettings(file)
        saved.configure("https://Car.Example.com/", sampleTunnelToken(), "lock")
        saved.enable(true)
        val loaded = TunnelSettings(file)
        assertTrue(loaded.enabled)
        assertEquals("car.example.com", loaded.hostname)
        assertEquals("lock", loaded.mode)
        assertEquals(sampleTunnelToken(), loaded.token)
    }

    @Test fun blankTokenKeepsTheExistingSecret() {
        val saved = fresh()
        saved.configure("car.example.com", sampleTunnelToken(), "off")
        saved.configure("strike.example.com", "", "always")
        assertEquals(sampleTunnelToken(), saved.token)
        assertEquals("strike.example.com", saved.hostname)
    }

    @Test fun invalidSaveDoesNotReplaceWorkingSettings() {
        val saved = fresh()
        saved.configure("car.example.com", sampleTunnelToken(), "off")
        for (host in listOf("", "http://car.example.com", "https://car.example.com/path",
            "user@car.example.com", "car.example.com:8090", "127.0.0.1", "car.example.com?x=1")) {
            try { saved.configure(host, sampleTunnelToken(), "lock"); fail(host) }
            catch (e: IllegalArgumentException) { assertEquals("car.example.com", saved.hostname) }
        }
        try { saved.configure("car.example.com", "not-a-token", "off"); fail() }
        catch (e: IllegalArgumentException) { assertEquals(sampleTunnelToken(), saved.token) }
    }

    @Test fun removingSetupClearsTheSecretAndDisablesStartup() {
        val saved = fresh()
        saved.configure("car.example.com", sampleTunnelToken(), "always")
        saved.enable(true)
        saved.forget()
        assertFalse(saved.enabled)
        assertEquals("", saved.token)
        assertEquals("", saved.hostname)
    }

    @Test fun unreadableSettingsDoNotPreventTheAppStarting() {
        val file = File(createTempDirectory().toFile(), "online.json")
        file.writeText("{broken")
        assertFalse(TunnelSettings(file).enabled)
    }

    private fun fresh() = TunnelSettings(File(createTempDirectory().toFile(), "online.json"))
}
