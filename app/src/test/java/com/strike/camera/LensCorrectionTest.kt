package com.strike.camera

import com.strike.recording.RecordingSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LensCorrectionTest {

    @Test
    fun noCorrectionLeavesEveryPointWhereItWas() {
        val (x, y) = lensSource(LensCorrection.NONE, 0.1f, 0.9f, 4f / 3f)

        assertEquals(0.1f, x, 1e-6f)
        assertEquals(0.9f, y, 1e-6f)
    }

    @Test
    fun everyStrengthReadsOnlyFromItsOwnCamera() {
        for (correction in LensCorrection.values()) {
            for (aspect in listOf(1f, 4f / 3f, 16f / 9f)) {
                for (edge in listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f, 0.5f to 0f, 0f to 0.5f)) {
                    val (x, y) = lensSource(correction, edge.first, edge.second, aspect)
                    assertTrue("$correction at $edge", x in 0f..1f && y in 0f..1f)
                }
            }
        }
    }

    @Test
    fun strongerCorrectionPullsTheCornerFurtherIn() {
        var previous = 0f
        for (correction in LensCorrection.values()) {
            val (x, _) = lensSource(correction, 0f, 0f, 4f / 3f)
            assertTrue("$correction", x >= previous)
            previous = x
        }
        assertTrue(previous > 0f)
    }

    @Test
    fun theCentreOfEachCameraNeverMoves() {
        for (correction in LensCorrection.values()) {
            val (x, y) = lensSource(correction, 0.5f, 0.5f, 4f / 3f)
            assertEquals(0.5f, x, 1e-6f)
            assertEquals(0.5f, y, 1e-6f)
        }
    }

    @Test
    fun everySettingNamesAStrength() {
        for (id in RecordingSettings.choices.getValue(RecordingSettings.LENS).options) {
            assertEquals(id, LensCorrection.of(id).id)
        }
        assertEquals(LensCorrection.NONE, LensCorrection.of(RecordingSettings.fallback(RecordingSettings.LENS)))
    }
}
