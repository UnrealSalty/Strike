package com.strike.daemon

import android.os.IBinder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.IdentityHashMap

class ParkedPanelTest {
    @Test fun releasesTheNullTokenAcceptedByTheVendor() {
        val vendor = Vendor().apply { rejectsBinder = true }
        val panel = panel(vendor)
        panel.darken()
        assertEquals(1, vendor.locks[null])
        assertTrue(panel.release())
        assertTrue(vendor.locks.isEmpty())
    }

    @Test fun failedReleaseRetainsOwnershipUntilItCanBeRetried() {
        val vendor = Vendor()
        val panel = panel(vendor)
        panel.darken()
        vendor.rejectsRelease = true
        assertFalse(panel.release())
        panel.darken()
        assertEquals(1, vendor.locks.values.sum())
        vendor.rejectsRelease = false
        assertTrue(panel.release())
        assertTrue(vendor.locks.isEmpty())
    }

    @Test fun repeatedDarkeningDoesNotStackVendorLocks() {
        val vendor = Vendor()
        val panel = panel(vendor)
        repeat(10) { panel.darken() }
        assertEquals(1, vendor.locks.values.sum())
        assertTrue(panel.release())
        assertTrue(vendor.locks.isEmpty())
    }

    @Test fun anAmbiguousOffFailureCanStillReleaseTheAcquiredLock() {
        val vendor = Vendor().apply { failsAfterAcquire = true }
        val panel = panel(vendor)
        panel.darken()
        panel.darken()
        assertEquals(1, vendor.locks.values.sum())
        assertFalse(vendor.locks.containsKey(null))
        assertTrue(panel.release())
        assertTrue(vendor.locks.isEmpty())
    }

    @Test fun anAmbiguousNullTokenFailureRetainsTheNullReleaseToken() {
        val vendor = Vendor().apply {
            rejectsBinder = true
            failsAfterAcquire = true
        }
        val panel = panel(vendor)
        panel.darken()
        assertEquals(1, vendor.locks[null])
        assertTrue(panel.release())
        assertTrue(vendor.locks.isEmpty())
    }

    @Test fun aFailedWakeDoesNotForgetTheOffLock() {
        val vendor = Vendor()
        val panel = panel(vendor)
        panel.darken()
        vendor.rejectsRelease = true
        panel.wake()
        assertFalse(panel.release())
        assertEquals(1, vendor.locks.values.sum())
        vendor.rejectsRelease = false
        panel.wake()
        assertTrue(vendor.locks.isEmpty())
        assertTrue(panel.release())
    }

    private fun panel(vendor: Vendor): ParkedPanel {
        val manager = Manager(vendor)
        return ParkedPanel(powerManager = { manager })
    }

    class Manager(@JvmField val mService: Vendor)

    class Vendor {
        val locks = IdentityHashMap<IBinder?, Int>()
        var rejectsBinder = false
        var failsAfterAcquire = false
        var rejectsRelease = false

        fun TurnBacklightOffWithLock(token: IBinder?) {
            if (rejectsBinder && token != null) throw IllegalArgumentException("token rejected")
            locks[token] = (locks[token] ?: 0) + 1
            if (failsAfterAcquire) throw IllegalStateException("reply failed after acquire")
        }

        @Suppress("UNUSED_PARAMETER")
        fun TurnBacklightOnWithLock(token: IBinder?, tag: String) {
            if (rejectsRelease) throw IllegalStateException("release refused")
            val count = locks[token] ?: return
            if (count == 1) locks.remove(token) else locks[token] = count - 1
        }
    }
}
