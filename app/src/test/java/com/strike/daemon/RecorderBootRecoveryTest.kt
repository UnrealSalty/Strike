package com.strike.daemon

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.Executor

class RecorderBootRecoveryTest {
    private val tasks = ArrayDeque<Runnable>()
    private var nowMs = 0L
    private var checks = 0
    private var starts = 0
    private var stops = 0
    private var watched = false
    private var disabled = false
    private var recover = false
    private var reply: JSONObject? = null
    private var onRecover: () -> Unit = {}
    private var onRead: () -> Unit = {}
    private var onPause: () -> Unit = {}
    private var onWatchdog: () -> Unit = {}
    private val recorder = RecorderDaemon(
        authorise = { true },
        prepare = {},
        launchDaemon = { starts++; watched = true; true },
        haltDaemon = { stops++; watched = false; disabled = true; reply = null; true },
        readStatus = { onRead(); reply },
        lastError = { null },
        worker = Executor { tasks.addLast(it) },
        nowMs = { nowMs },
        pause = { nowMs += it; onPause() },
        watchdogRunning = { onWatchdog(); watched },
        recoverWatchdog = {
            checks++
            onRecover()
            (recover && !disabled && !watched).also { if (it) watched = true }
        }
    )

    @Test fun bootHandoffReturnsWhileMaintenanceWaitsForCameraStartup() {
        recover = true
        assertFalse(recorder.restoreAfterBoot(start = true))
        assertEquals(0L, nowMs)
        assertEquals(1, tasks.size)
        reply = JSONObject().put("bootReady", true).put("uptimeMs", 1_000L)
        onPause = { if (nowMs >= 2_000L) reply!!.put("uptimeMs", 3_000L) }
        drain()

        assertTrue(recorder.restoreAfterBoot(start = false))
        assertEquals(Phase.RUNNING, recorder.phase)
        assertTrue(recorder.canStop)
        assertTrue(watched)
        assertEquals(0, starts)
        assertEquals(0, stops)
    }

    @Test fun aStableDaemonWaitsUntilParkedPowerIsReady() {
        recover = true
        reply = JSONObject().put("uptimeMs", 3_000L).put("bootReady", false)
        assertFalse(recorder.restoreAfterBoot(start = true))
        drain()
        assertFalse(recorder.restoreAfterBoot(start = false))
        assertEquals(0L, nowMs)
        reply!!.put("bootReady", true)
        assertTrue(recorder.restoreAfterBoot(start = false))
        assertEquals(1, checks)
        assertEquals(0, stops)
    }

    @Test fun missingPowerReadinessCannotCompleteBootRecovery() {
        watched = true
        reply = JSONObject().put("uptimeMs", 3_000L)
        recorder.restoreAfterBoot(start = true)
        drain()
        assertFalse(recorder.restoreAfterBoot(start = false))
    }

    @Test fun aRecorderThatWasNeverStartedNeedsNoBootRecovery() {
        assertFalse(recorder.restoreAfterBoot(start = true))
        drain()
        assertTrue(recorder.restoreAfterBoot(start = false))
        assertEquals(Phase.OFF, recorder.phase)
        assertFalse(recorder.canStop)
        assertFalse(watched)
        assertEquals(0, starts)
        assertEquals(0, stops)
    }

    @Test fun bootRecoveryPreservesAnIntentionalStop() {
        recover = true
        disabled = true
        assertFalse(recorder.restoreAfterBoot(start = true))
        drain()
        assertTrue(recorder.restoreAfterBoot(start = false))
        assertEquals(Phase.OFF, recorder.phase)
        assertFalse(recorder.canStop)
        assertTrue(disabled)
        assertFalse(watched)
        assertEquals(0, starts)
    }

    @Test fun aFailedMaintenanceProbeIsNotMistakenForAnIntentionalStop() {
        onRecover = { throw IOException("Shell unavailable") }
        recorder.restoreAfterBoot(start = true)
        drain()
        assertFalse(recorder.restoreAfterBoot(start = false))
        assertEquals(Phase.OFF, recorder.phase)
        onRecover = {}
        recover = true
        reply = JSONObject().put("bootReady", true).put("uptimeMs", 3_000L)
        recorder.restoreAfterBoot(start = true)
        drain()
        assertTrue(recorder.restoreAfterBoot(start = false))
        assertEquals(Phase.RUNNING, recorder.phase)
        assertEquals(2, checks)
    }

