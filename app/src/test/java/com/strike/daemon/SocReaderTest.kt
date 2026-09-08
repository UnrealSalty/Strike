package com.strike.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SocReaderTest {
    private var now = 1_000_000L
    private var reading: Int? = 64
    private val reader = SocReader({ reading }, { now })

    @Test
    fun theFirstReadingIsReported() {
        assertTrue(reader.poll())
        assertEquals(64, reader.percent)
    }

    @Test
    fun anUnchangedPercentageIsNotRepeatedEveryMinute() {
        assertTrue(reader.poll())
        now += 60_000
        assertFalse(reader.poll())
        assertEquals(64, reader.percent)
    }

    @Test
    fun aStillPercentageIsRepeatedHalfHourlySoTheDeviceProvesItAnswers() {
        reader.poll()
        repeat(29) {
            now += 60_000
            assertFalse(reader.poll())
        }
        now += 60_000
        assertTrue(reader.poll())
    }

    @Test
    fun theStatisticDeviceIsReadOnceAMinute() {
        var reads = 0
        val counted = SocReader({ reads++; reads }, { now })
        counted.poll()
        repeat(59) {
            now += 1_000
            counted.poll()
        }
        assertEquals(1, reads)
        now += 1_000
        counted.poll()
        assertEquals(2, reads)
    }

    @Test
    fun aPercentageThatArrivesLateIsReportedWhenItLands() {
        reading = null
        assertTrue(reader.poll())
        assertNull(reader.percent)
        reading = 41
        now += 60_000
        assertTrue(reader.poll())
        assertEquals(41, reader.percent)
    }

    @Test
    fun aPercentageThatStopsAnsweringIsReported() {
        reader.poll()
        reading = null
        now += 60_000
        assertTrue(reader.poll())
        assertNull(reader.percent)
    }

    @Test
    fun theLineCarriesTheUnitAndThePowerState() {
        assertEquals("battery 64 %, car off", socLine(64, false))
        assertEquals("battery 64 %, car on", socLine(64, true))
        assertEquals("battery 64 %, power unknown", socLine(64, null))
        assertEquals("battery percentage unavailable, car off", socLine(null, false))
    }
}
