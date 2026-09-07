package com.strike.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PinSessionTest {

    @Before
    fun reset() {
        PinSession.lock()
    }

    @Test
    fun aFreshProcessHasNoSession() {
        assertFalse(PinSession.allows(null))
        assertFalse(PinSession.allows("nope"))
    }

    @Test
    fun unlockIssuesATokenThatWorksUntilLock() {
        val token = PinSession.unlock()
        assertTrue(PinSession.allows(token))
        PinSession.lock()
        assertFalse(PinSession.allows(token))
    }

    @Test
    fun twoClientsDoNotShareAToken() {
        val car = PinSession.unlock()
        val phone = PinSession.unlock()
        assertNotEquals(car, phone)
        assertTrue(PinSession.allows(car))
        assertTrue(PinSession.allows(phone))
        PinSession.lock()
        assertFalse(PinSession.allows(car))
        assertFalse(PinSession.allows(phone))
    }
}
