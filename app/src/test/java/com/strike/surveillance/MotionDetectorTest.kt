package com.strike.surveillance

import com.strike.camera.Mosaic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val WIDTH = 64
private const val HEIGHT = 48

class MotionDetectorTest {

    @Test
    fun theFirstFrameHasNothingToCompareWith() {
        val verdict = MotionDetector().evaluate(grey(40), 0.01f)

        assertFalse(verdict.moved)
        assertEquals(0f, verdict.score, 0f)
    }

    @Test
    fun aSubjectFillingOneQuadrantMoves() {
        val detector = MotionDetector()
        detector.evaluate(grey(40), 0.5f)
        val verdict = detector.evaluate(grey(40, quadrant = 0, patch = 200), 0.5f)

        assertTrue(verdict.moved)
        assertEquals(1f, verdict.score, 0.001f)
    }

    @Test
    fun oneQuadrantIsNotAveragedAwayAcrossTheOtherThree() {
        val detector = MotionDetector()
        detector.evaluate(grey(40), 0.9f)

        assertTrue(detector.evaluate(grey(40, quadrant = 3, patch = 200), 0.9f).moved)
    }

    @Test
    fun aPatchThatFillsAnEighthOfOneCameraNeedsTheNearerSettings() {
        val patch = 0.4f

        val nearer = MotionDetector()
        nearer.evaluate(grey(40), movedShareFor(2))
        assertTrue(nearer.evaluate(grey(40, 0, 200, patch), movedShareFor(2)).moved)

        val touching = MotionDetector()
        touching.evaluate(grey(40), movedShareFor(1))
        assertFalse(touching.evaluate(grey(40, 0, 200, patch), movedShareFor(1)).moved)
    }

    @Test
    fun sensorNoiseUnderTheStepIsNotMovement() {
        val detector = MotionDetector()
        detector.evaluate(grey(40), 0.01f)
        val verdict = detector.evaluate(grey(45), 0.01f)

        assertFalse(verdict.moved)
        assertEquals(0f, verdict.score, 0f)
    }

    @Test
    fun theCamerasOwnAutoExposureIsNotMovement() {
        val detector = MotionDetector()
        detector.evaluate(grey(40), 0.01f)

        assertFalse(detector.evaluate(grey(180), 0.01f).moved)
    }

    @Test
    fun onlyTheCameraTheSubjectIsInFrontOfCountsAsMoved() {
        val detector = MotionDetector()
        detector.evaluate(grey(40), 0.5f)
        val verdict = detector.evaluate(grey(40, quadrant = 1, patch = 200), 0.5f)

        assertTrue(verdict.movedIn(1))
        assertFalse(verdict.movedIn(0))
        assertFalse(verdict.movedIn(2))
        assertFalse(verdict.movedIn(3))
    }

    @Test
    fun forgettingMakesTheNextFrameTheFirstOneAgain() {
        val detector = MotionDetector()
        detector.evaluate(grey(40), 0.01f)
        detector.forget()

        assertFalse(detector.evaluate(grey(200), 0.01f).moved)
    }

    private fun grey(level: Int, quadrant: Int = -1, patch: Int = level, share: Float = 1f): Mosaic {
        val rgba = ByteArray(WIDTH * HEIGHT * 4)
        val fromX = if (quadrant % 2 == 0) 0 else WIDTH / 2
        val fromY = if (quadrant < 2) 0 else HEIGHT / 2
        val patchWidth = (WIDTH / 2 * share).toInt()
        val patchHeight = (HEIGHT / 2 * share).toInt()
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                val inPatch = quadrant >= 0 &&
                    x >= fromX && x < fromX + patchWidth &&
                    y >= fromY && y < fromY + patchHeight
                val value = (if (inPatch) patch else level).toByte()
                val at = (y * WIDTH + x) * 4
                rgba[at] = value
                rgba[at + 1] = value
                rgba[at + 2] = value
                rgba[at + 3] = -1
            }
        }
        return Mosaic(WIDTH, HEIGHT, rgba, 0L)
    }
}
