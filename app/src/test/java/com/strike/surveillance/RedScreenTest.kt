package com.strike.surveillance

import com.strike.daemon.AccGate
import com.strike.daemon.ParkedPanel
import com.strike.daemon.ParkedPanelTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedScreenTest {

    private val vendor = ParkedPanelTest.Vendor()
    private val manager = ParkedPanelTest.Manager(vendor)
    private val screen = RedScreen(ParkedPanel(powerManager = { manager }), keepDark = { true })

    @Test
    fun aPanelLitBeforeTheCarIsKnownDarkensOnceItIsConfirmedOff() {
        AccGate.say(null, 0L)
        screen.sleepPanel()
        assertTrue(vendor.lit)

        AccGate.say(false, System.currentTimeMillis())
        screen.settle()
        assertFalse(vendor.lit)
    }

    @Test
    fun aCarInUseCancelsTheWaitingDarken() {
        AccGate.say(null, 0L)
        screen.sleepPanel()
        AccGate.say(true, System.currentTimeMillis())
        screen.settle()

        AccGate.say(false, System.currentTimeMillis())
        screen.settle()
        assertTrue(vendor.lit)
    }

    @Test
    fun aCarInGearCancelsTheWaitingDarken() {
        AccGate.say(null, 0L)
        screen.sleepPanel()
        AccGate.say(null, System.currentTimeMillis())
        screen.settle()

        AccGate.say(false, System.currentTimeMillis())
        screen.settle()
        assertTrue(vendor.lit)
    }

    @Test
    fun standingDownWhileParkedKeepsTheDarkenedPanelDark() {
        AccGate.say(false, System.currentTimeMillis())
        screen.sleepPanel()
        screen.hide()
        assertFalse(vendor.lit)
    }

    @Test
    fun startingTheCarLightsThePanelStrikeDarkened() {
        AccGate.say(false, System.currentTimeMillis())
        screen.sleepPanel()
        AccGate.say(true, System.currentTimeMillis())
        screen.standDown()
        assertTrue(vendor.lit)
    }
}
