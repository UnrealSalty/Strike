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

class OnlineSettingsTest {
    @Test fun remoteAccessDefaultsOffOnCloudflare() {
        val saved = fresh()
        assertFalse(saved.enabled)
        assertEquals("off", saved.mode)
        assertEquals(CLOUDFLARE, saved.method)
        assertFalse(saved.configured)
    }

    @Test fun savingAndReloadingKeepsTheSecretAndSchedule() {
        val file = File(createTempDirectory().toFile(), "online.json")
        val saved = OnlineSettings(file)
        saved.configure(CLOUDFLARE, "https://Car.Example.com/", sampleTunnelToken(), "lock")
        saved.enable(true)
        val loaded = OnlineSettings(file)
        assertTrue(loaded.enabled)
        assertEquals("car.example.com", loaded.name(CLOUDFLARE))
        assertEquals("lock", loaded.mode)
        assertEquals(sampleTunnelToken(), loaded.secret(CLOUDFLARE))
    }

    @Test fun settingsWrittenBeforeTheServiceChoiceStillStartCloudflare() {
        val file = File(createTempDirectory().toFile(), "online.json")
        file.writeText(JSONObject().put("enabled", true).put("mode", "always")
            .put("hostname", "car.example.com").put("token", sampleTunnelToken()).toString())
        val loaded = OnlineSettings(file)
        assertEquals(CLOUDFLARE, loaded.method)
        assertTrue(loaded.enabled)
        assertEquals("car.example.com", loaded.name(CLOUDFLARE))
        assertEquals(sampleTunnelToken(), loaded.secret(CLOUDFLARE))
    }

    @Test fun blankSecretKeepsTheExistingOne() {
        val saved = fresh()
        saved.configure(CLOUDFLARE, "car.example.com", sampleTunnelToken(), "off")
        saved.configure(CLOUDFLARE, "strike.example.com", "", "always")
        assertEquals(sampleTunnelToken(), saved.secret(CLOUDFLARE))
        assertEquals("strike.example.com", saved.name(CLOUDFLARE))
    }

    @Test fun invalidSaveDoesNotReplaceWorkingSettings() {
        val saved = fresh()
        saved.configure(CLOUDFLARE, "car.example.com", sampleTunnelToken(), "off")
        for (host in listOf("", "http://car.example.com", "https://car.example.com/path",
            "user@car.example.com", "car.example.com:8090", "127.0.0.1", "car.example.com?x=1")) {
            try { saved.configure(CLOUDFLARE, host, sampleTunnelToken(), "lock"); fail(host) }
            catch (e: IllegalArgumentException) { assertEquals("car.example.com", saved.name(CLOUDFLARE)) }
        }
        try { saved.configure(CLOUDFLARE, "car.example.com", "not-a-token", "off"); fail() }
        catch (e: IllegalArgumentException) { assertEquals(sampleTunnelToken(), saved.secret(CLOUDFLARE)) }
    }

    @Test fun eachServiceCarriesItsOwnSetupAndOneScheduleIsShared() {
        val file = File(createTempDirectory().toFile(), "online.json")
        val saved = OnlineSettings(file)
        saved.configure(CLOUDFLARE, "car.example.com", sampleTunnelToken(), "off")
        saved.configure(ZROK, "strikecar", "zrok-account-token", "lock")
        val loaded = OnlineSettings(file)
        assertEquals(ZROK, loaded.method)
        assertEquals("lock", loaded.mode)
        assertTrue(loaded.isConfigured(CLOUDFLARE))
        assertTrue(loaded.isConfigured(ZROK))
        loaded.select(CLOUDFLARE)
        assertEquals("car.example.com", loaded.name(CLOUDFLARE))
        assertEquals("lock", loaded.mode)
    }

    @Test fun zrokNeedsANameAndAToken() {
        val saved = fresh()
        for (name in listOf("", "ab", "Not A Name", "a".repeat(33))) {
            try { saved.configure(ZROK, name, "token", "off"); fail(name) }
            catch (e: IllegalArgumentException) { assertFalse(saved.isConfigured(ZROK)) }
        }
        saved.configure(ZROK, "StrikeCar", "token", "off")
        assertEquals("strikecar", saved.name(ZROK))
    }

    @Test fun removingSetupClearsOnlyTheChosenService() {
        val saved = fresh()
        saved.configure(CLOUDFLARE, "car.example.com", sampleTunnelToken(), "always")
        saved.configure(ZROK, "strikecar", "zrok-account-token", "always")
        saved.enable(true)
        saved.forget()
        assertFalse(saved.enabled)
        assertFalse(saved.isConfigured(ZROK))
        assertTrue(saved.isConfigured(CLOUDFLARE))
    }

    @Test fun unreadableSettingsDoNotPreventTheAppStarting() {
        val file = File(createTempDirectory().toFile(), "online.json")
        file.writeText("{broken")
        assertFalse(OnlineSettings(file).enabled)
    }

    private fun fresh() = OnlineSettings(File(createTempDirectory().toFile(), "online.json"))
}
