package com.strike.server.api

import com.strike.core.Pin
import com.strike.core.PinSession
import com.strike.server.cookieValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class SecurityApiTest {

    @Before
    fun reset() {
        PinSession.lock()
    }

    @Test
    fun unlockIssuesACookieThatOpensTheSession() {
        val pin = fresh()
        pin.set("2580")
        val reply = SecurityApi(pin).unlock("pin=2580")
        assertEquals(200, reply.status)
        val token = cookieValue(reply.headers["Set-Cookie"], "strike")
        assertNotNull(token)
        assertTrue(PinSession.allows(token))
    }

    @Test
    fun aWrongPinDoesNotOpenTheSession() {
        val pin = fresh()
        pin.set("2580")
        val reply = SecurityApi(pin).unlock("pin=0000")
        assertEquals(403, reply.status)
        assertFalse(PinSession.allows("anything"))
    }

    @Test
    fun setOpensASessionSoTheNextPageIsNotGated() {
        val pin = fresh()
        val reply = SecurityApi(pin).update("action=set&pin=1357&confirm=1357")
        assertEquals(200, reply.status)
        assertTrue(pin.isSet())
        val token = cookieValue(reply.headers["Set-Cookie"], "strike")
        assertTrue(PinSession.allows(token))
    }

    @Test
    fun mismatchedConfirmsAreRefused() {
        val pin = fresh()
        val reply = SecurityApi(pin).update("action=set&pin=1357&confirm=1358")
        assertEquals(400, reply.status)
        assertFalse(pin.isSet())
    }

    private fun fresh(): Pin {
        val dir = createTempDirectory("strike-pin").toFile()
        return Pin(File(dir, "pin.json"), File(dir, "reset"))
    }
}
