package com.strike.surveillance

import org.junit.Assert.assertEquals
import org.junit.Test

class EventBandTest {

    @Test
    fun oneVisitIsOneBandRatherThanARowOfTicks() {
        val bands = bandsOf(
            listOf(Mark(0, PERSON), Mark(500, PERSON), Mark(1_000, PERSON))
        )

        assertEquals(1, bands.size)
        assertEquals(0L, bands[0].startMs)
        assertEquals(1_500L, bands[0].endMs)
    }

    @Test
    fun someoneComingBackLaterIsASecondBand() {
        val bands = bandsOf(listOf(Mark(0, PERSON), Mark(9_000, PERSON)))

        assertEquals(2, bands.size)
        assertEquals(9_000L, bands[1].startMs)
    }

    @Test
    fun aCarArrivingDuringAVisitDoesNotJoinThePersonsBand() {
        val bands = bandsOf(listOf(Mark(0, PERSON), Mark(500, VEHICLE)))

        assertEquals(2, bands.size)
        assertEquals(PERSON, bands[0].seen)
        assertEquals(VEHICLE, bands[1].seen)
    }
}