    @Test fun aSuccessfulManualStopClearsAnEarlierRecoveryFailure() {
        onRecover = { throw IOException("Shell unavailable") }
        recorder.restoreAfterBoot(start = true)
        drain()
        assertFalse(recorder.restoreAfterBoot(start = false))
        recorder.stop()
        drain()
        assertTrue(recorder.restoreAfterBoot(start = false))
        assertTrue(disabled)
        assertEquals(1, stops)
    }

    @Test fun anExistingWatchdogMustProduceAStableCameraReply() {
        watched = true
        recorder.restoreAfterBoot(start = true)
        drain()
        assertFalse(recorder.restoreAfterBoot(start = false))
        reply = JSONObject().put("bootReady", true).put("uptimeMs", 2_999L)
        assertFalse(recorder.restoreAfterBoot(start = false))
        reply!!.put("uptimeMs", 3_000L)
        assertTrue(recorder.restoreAfterBoot(start = false))
        assertEquals(0, starts)
        assertEquals(0, stops)
    }

    @Test fun aWatchdogWhoseCameraNeverAnswersKeepsBootRecoveryPending() {
        recover = true
        recorder.restoreAfterBoot(start = true)
        drain()
        assertFalse(recorder.restoreAfterBoot(start = false))
        assertEquals(Phase.FAILED, recorder.phase)
        assertTrue(recorder.canStop)
        assertTrue(watched)
        assertEquals(0, stops)
    }

    @Test fun onlyTheFirstBootRequestForcesMaintenanceBeforeTheMinuteCadence() {
        recorder.maintain()
        drain()
        assertEquals(1, checks)
        recorder.restoreAfterBoot(start = true)
        drain()
        repeat(3) { assertTrue(recorder.restoreAfterBoot(start = false)) }
        recorder.maintain()
        drain()
        assertEquals(2, checks)
        recorder.restoreAfterBoot(start = true)
        drain()
        assertEquals(3, checks)
    }

    @Test fun bootDoesNotDuplicateAnAlreadyQueuedMaintenanceAttempt() {
        recorder.maintain()
        assertFalse(recorder.restoreAfterBoot(start = true))
        assertFalse(recorder.restoreAfterBoot(start = false))
        assertEquals(1, tasks.size)
        drain()
        assertTrue(recorder.restoreAfterBoot(start = false))
        assertEquals(1, checks)
    }

    @Test fun aManualStopDuringRecoveryCannotBeOverriddenByBootPolling() {
        recover = true
        onRecover = { recorder.stop() }
        recorder.restoreAfterBoot(start = true)
        drain()
        assertTrue(recorder.restoreAfterBoot(start = false))
        assertEquals(Phase.OFF, recorder.phase)
        assertTrue(disabled)
        assertFalse(watched)
        assertEquals(1, stops)
        assertEquals(0, starts)
    }

    @Test fun aManualStopDuringTheStatusReadCannotUseTheOldReply() {
        watched = true
        reply = JSONObject().put("bootReady", true).put("uptimeMs", 3_000L)
        onRead = { recorder.stop() }
        assertFalse(recorder.restoreAfterBoot(start = false))
        drain()
        assertTrue(disabled)
    }

    @Test fun aCameraThatDisappearsAfterMaintenanceKeepsBootRecoveryPending() {
        recover = true
        reply = JSONObject().put("bootReady", true).put("uptimeMs", 3_000L)
        recorder.restoreAfterBoot(start = true)
        drain()
        reply = null
        assertFalse(recorder.restoreAfterBoot(start = false))
        assertTrue(watched)
        assertTrue(recorder.canStop)
        assertEquals(0, stops)
    }

    @Test fun anIntentChangeDuringTheFinalWatchdogProbeInvalidatesItsReply() {
        onWatchdog = { recorder.start() }
        assertFalse(recorder.restoreAfterBoot(start = false))
        assertEquals(Phase.STARTING, recorder.phase)
    }

    @Test fun statusAndWatchdogProbeFailuresRemainVisibleToTheBootCaller() {
        onRead = { throw IOException("Status unavailable") }
        assertEquals("Status unavailable", assertThrows(IOException::class.java) {
            recorder.restoreAfterBoot(start = false)
        }.message)
        onRead = {}
        onWatchdog = { throw IOException("Watchdog unavailable") }
        assertEquals("Watchdog unavailable", assertThrows(IOException::class.java) {
            recorder.restoreAfterBoot(start = false)
        }.message)
    }

    private fun drain() {
        while (tasks.isNotEmpty()) tasks.removeFirst().run()
    }
}
