package com.strike.online

import org.junit.Assert.*
import org.junit.Test

class ParkedAccessTest {
    @Test fun theNewServiceReceivesTheAppUidAndAvoidsTheLegacyService() {
        val manager = Startup()
        val status = exemptBydBackground(10123) {
            assertEquals("byd_datacached", it)
            manager
        }
        assertEquals("allowed", status)
        assertEquals("10123" to 0, manager.setting)
    }

    @Test fun olderFirmwareUsesTheLegacyService() {
        val manager = Legacy()
        val status = exemptBydBackground(10234) { if (it == "bg_datacache") manager else null }
        assertEquals("allowed", status)
        assertEquals("10234" to 0, manager.setting)
    }

    @Test fun aRejectedNewServiceStillAllowsTheLegacyFallback() {
        val manager = Legacy()
        val status = exemptBydBackground(10345) {
            if (it == "byd_datacached") throw SecurityException()
            manager
        }
        assertEquals("allowed", status)
        assertEquals("10345" to 0, manager.setting)
    }

    @Test fun missingServicesAndPermissionFailuresAreReportedSeparately() {
        assertEquals("unavailable", exemptBydBackground(10123) { null })
        assertEquals("rejected", exemptBydBackground(10123) { throw SecurityException() })
        assertEquals("rejected", exemptBydBackground(10123) { Any() })
    }

    class Startup {
        var setting: Pair<String, Int>? = null
        fun setAppStartupData(uid: String, mode: Int) { setting = uid to mode }
    }

    class Legacy {
        var setting: Pair<String, Int>? = null
        fun setAppOpsData(uid: String, mode: Int) { setting = uid to mode }
    }
}
