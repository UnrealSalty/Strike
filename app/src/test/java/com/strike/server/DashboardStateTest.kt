package com.strike.server

import com.strike.core.Pin
import com.strike.core.PinCheck
import com.strike.online.BrowserAccess
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DashboardStateTest {
    @get:Rule val temporary = TemporaryFolder()
    private val identity = "b0ecab73-c16b-4ac8-a73d-7fc687694d42"

    @Test fun handoverPreservesThePinAccessCodeAndExistingBrowserSessions() {
        val app = temporary.newFolder("app")
        val daemon = temporary.newFolder("daemon")
        val pin = Pin(File(app, "pin.json"), File(app, "reset"))
        pin.set("2580")
        val access = BrowserAccess(File(app, "browser-access.json"))
        val code = access.code()!!
        val token = access.login(code).token!!
        importDashboard(daemon, identity, dashboardSeed(app))
        assertEquals(PinCheck.OK, Pin(File(daemon, "pin.json"), File(daemon, "reset")).check("2580"))
        val restored = BrowserAccess(File(daemon, "browser-access.json"))
        assertEquals(code, restored.code())
        assertTrue(restored.allows(token))
        assertEquals(identity, File(daemon, "identity").readText())
    }

    @Test fun aFreshInstallationCannotRestoreThePreviousPinOrBrowserSession() {
        val app = temporary.newFolder("app")
        val daemon = temporary.newFolder("daemon")
        Pin(File(daemon, "pin.json"), File(daemon, "reset")).set("2580")
        val access = BrowserAccess(File(daemon, "browser-access.json"))
        val old = access.login(access.code()!!).token!!
        val fresh = BrowserAccess(File(app, "browser-access.json"))
        importDashboard(daemon, identity, dashboardSeed(app))
        assertFalse(Pin(File(daemon, "pin.json"), File(daemon, "reset")).isSet())
        val restored = BrowserAccess(File(daemon, "browser-access.json"))
        assertEquals(fresh.code(), restored.code())
        assertFalse(restored.allows(old))
    }

    @Test fun invalidSettingsDoNotReplaceWorkingCredentials() {
        val daemon = temporary.newFolder()
        val access = BrowserAccess(File(daemon, "browser-access.json"))
        val before = access.code()
        File(daemon, "identity").writeText(identity)
        try {
            importDashboard(daemon, identity, JSONObject().put("online.json", "not json"))
            fail("Invalid settings must be rejected before writing")
        } catch (e: org.json.JSONException) {
            assertEquals(before, BrowserAccess(File(daemon, "browser-access.json")).code())
            assertEquals(identity, File(daemon, "identity").readText())
        }
    }
}
