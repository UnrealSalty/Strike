package com.strike.daemon

import android.os.IBinder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParkedPanelTest {
    @Test fun handingBackWhileParkedLeavesADarkenedPanelDark() {
        val vendor = Vendor()
        val panel = panel(vendor)
        panel.darken()
        assertTrue(panel.release(carInUse = false))
        assertFalse(vendor.lit)
    }

    @Test fun handingBackToACarInUseLightsTheDarkenedPanel() {
        val vendor = Vendor()
        val panel = panel(vendor)
        panel.darken()
        assertTrue(panel.release(carInUse = true))
        assertTrue(vendor.lit)
    }

    @Test fun handingBackAfterAWakeDropsTheKeepOnHoldAndLeavesTheLight() {
        val vendor = Vendor()
        val panel = panel(vendor)
        panel.wake()
        assertEquals(1, vendor.holds.size)
        assertTrue(panel.release(carInUse = false))
        assertTrue(vendor.holds.isEmpty())
        assertTrue(vendor.lit)
    }

    @Test fun aPanelStrikeNeverDarkenedIsNotLit() {
        val vendor = Vendor().apply { lit = false }
        assertTrue(panel(vendor).release(carInUse = true))
        assertFalse(vendor.lit)
    }

    private fun panel(vendor: Vendor): ParkedPanel {
        val manager = Manager(vendor)
        return ParkedPanel(powerManager = { manager })
    }

    class Manager(@JvmField val mService: Vendor) {
        fun TurnBacklightOn() {
            mService.lit = true
        }

        fun TurnBacklightOff() {
            mService.holds.clear()
            mService.lit = false
        }
    }

    class Vendor {
        var lit = true
        val holds = mutableSetOf<IBinder>()

        @Suppress("UNUSED_PARAMETER")
        fun TurnBacklightOnWithLock(token: IBinder?, tag: String) {
            if (token != null) holds += token
            lit = true
        }

        fun TurnBacklightOffWithLock(token: IBinder?) {
            holds.remove(token)
        }
    }
}
