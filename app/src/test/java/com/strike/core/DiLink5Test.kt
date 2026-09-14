package com.strike.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiLink5Test {
    @Test
    fun olderAndroidNeverProbesTheAisLibrary() {
        assertFalse(supportsDiLink5(28, true) { error("Legacy camera must not probe AIS") })
        assertFalse(supportsDiLink5(29, true) { error("Legacy camera must not probe AIS") })
    }

    @Test
    fun anEmulatorNeverProbesTheAisLibrary() {
        assertFalse(supportsDiLink5(35, false) { error("The producer is ARM64 only") })
    }

    @Test
    fun newerAndroidNeedsTheAisLibrary() {
        assertFalse(supportsDiLink5(30, true) { false })
        assertTrue(supportsDiLink5(30, true) { true })
    }
}
