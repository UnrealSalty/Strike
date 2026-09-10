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
    fun socRejectsNonFiniteReadings() {
        for (percent in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertNull(socOf(percent))
        }
    }

    @Test
    fun rangeRejectsZeroAndImpossibleDistances() {
        assertNull(rangeOf(0))
        assertNull(rangeOf(1000))
        assertEquals(289, rangeOf(289))
    }

    @Test
    fun fuelGaugeRejectsTheEmptyAnswerAndTheSensorRails() {
        assertNull(fuelOf(0))
        assertNull(fuelOf(null))
        assertNull(fuelOf(101))
        for (rail in listOf(254, 255, 511, 1023, 2046, 2047, 4095, 65534, 65535)) {
            assertNull(fuelOf(rail))
        }
        assertEquals(28, fuelOf(28))
    }

    @Test
    fun fuelRangeRejectsZeroAndKeepsDistancesThatLookLikeGaugeRails() {
        assertNull(fuelRangeOf(0))
        assertNull(fuelRangeOf(null))
        assertNull(fuelRangeOf(1201))
        assertNull(fuelRangeOf(4095))
        assertEquals(255, fuelRangeOf(255))
        assertEquals(1023, fuelRangeOf(1023))
        assertEquals(320, fuelRangeOf(320))
    }

    @Test
    fun batteryEnergyPrefersTheDirectReading() {
        assertEquals(20.7, batteryKwhOf(20.7, 46) { error("The primary energy reading is valid") }!!, 0.001)
    }

    @Test
    fun batteryEnergyFallsBackToTenthsOfAKilowattHour() {
        assertEquals(20.5, batteryKwhOf(null, 46) { 205 }!!, 0.001)
    }

    @Test
    fun batteryEnergyRejectsTheReadingThatIsActuallyThePercentage() {
        assertNull(batteryKwhOf(46.0, 46) { null })
    }

    @Test
    fun batteryEnergyIsUnknownWhenNeitherSourceAnswers() {
        assertNull(batteryKwhOf(null, 46) { 0 })
    }

    @Test
    fun invalidPrimaryEnergyCanUseAValidFallback() {
        for (direct in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.0, 46.1)) {
            assertEquals(20.5, batteryKwhOf(direct, 46) { 205 }!!, 0.001)
        }
    }

    @Test
    fun nonFiniteEnergyNeverReachesTheDisplayWithoutSoc() {
        for (direct in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertNull(batteryKwhOf(direct, null) { null })
            assertNull(batteryKwhOf(direct, 3) { null })
        }
    }

    @Test
    fun largerPackReadingsNeedEnoughSocToValidate() {
        assertEquals(108.0, batteryKwhOf(108.0, 90) { error("Valid 120 kWh pack") }!!, 0.001)
        assertEquals(99.0, batteryKwhOf(null, 90) { 990 }!!, 0.001)
        assertNull(batteryKwhOf(108.0, null) { null })
        assertNull(batteryKwhOf(108.0, 3) { null })
        assertNull(batteryKwhOf(108.0, 50) { null })
        assertNull(batteryKwhOf(120.0, 100) { null })
    }

    @Test
    fun percentageEchoesAreRejectedFromEitherEnergyGetter() {
        for (soc in listOf(20, 46, 84, 100)) {
            assertNull(batteryKwhOf(soc + 0.1, soc) { null })
            assertNull(batteryKwhOf(null, soc) { soc * 10 + 1 })
        }
    }

    @Test
    fun lowSocOnTheCurrentPackIsNotMistakenForAPercentageEcho() {
        assertEquals(2.7, batteryKwhOf(2.7, 6) { null }!!, 0.001)
        assertEquals(4.5, batteryKwhOf(4.5, 10) { null }!!, 0.001)
        assertEquals(20.7, batteryKwhOf(20.7, null) { null }!!, 0.001)
    }

    @Test
    fun anUnverifiedHalfScaleHybridReadingDoesNotHideAValidFallback() {
        assertEquals(16.5, batteryKwhOf(8.25, 77) { 165 }!!, 0.001)
        assertNull(batteryKwhOf(9.1, 100) { 91 })
    }

    @Test
    fun fullScaleHybridReadingsAreNeverDoubled() {
        assertEquals(16.5, batteryKwhOf(16.5, 77) { error("Primary is already in kWh") }!!, 0.001)
        assertEquals(16.5, batteryKwhOf(null, 77) { 165 }!!, 0.001)
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
