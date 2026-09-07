package com.strike.surveillance

import com.strike.recording.RecordingSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SurveillanceSettingsTest {

    @Test
    fun modeTakesOnlySmartOrContinuous() {
        assertTrue(SurveillanceSettings.accepts(SurveillanceSettings.MODE, "smart"))
        assertTrue(SurveillanceSettings.accepts(SurveillanceSettings.MODE, "continuous"))
        assertFalse(SurveillanceSettings.accepts(SurveillanceSettings.MODE, "sentry"))
    }

    @Test
    fun armTakesOnlyTurnOffOrLock() {
        assertTrue(SurveillanceSettings.accepts(SurveillanceSettings.ARM, "off"))
        assertTrue(SurveillanceSettings.accepts(SurveillanceSettings.ARM, "lock"))
        assertFalse(SurveillanceSettings.accepts(SurveillanceSettings.ARM, "power"))
    }

    @Test
    fun proximityTakesOnlyTheFiveSteps() {
        assertTrue(SurveillanceSettings.accepts(SurveillanceSettings.PROXIMITY, "1"))
        assertTrue(SurveillanceSettings.accepts(SurveillanceSettings.PROXIMITY, "5"))
        assertFalse(SurveillanceSettings.accepts(SurveillanceSettings.PROXIMITY, "0"))
        assertFalse(SurveillanceSettings.accepts(SurveillanceSettings.PROXIMITY, "6"))
    }

    @Test
    fun anEmptyOrOverlongMessageIsRefused() {
        assertTrue(SurveillanceSettings.accepts(SurveillanceSettings.MESSAGE, "Smile."))
        assertFalse(SurveillanceSettings.accepts(SurveillanceSettings.MESSAGE, "   "))
        assertFalse(
            SurveillanceSettings.accepts(
                SurveillanceSettings.MESSAGE,
                "x".repeat(SurveillanceSettings.MESSAGE_MAX_CHARS + 1)
            )
        )
    }

    @Test
    fun budgetIsRefusedBelowTheSameFloorAsRecordings() {
        assertTrue(
            SurveillanceSettings.accepts(
                SurveillanceSettings.BUDGET_MB, RecordingSettings.BUDGET_FLOOR_MB.toString()
            )
        )
        assertFalse(
            SurveillanceSettings.accepts(
                SurveillanceSettings.BUDGET_MB, (RecordingSettings.BUDGET_FLOOR_MB - 1).toString()
            )
        )
    }

    @Test
    fun unknownKeysAreRefused() {
        assertFalse(SurveillanceSettings.accepts("surveillance.telegram", "true"))
    }

    @Test
    fun everyChoiceOffersItsOwnFallback() {
        for ((key, choice) in SurveillanceSettings.choices) {
            assertTrue(key, choice.options.contains(choice.fallback))
        }
    }

    @Test
    fun closerProximityDemandsMoreOfTheFrame() {
        var previous = movedShareFor(1)
        for (step in 2..5) {
            val share = movedShareFor(step)
            assertTrue("step $step", share < previous)
            previous = share
        }
    }

    @Test
    fun closerProximityDemandsATallerBox() {
        var previous = boxShareFor(1)
        for (step in 2..5) {
            val share = boxShareFor(step)
            assertTrue("step $step", share < previous)
            previous = share
        }
    }

    @Test
    fun anotherFeaturesBudgetComesOffTheCeiling() {
        val alone = RecordingSettings.budgetCeilingMb(
            freeMb = 60_000, totalMb = 128_000, usedMb = 2_000
        )
        val shared = RecordingSettings.budgetCeilingMb(
            freeMb = 60_000, totalMb = 128_000, usedMb = 2_000, reservedMb = 20_000
        )
        assertEquals(alone - 20_000, shared)
    }

    @Test
    fun onlyTheSameVolumeReservesAnything() {
        assertEquals(20_000, reservedOn("sd", "sd", 20_000))
        assertEquals(0, reservedOn("sd", "usb", 20_000))
    }
}
