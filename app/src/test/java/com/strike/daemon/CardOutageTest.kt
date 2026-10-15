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
    fun aHungReaderGetsThreeWakesThenAsksForAReplugAndLeavesTheCarAsleep() {
        val outage = CardOutage()
        val steps = ArrayList<Pair<Long, CardStep>>()
        var nowMs = 0L
        while (nowMs <= 8 * 60 * 60_000L) {
            val step = outage.observe(false, nowMs)
            if (step != CardStep.NONE) steps.add(nowMs to step)
            nowMs += 10_000L
        }

        assertEquals(
            listOf(
                30_000L to CardStep.REVIVE, 40_000L to CardStep.REMOUNT,
                90_000L to CardStep.REVIVE, 100_000L to CardStep.REMOUNT,
                210_000L to CardStep.REVIVE, 220_000L to CardStep.REMOUNT,
                450_000L to CardStep.REPLUG
            ),
            steps
        )
        assertTrue(outage.needsReplug)
    }

    @Test
    fun aReplugClearsTheRequestAndTheNextOutageWakesTheCarAgain() {
        val outage = CardOutage()
        var nowMs = 0L
        while (!outage.needsReplug) {
            outage.observe(false, nowMs)
            nowMs += 10_000L
        }

        outage.observe(true, nowMs)
        assertFalse(outage.needsReplug)
        outage.observe(false, nowMs + 10_000L)
        assertEquals(CardStep.REVIVE, outage.observe(false, nowMs + 40_000L))
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
