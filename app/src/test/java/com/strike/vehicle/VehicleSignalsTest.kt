package com.strike.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VehicleSignalsTest {

    @Test
    fun socRejectsTheUnpopulatedZero() {
        assertNull(socOf(0.0))
        assertNull(socOf(null))
        assertNull(socOf(101.0))
        assertEquals(46, socOf(45.6))
    }

    @Test
    fun rangeRejectsZeroAndImpossibleDistances() {
        assertNull(rangeOf(0))
        assertNull(rangeOf(1000))
        assertEquals(289, rangeOf(289))
    }

    @Test
    fun batteryEnergyPrefersTheDirectReading() {
        assertEquals(20.7, batteryKwhOf(20.7, 205, 46)!!, 0.001)
    }

    @Test
    fun batteryEnergyFallsBackToTenthsOfAKilowattHour() {
        assertEquals(20.5, batteryKwhOf(null, 205, 46)!!, 0.001)
    }

    @Test
    fun batteryEnergyRejectsTheReadingThatIsActuallyThePercentage() {
        assertNull(batteryKwhOf(46.0, null, 46))
    }

    @Test
    fun batteryEnergyIsUnknownWhenNeitherSourceAnswers() {
        assertNull(batteryKwhOf(null, 0, 46))
    }

    @Test
    fun gearMapsTheAutoModeType() {
        assertEquals("P", gearOf(1))
        assertEquals("D", gearOf(4))
        assertNull(gearOf(0))
        assertNull(gearOf(7))
    }

    @Test
    fun accIsOnFromIgnitionUpAndUnknownWhenTheHalBluffs() {
        assertEquals(false, accOnOf(1))
        assertEquals(true, accOnOf(2))
        assertEquals(true, accOnOf(3))
        assertNull(accOnOf(4))
        assertNull(accOnOf(255))
    }

    @Test
    fun lockMapsTheOtaDoorState() {
        assertEquals(false, lockOf(1))
        assertEquals(true, lockOf(2))
        assertNull(lockOf(0))
        assertNull(lockOf(null))
    }
}
