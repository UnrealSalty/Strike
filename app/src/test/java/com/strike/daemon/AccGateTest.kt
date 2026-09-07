package com.strike.daemon

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val NOW = 1_000_000L

class AccGateTest {
    @Test
    fun theDeterrentStaysDownWhileTheCarIsOn() {
        assertTrue(accUnsafe(true, NOW, NOW))
    }

    @Test
    fun confirmedParkingAllowsTheDeterrentImmediately() {
        assertFalse(accUnsafe(false, NOW, NOW))
    }

    @Test
    fun missingReadingsDoNotPermitTheDeterrent() {
        assertTrue(accUnsafe(null, NOW, NOW))
    }

    @Test
    fun staleParkingReadingsDoNotPermitTheDeterrent() {
        assertTrue(accUnsafe(false, NOW - 20_000L, NOW))
    }
}
