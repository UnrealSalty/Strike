package com.strike.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingSettingsTest {

    @Test
    fun clipLengthTakesOnlyTheRotationsTheRecorderSupports() {
        assertTrue(RecordingSettings.accepts("recording.clipLengthMinutes", "5"))
        assertFalse(RecordingSettings.accepts("recording.clipLengthMinutes", "7"))
    }

    @Test
    fun qualityTakesOnlyTheNamedTiers() {
        assertTrue(RecordingSettings.accepts("recording.quality", "premium"))
        assertFalse(RecordingSettings.accepts("recording.quality", "ultra"))
    }

    @Test
    fun budgetIsRefusedBelowTheFloor() {
        assertTrue(RecordingSettings.accepts(RecordingSettings.BUDGET_MB, "100"))
        assertFalse(RecordingSettings.accepts(RecordingSettings.BUDGET_MB, "99"))
        assertFalse(RecordingSettings.accepts(RecordingSettings.BUDGET_MB, "lots"))
    }

    @Test
    fun audioTakesOnlyBooleans() {
        assertTrue(RecordingSettings.accepts(RecordingSettings.AUDIO, "true"))
        assertFalse(RecordingSettings.accepts(RecordingSettings.AUDIO, "yes"))
    }

    @Test
    fun unknownKeysAreRefused() {
        assertFalse(RecordingSettings.accepts("recording.bitrate", "4000000"))
    }

    @Test
    fun everyChoiceOffersItsOwnFallback() {
        for ((key, choice) in RecordingSettings.choices) {
            assertTrue(key, choice.options.contains(choice.fallback))
        }
    }

    @Test
    fun aVolumeToItselfOffersEverythingButTheHeadroom() {
        assertEquals(64_000 - RecordingSettings.BUDGET_HEADROOM_MB, RecordingSettings.budgetCeilingMb(64_000, 0))
    }

    @Test
    fun fiftyGigabytesOfRecordingLeaveSurveillanceTheRestOfAHundredGigabyteCard() {
        val ceilingMb = RecordingSettings.budgetCeilingMb(totalMb = 100_000, sharedMb = 50_000)

        assertEquals(50_000 - RecordingSettings.BUDGET_HEADROOM_MB, ceilingMb)
        assertFalse(RecordingSettings.budgetFits(100_000, currentMb = 500, ceilingMb = ceilingMb))
        assertTrue(RecordingSettings.budgetFits(ceilingMb, currentMb = 500, ceilingMb = ceilingMb))
    }

    @Test
    fun aBudgetAlreadyOverTheCeilingCanStayOrComeDownButNotGrow() {
        assertTrue(RecordingSettings.budgetFits(60_000, currentMb = 60_000, ceilingMb = 40_000))
        assertTrue(RecordingSettings.budgetFits(50_000, currentMb = 60_000, ceilingMb = 40_000))
        assertFalse(RecordingSettings.budgetFits(60_100, currentMb = 60_000, ceilingMb = 40_000))
    }

    @Test
    fun aTinyVolumeStillOffersTheFloor() {
        assertEquals(RecordingSettings.BUDGET_FLOOR_MB, RecordingSettings.budgetCeilingMb(200, 0))
    }

    @Test
    fun aVolumeWithLessRoomThanTheFloorIsNotUsable() {
        assertFalse(RecordingSettings.hasRoom(freeMb = 0, totalMb = 64_000, usedMb = 0))
        assertFalse(RecordingSettings.hasRoom(freeMb = 300, totalMb = 64_000, usedMb = 0))
        assertTrue(RecordingSettings.hasRoom(freeMb = 356, totalMb = 64_000, usedMb = 0))
    }

    @Test
    fun clipsAlreadyOnTheVolumeCountAsRoom() {
        assertTrue(RecordingSettings.hasRoom(freeMb = 0, totalMb = 64_000, usedMb = 4_000))
    }
}
