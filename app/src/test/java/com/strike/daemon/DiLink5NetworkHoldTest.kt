package com.strike.daemon

import com.strike.DiLink5NetworkReceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiLink5NetworkHoldTest {
    @Test
    fun disabledKeepaliveCleansUpOnceAfterAConfirmedStop() {
        val f = Fixture()
        f.hold.update(false, false, null)
        f.now += 60_000L
        f.hold.update(false, false, null)
        assertEquals(listOf("stop"), f.commands())
    }

    @Test
    fun parkedKeepaliveReassertsEveryThirtySeconds() {
        val f = Fixture()
        f.hold.update(true, true, false)
        f.now = 29_999L
        f.hold.update(true, true, false)
        assertEquals(listOf("start"), f.commands())
        f.now = 30_000L
        f.hold.update(true, true, false)
        assertEquals(listOf("start", "start"), f.commands())
        assertEquals(listOf(0L, 30_000L), f.sent.map { it.second })
    }

    @Test
    fun ignitionOnDetachesWithoutStoppingEvenWhenTheSettingWasDisabled() {
        val f = Fixture()
        f.hold.update(true, true, false)
        f.now++
        f.hold.update(false, false, true)
        f.now += 60_000L
        f.hold.update(false, false, true)
        assertEquals(listOf("start", "detach"), f.commands())
    }

    @Test
    fun disablingWhileParkedStopsWithoutWaitingForTheNextHeartbeat() {
        val f = Fixture()
        f.hold.update(true, true, false)
        f.now++
        f.hold.update(false, true, false)
        assertEquals(listOf("start", "stop"), f.commands())
    }

    @Test
    fun anUnconfirmedStopKeepsRetryingUntilItIsConfirmed() {
        val f = Fixture(DiLink5NetworkReceiver.UNCONFIRMED)
        f.hold.update(false, true, false)
        f.now = 29_999L
        f.hold.update(false, true, false)
        assertEquals(1, f.sent.size)
        f.now = 30_000L
        f.hold.update(false, true, false)
        assertEquals(2, f.sent.size)
        f.result = DiLink5NetworkReceiver.CONFIRMED
        f.now = 60_000L
        f.hold.update(false, true, false)
        f.now = 90_000L
        f.hold.update(false, true, false)
        assertEquals(listOf("stop", "stop", "stop"), f.commands())
    }

    @Test
    fun repeatedFailuresDoNotRepeatTheSameWarning() {
        val f = Fixture(DiLink5NetworkReceiver.UNAVAILABLE)
        repeat(4) {
            f.hold.update(true, true, false)
            f.now += 30_000L
        }
        assertEquals(4, f.sent.size)
        assertEquals(1, f.messages.size)
        f.result = DiLink5NetworkReceiver.UNCONFIRMED
        f.hold.update(true, true, false)
        assertEquals(2, f.messages.size)
    }

    @Test
    fun unknownPowerDoesNotStartWithoutAConfirmedParkedLease() {
        val f = Fixture()
        f.hold.update(true, false, null)
        assertTrue(f.sent.isEmpty())
        f.hold.update(true, true, null)
        assertEquals(listOf("start"), f.commands())
    }

    @Test
    fun queuedParkedStartsAreDiscardedWhenIgnitionTurnsOnBeforeDispatch() {
        val pending = ArrayList<Runnable>()
        val sent = ArrayList<String>()
        var now = 1_000L
        val hold = DiLink5NetworkHold(
            send = { command, _ -> sent.add(command); DiLink5NetworkReceiver.CONFIRMED },
            nowMs = { now },
            report = { _, _ -> },
            execute = { pending.add(it) }
        )
        hold.update(true, true, false)
        now++
        hold.update(true, true, false)
        now++
        hold.update(false, false, true)
        assertTrue(sent.isEmpty())
        assertEquals(1, pending.size)
        pending.removeAt(0).run()
        assertEquals(listOf("detach"), sent)
        assertTrue(pending.isEmpty())
    }

    @Test
    fun ignitionOnDuringAStartIsTheNextCommandSent() {
        val sent = ArrayList<String>()
        var now = 1_000L
        lateinit var hold: DiLink5NetworkHold
        hold = DiLink5NetworkHold(
            send = { command, _ ->
                sent.add(command)
                if (command == "start") {
                    now++
                    hold.update(true, false, true)
                }
                DiLink5NetworkReceiver.CONFIRMED
            },
            nowMs = { now },
            report = { _, _ -> },
            execute = { it.run() }
        )
        hold.update(true, true, false)
        assertEquals(listOf("start", "detach"), sent)
    }

    @Test
    fun broadcastResultsNeedOneSuccessfulExplicitReply() {
        for (result in 1..5) {
            assertEquals(result, networkReply(0, "Broadcasting: Intent {}\nBroadcast completed: result=$result\n"))
        }
        val invalid = listOf(
            "Broadcast completed: result=0",
            "Broadcast completed: result=6",
            "Broadcast completed: result=1\nBroadcast completed: result=1",
            "Broadcast completed: result=1\nBroadcast completed: result=2",
            "Broadcast completed: result=0\nBroadcast completed: result=1",
            "Broadcasting: Intent {}",
            "error: Broadcast completed: result=1",
            "x".repeat(4_096) + "\nBroadcast completed: result=1"
        )
        for (output in invalid) {
            assertEquals(DiLink5NetworkReceiver.UNAVAILABLE, networkReply(0, output))
        }
        assertEquals(DiLink5NetworkReceiver.UNAVAILABLE, networkReply(1, "Broadcast completed: result=1"))
        assertEquals(DiLink5NetworkReceiver.UNAVAILABLE, networkReply(137, "Broadcast completed: result=1"))
    }

    private class Fixture(var result: Int = DiLink5NetworkReceiver.CONFIRMED) {
        var now = 0L
        val sent = ArrayList<Pair<String, Long>>()
        val messages = ArrayList<String>()
        val hold = DiLink5NetworkHold(
            send = { command, observedAt -> sent.add(command to observedAt); result },
            nowMs = { now },
            report = { _, message -> messages.add(message) },
            execute = { it.run() }
        )

        fun commands() = sent.map { it.first }
    }
}
