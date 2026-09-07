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
    fun theCeilingIsWhatIsFreePlusWhatTheClipsAlreadyHold() {
        assertEquals(
            10_000 + 4_000 - RecordingSettings.BUDGET_HEADROOM_MB,
            RecordingSettings.budgetCeilingMb(freeMb = 10_000, totalMb = 64_000, usedMb = 4_000)
        )
    }

    @Test
    fun theCeilingNeverExceedsTheVolume() {
        assertEquals(
            64_000,
            RecordingSettings.budgetCeilingMb(freeMb = 64_000, totalMb = 64_000, usedMb = 8_000)
        )
    }

    @Test
    fun aFullVolumeStillOffersTheFloor() {
        assertEquals(
            RecordingSettings.BUDGET_FLOOR_MB,
            RecordingSettings.budgetCeilingMb(freeMb = 0, totalMb = 64_000, usedMb = 0)
        )
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
