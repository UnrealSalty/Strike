package com.strike.surveillance

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneTest {

    @Test
    fun aCarThatSatThroughTwoQuietLooksIsScenery() {
        val scene = Scene()
        val parked = Sighting(VEHICLE, 0.8f, 10, 10, 40, 20)
        scene.observe(listOf(parked), quiet = true)
        scene.observe(listOf(parked), quiet = true)

        assertTrue(scene.isParked(parked))
    }

    @Test
    fun aCarThatJustAppearedIsNotScenery() {
        val scene = Scene()
        val passing = Sighting(VEHICLE, 0.8f, 10, 10, 40, 20)

        assertFalse(scene.isParked(passing))
    }

    @Test
    fun aCarThatMovedOffTheParkedBoxIsANewVisit() {
        val scene = Scene()
        val parked = Sighting(VEHICLE, 0.8f, 10, 10, 40, 20)
        scene.observe(listOf(parked), quiet = true)
        scene.observe(listOf(parked), quiet = true)

        assertFalse(scene.isParked(Sighting(VEHICLE, 0.8f, 80, 10, 40, 20)))
    }

    @Test
    fun aPersonIsNeverScenery() {
        val scene = Scene()
        val person = Sighting(PERSON, 0.6f, 10, 10, 16, 32)
        scene.observe(listOf(person), quiet = true)
        scene.observe(listOf(person), quiet = true)

        assertFalse(scene.isParked(person))
    }

    @Test
    fun allParkedCarsAreRememberedEvenWhenAPersonIsPresent() {
        val scene = Scene()
        val first = Sighting(VEHICLE, 0.9f, 10, 10, 40, 20)
        val second = Sighting(VEHICLE, 0.6f, 80, 10, 40, 20)
        val person = Sighting(PERSON, 0.7f, 50, 10, 16, 32)
        scene.observe(listOf(person, first, second), quiet = true)
        scene.observe(listOf(person, first, second), quiet = true)

        assertTrue(scene.isParked(first))
        assertTrue(scene.isParked(second))
    }

    @Test
    fun detectingAPassingCarDoesNotTurnItIntoScenery() {
        val scene = Scene()
        val passing = Sighting(VEHICLE, 0.8f, 10, 10, 40, 20)
        scene.observe(listOf(passing), quiet = false)
        scene.observe(listOf(passing), quiet = false)

        assertFalse(scene.isParked(passing))
    }

    @Test
    fun aCarThatLeavesDoesNotSuppressTheNextArrivalInThatSpot() {
        val scene = Scene()
        val parked = Sighting(VEHICLE, 0.8f, 10, 10, 40, 20)
        scene.observe(listOf(parked), quiet = true)
        scene.observe(listOf(parked), quiet = true)
        scene.observe(emptyList(), quiet = false)
        scene.observe(listOf(parked), quiet = false)

        assertFalse(scene.isParked(parked))
    }

    @Test
    fun theParkedAnchorDoesNotFollowAMovingCar() {
        val scene = Scene()
        val parked = Sighting(VEHICLE, 0.8f, 10, 10, 40, 20)
        scene.observe(listOf(parked), quiet = true)
        scene.observe(listOf(parked), quiet = true)
        scene.observe(listOf(Sighting(VEHICLE, 0.8f, 15, 10, 40, 20)), quiet = false)
        val departed = Sighting(VEHICLE, 0.8f, 20, 10, 40, 20)
        scene.observe(listOf(departed), quiet = false)

        assertFalse(scene.isParked(departed))
    }

    @Test
    fun aNewParkingSessionStartsWithoutOldScenery() {
        val scene = Scene()
        val parked = Sighting(VEHICLE, 0.8f, 10, 10, 40, 20)
        scene.observe(listOf(parked), quiet = true)
        scene.observe(listOf(parked), quiet = true)
        scene.forget()

        assertFalse(scene.isParked(parked))
    }
}
