package com.strike.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccEdgeTest {
    @Test
    fun aParkedBroadcastIsAccepted() {
        assertTrue(shouldAcceptParked(false, 0, 0))
    }

    @Test
    fun aCarThatIsOnRejectsAParkedBroadcast() {
        assertFalse(shouldAcceptParked(true, 0, 0))
    }

    @Test
    fun accOnDuringThePowerQueryWins() {
        assertFalse(shouldAcceptParked(false, 0, 1))
    }

    @Test
    fun aBroadcastStillWorksWhenTheSdkCannotReadPower() {
        assertTrue(shouldAcceptParked(null, 4, 4))
    }
}
