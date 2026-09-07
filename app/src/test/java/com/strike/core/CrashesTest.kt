package com.strike.core

import org.junit.Assert.assertTrue
import org.junit.Test

class CrashesTest {

    @Test
    fun theTraceNamesTheExceptionAndTheLineItCameFrom() {
        val trace = traceOf(IllegalStateException("the encoder refused the strip"))

        assertTrue(trace.contains("IllegalStateException"))
        assertTrue(trace.contains("the encoder refused the strip"))
        assertTrue(trace.contains("at com.strike.core.CrashesTest"))
    }

    @Test
    fun aCauseIsCarriedSoTheRealFailureIsNotLost() {
        val trace = traceOf(RuntimeException("live failed", IllegalArgumentException("bad view")))

        assertTrue(trace.contains("Caused by"))
        assertTrue(trace.contains("bad view"))
    }
}
