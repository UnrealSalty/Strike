package com.strike.surveillance

import com.strike.camera.Mosaic
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private const val WIDTH = 64
private const val HEIGHT = 48

class SentrySightingTest {

    @Test
    fun someoneWalkingInTheirOwnBoxCounts() {
        val detector = MotionDetector()
        detector.evaluate(still(), 0.01f)
        val verdict = detector.evaluate(patch(4, 4, 8, 8, 200), 0.01f)

        assertTrue(verdict.movedInBox(4, 4, 8, 8, WIDTH, HEIGHT, boxMovedShare(PERSON)))
    }

    @Test
    fun aParkedCarIsIgnoredWhenTheMotionIsBesideIt() {
        val detector = MotionDetector()
        detector.evaluate(still(), 0.01f)
        val verdict = detector.evaluate(patch(4, 4, 8, 8, 200), 0.01f)

        assertFalse(verdict.movedInBox(24, 4, 36, 20, WIDTH, HEIGHT, boxMovedShare(VEHICLE)))
    }

    @Test
    fun aPassingCarIsDetectedBesideAHigherScoringParkedCar() {
        val scene = Scene()
        val parked = Sighting(VEHICLE, 0.95f, 24, 4, 36, 20)
        scene.observe(listOf(parked), quiet = true)
        scene.observe(listOf(parked), quiet = true)
        val passing = Sighting(VEHICLE, 0.6f, 4, 4, 8, 8)

        assertSame(passing, movingSighting(listOf(parked, passing), scene, movement(), WIDTH, HEIGHT))
    }

    @Test
    fun aPassingCarIsDetectedEvenWhenAPersonIsStandingStillElsewhere() {
        val person = Sighting(PERSON, 0.9f, 40, 4, 8, 16)
        val passing = Sighting(VEHICLE, 0.6f, 4, 4, 8, 8)

        assertSame(passing, movingSighting(listOf(person, passing), Scene(), movement(), WIDTH, HEIGHT))
    }

    @Test
    fun theMovingPersonIsDetectedWhenAHigherScoringPersonIsStill() {
        val still = Sighting(PERSON, 0.9f, 40, 4, 8, 16)
        val walking = Sighting(PERSON, 0.6f, 4, 4, 8, 8)

        assertSame(walking, movingSighting(listOf(still, walking), Scene(), movement(), WIDTH, HEIGHT))
    }

    @Test
    fun aMovingPersonWinsOverAHigherScoringMovingCar() {
        val person = Sighting(PERSON, 0.4f, 4, 4, 8, 8)
        val vehicle = Sighting(VEHICLE, 0.9f, 4, 4, 12, 8)

        assertSame(person, movingSighting(listOf(vehicle, person), Scene(), movement(), WIDTH, HEIGHT))
    }

    private fun movement(): Verdict {
        val detector = MotionDetector()
        detector.evaluate(still(), 0.01f)
        return detector.evaluate(patch(4, 4, 8, 8, 200), 0.01f)
    }

    private fun still(): Mosaic = patch(0, 0, 0, 0, 40)

    private fun patch(x: Int, y: Int, width: Int, height: Int, value: Int): Mosaic {
        val rgba = ByteArray(WIDTH * HEIGHT * 4)
        for (row in 0 until HEIGHT) {
            for (column in 0 until WIDTH) {
                val inPatch = width > 0 &&
                    column >= x && column < x + width &&
                    row >= y && row < y + height
                val level = (if (inPatch) value else 40).toByte()
                val at = (row * WIDTH + column) * 4
                rgba[at] = level
                rgba[at + 1] = level
                rgba[at + 2] = level
                rgba[at + 3] = -1
            }
        }
        return Mosaic(WIDTH, HEIGHT, rgba, 0L)
    }
}
