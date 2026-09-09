package com.strike.server.api

import org.junit.Assert.assertEquals
import org.junit.Test
import org.json.JSONObject

class DaemonsApiTest {

    @Test
    fun enabledServicesWaitWithoutClaimingTheyAreRunning() {
        assertEquals("starting", tunnelDaemonState("waiting"))
        assertEquals("starting", tunnelDaemonState("stopping"))
        assertEquals("starting", surveillanceDaemonState(true, JSONObject().put("accOn", true)))
        assertEquals("starting", surveillanceDaemonState(true, JSONObject().put("accOn", JSONObject.NULL)))
        assertEquals("starting", surveillanceDaemonState(true, JSONObject().put("accOn", false)))
    }

    @Test
    fun activeWorkAndFailuresKeepTheirOwnIndicators() {
        assertEquals("running", tunnelDaemonState("running"))
        assertEquals("broken", tunnelDaemonState("broken"))
        assertEquals("off", tunnelDaemonState("off"))
        assertEquals("running", surveillanceDaemonState(true, JSONObject().put("armed", true)))
        assertEquals("running", surveillanceDaemonState(true, JSONObject().put("writing", "watch")))
        assertEquals("broken", surveillanceDaemonState(true, null))
        assertEquals("off", surveillanceDaemonState(false, JSONObject().put("armed", true)))
    }

    @Test
    fun theCountIsGreenOnlyWhenEverythingRuns() {
        assertEquals("ok", health(running = 2, total = 2))
        assertEquals("warn", health(running = 1, total = 2))
        assertEquals("bad", health(running = 0, total = 2))
    }

    @Test
    fun uptimeIsExactToTheSecond() {
        assertEquals("0:00:00", formatUptime(0))
        assertEquals("0:03:07", formatUptime(187_000))
        assertEquals("1:23:45", formatUptime((1 * 3_600 + 23 * 60 + 45) * 1_000L))
        assertEquals("2d 3:00:09", formatUptime((2 * 86_400 + 3 * 3_600 + 9) * 1_000L))
    }
}
