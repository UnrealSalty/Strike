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
    fun anUnchangedPercentageIsNotRepeated() {
        assertTrue(reader.poll())
        now += 60_000
        assertFalse(reader.poll())
        assertEquals(64, reader.percent)
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
    fun aChangedPercentageIsReportedAtTheNextSample() {
        reader.poll()
        reading = 63
        now += 59_999
        assertFalse(reader.poll())
        assertEquals(64, reader.percent)
        now++
        assertTrue(reader.poll())
        assertEquals(63, reader.percent)
    }

    @Test
    fun wakingAfterALongGapReadsOnceWithoutCatchingUp() {
        var reads = 0
        val counted = SocReader({ reads++; reading }, { now })
        counted.poll()
        now += 8 * 60 * 60_000
        reading = 62
        assertTrue(counted.poll())
        assertEquals(62, counted.percent)
        repeat(10) { assertFalse(counted.poll()) }
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
        assertEquals("battery percentage unavailable", socLine(reader.percent))
    }

    @Test
    fun theLoggedLineCarriesTheUnit() {
        reader.poll()
        assertEquals("battery 64 %", socLine(reader.percent))
    }
}
