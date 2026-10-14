package com.strike.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardOutageTest {

    @Test
    fun oneFailedProbeSendsClipsElsewhereButWaitsBeforeWakingTheCar() {
        val outage = CardOutage()

        assertEquals(CardStep.NONE, outage.observe(false, 0L))
        assertTrue(outage.isDown)
        assertEquals(CardStep.NONE, outage.observe(false, 20_000L))
        assertEquals(CardStep.REVIVE, outage.observe(false, 30_000L))
    }

    @Test
    fun everyWakeIsFollowedByARemountOnceTheReaderHasHadPower() {
        val outage = CardOutage()
        outage.observe(false, 0L)
        outage.observe(false, 30_000L)

        assertEquals(CardStep.NONE, outage.observe(false, 35_000L))
        assertEquals(CardStep.REMOUNT, outage.observe(false, 40_000L))
        assertEquals(CardStep.NONE, outage.observe(false, 50_000L))
    }

    @Test
    fun wakesBackOffUntilEightMinutesApart() {
        val outage = CardOutage()
        val wakes = ArrayList<Long>()
        var nowMs = 0L
        while (nowMs <= 40 * 60_000L) {
            if (outage.observe(false, nowMs) == CardStep.REVIVE) wakes.add(nowMs)
            nowMs += 10_000L
        }
        val gaps = wakes.zipWithNext { a, b -> b - a }

        assertEquals(listOf(60_000L, 120_000L, 240_000L, 480_000L), gaps.take(4))
        assertTrue(gaps.drop(4).all { it == 480_000L })
    }

    @Test
    fun aCardThatAnswersAgainStartsTheNextOutageFromTheShortWait() {
        val outage = CardOutage()
        outage.observe(false, 0L)
        outage.observe(false, 30_000L)
        outage.observe(false, 40_000L)

        outage.observe(true, 50_000L)
        assertFalse(outage.isDown)

        outage.observe(false, 100_000L)
        assertEquals(100_000L, outage.downSinceMs)
        assertEquals(CardStep.REVIVE, outage.observe(false, 130_000L))
    }
}
