package com.strike.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GateTest {

    @Test
    fun pagesBecomeTheLockScreenWhenLocked() {
        assertTrue(rewriteToLock("/", true))
        assertTrue(rewriteToLock("/recordings.html", true))
        assertFalse(rewriteToLock("/lock.html", true))
        assertFalse(rewriteToLock("/css/strike.css", true))
        assertFalse(rewriteToLock("/", false))
    }

    @Test
    fun lockAssetsAndUnlockStayReachable() {
        assertFalse(refuseWhenLocked("GET", "/lock.html", true))
        assertFalse(refuseWhenLocked("GET", "/css/tokens.css", true))
        assertFalse(refuseWhenLocked("GET", "/js/lock.js", true))
        assertFalse(refuseWhenLocked("GET", "/api/security", true))
        assertFalse(refuseWhenLocked("POST", "/api/security/unlock", true))
        assertFalse(refuseWhenLocked("GET", "/index.html", true))
    }

    @Test
    fun clipsStatusAndLiveAreHeld() {
        assertTrue(refuseWhenLocked("GET", "/api/status", true))
        assertTrue(refuseWhenLocked("GET", "/clips/drive.mp4", true))
        assertTrue(refuseWhenLocked("GET", "/thumbs/drive.jpg", true))
        assertTrue(refuseWhenLocked("GET", LIVE_STREAM_PATH, true))
        assertTrue(refuseWhenLocked("POST", "/api/security", true))
        assertFalse(refuseWhenLocked("GET", "/api/status", false))
    }

    @Test
    fun theSessionCookieIsReadByName() {
        assertEquals("abc", cookieValue("strike=abc", "strike"))
        assertEquals("abc", cookieValue("other=1; strike=abc; x=y", "strike"))
        assertNull(cookieValue("other=1", "strike"))
        assertNull(cookieValue(null, "strike"))
        assertNull(cookieValue("strike=", "strike"))
    }

    @Test
    fun theSetCookieHoldsThePath() {
        assertEquals("strike=deadbeef; Path=/; HttpOnly", sessionCookie("deadbeef"))
    }
}
