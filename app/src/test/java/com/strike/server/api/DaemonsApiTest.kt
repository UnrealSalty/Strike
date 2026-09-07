package com.strike.server.api

import org.junit.Assert.assertEquals
import org.junit.Test

class DaemonsApiTest {

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
