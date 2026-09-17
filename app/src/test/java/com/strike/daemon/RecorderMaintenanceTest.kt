package com.strike.daemon

import com.strike.core.Logs
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.Executor

class RecorderMaintenanceTest {
    private val tasks = ArrayDeque<Runnable>()
    private var inline = false
    private var nowMs = 0L
    private var checks = 0
    private var stops = 0
    private var watched = false
    private var disabled = false
    private var recover = false
    private var reply: JSONObject? = null
    private var onRecover: () -> Unit = {}
    private var onRead: () -> Unit = {}
    private var onPause: () -> Unit = {}
    private var onUpdateStop: () -> Unit = {}
    private val recorder = RecorderDaemon(
        authorise = { true },
        prepare = {},
        launchDaemon = { watched = true; true },
        haltDaemon = { stops++; watched = false; disabled = true; true },
        readStatus = { onRead(); reply },
        lastError = { null },
        worker = Executor { if (inline) it.run() else tasks.addLast(it) },
        nowMs = { nowMs },
        pause = { nowMs += it; onPause() },
        haltForUpdate = {
            onUpdateStop()
            watched = false
            disabled = true
            true
        },
        watchdogRunning = { watched },
        recoverWatchdog = {
            checks++
            onRecover()
            (recover && !disabled).also { if (it) watched = true }
        }
    )

    @Test
    fun maintenanceSchedulesOncePerMinuteWithOnlyOnePendingTask() {
        repeat(10) { recorder.maintain() }
        assertEquals(1, tasks.size)
        nowMs = 60_000L
        recorder.maintain()
        assertEquals(1, tasks.size)
        tasks.removeFirst().run()
        assertEquals(1, checks)

        recorder.maintain()
        tasks.removeFirst().run()
        assertEquals(2, checks)
        nowMs = 119_999L
        recorder.maintain()
        assertTrue(tasks.isEmpty())
        nowMs = 120_000L
        recorder.maintain()
        tasks.removeFirst().run()
        assertEquals(3, checks)
        assertEquals(Phase.OFF, recorder.phase)
    }

    @Test
    fun explicitStopInvalidatesQueuedMaintenance() {
        recover = true
        recorder.maintain()
        recorder.stop()
        tasks.removeFirst().run()
        assertEquals(0, checks)
        tasks.removeFirst().run()

        assertEquals(Phase.OFF, recorder.phase)
        assertEquals(1, stops)
        assertFalse(watched)
        assertFalse(recorder.canStop)
    }

    @Test
    fun maintenanceWaitsForStartupAndShutdown() {
        reply = JSONObject().put("uptimeMs", 3_000)
        recorder.start()
        recorder.maintain()
        assertEquals(1, tasks.size)
        tasks.removeFirst().run()
        recorder.stop()
        recorder.maintain()
        assertEquals(1, tasks.size)
        tasks.removeFirst().run()

        assertEquals(0, checks)
        assertEquals(Phase.OFF, recorder.phase)
    }

    @Test
    fun restoredWatchdogWaitsForAStableDaemonReply() {
        recover = true
        reply = JSONObject().put("uptimeMs", 1_000)
        onPause = { if (nowMs >= 1_500L) reply = JSONObject().put("uptimeMs", 3_000) }
        recorder.maintain()
        tasks.removeFirst().run()

        assertEquals(1_500L, nowMs)
        assertEquals(Phase.RUNNING, recorder.phase)
        assertTrue(watched)
        assertTrue(recorder.canStop)
        assertEquals(0, stops)
    }

    @Test
    fun statusTimeoutPreservesTheRestoredWatchdogAndAcceptsALateReply() {
        recover = true
        recorder.maintain()
        tasks.removeFirst().run()

        assertEquals(20_000L, nowMs)
        assertEquals(Phase.FAILED, recorder.phase)
        assertTrue(watched)
        assertTrue(recorder.canStop)
        assertEquals(0, stops)

        reply = JSONObject().put("uptimeMs", 3_000)
        recorder.status()
        assertEquals(Phase.RUNNING, recorder.phase)
        assertEquals("", recorder.failure)
        assertEquals(1, checks)
    }

    @Test
    fun aStopRequestedDuringWatchdogRecoveryWins() {
        recover = true
        onRecover = { recorder.stop() }
        recorder.maintain()
        tasks.removeFirst().run()
        assertEquals(Phase.STOPPING, recorder.phase)
        tasks.removeFirst().run()

        assertEquals(Phase.OFF, recorder.phase)
        assertEquals(1, stops)
        assertFalse(watched)
        assertFalse(recorder.canStop)
    }

    @Test
    fun updatePauseCannotBeUndoneByMaintenance() {
        inline = true
        watched = true
        recover = true
        reply = JSONObject().put("uptimeMs", 3_000)
        recorder.status()
        onUpdateStop = { recorder.maintain(); reply = null }
        recorder.pauseForUpdate { assertTrue(it) }

        assertEquals(0, checks)
        assertEquals(Phase.OFF, recorder.phase)
        assertFalse(watched)
        nowMs = 60_000L
        recorder.maintain()
        assertEquals(1, checks)
        assertEquals(Phase.OFF, recorder.phase)
        assertFalse(watched)
        assertFalse(recorder.canStop)
        assertEquals(0, stops)
    }

    @Test
    fun aStatusReadFailureLeavesRecoveryRetryableAndStoppable() {
        recover = true
        onRead = { throw IOException("Connection lost") }
        recorder.maintain()
        tasks.removeFirst().run()

        assertEquals(Phase.FAILED, recorder.phase)
        assertTrue(watched)
        assertTrue(recorder.canStop)
        assertEquals(0, stops)

        nowMs = 60_000L
        onRead = {}
        reply = JSONObject().put("uptimeMs", 3_000)
        recorder.maintain()
        assertEquals(1, tasks.size)
        tasks.removeFirst().run()
        assertEquals(2, checks)
        assertEquals(Phase.RUNNING, recorder.phase)
        assertEquals(0, stops)

        recorder.stop()
        tasks.removeFirst().run()
        assertEquals(Phase.OFF, recorder.phase)
        assertEquals(1, stops)
        assertFalse(watched)
        assertFalse(recorder.canStop)
    }

    @Test
    fun aFailedRecoveryCheckCanRetryNextMinute() {
        onRecover = { throw IOException("Shell unavailable") }
        recorder.maintain()
        tasks.removeFirst().run()
        assertEquals(Phase.OFF, recorder.phase)
        assertEquals(1, checks)
        recorder.maintain()
        assertTrue(tasks.isEmpty())

        nowMs = 60_000L
        onRecover = {}
        recover = true
        reply = JSONObject().put("uptimeMs", 3_000)
        recorder.maintain()
        tasks.removeFirst().run()
        assertEquals(2, checks)
        assertEquals(Phase.RUNNING, recorder.phase)
        assertTrue(watched)
    }

    @Test
    fun aPersistentRecoveryFailureRetriesWithoutRepeatingTheWarning() {
        val after = Logs.recent().lastOrNull()?.sequence ?: 0L
        onRecover = { throw IOException("Could not launch the watchdog") }

        repeat(3) {
            maintainNow()
            nowMs += 60_000L
        }

        assertEquals(3, checks)
        assertEquals(1, recoveryWarnings(after))
        assertEquals(Phase.OFF, recorder.phase)
        assertEquals(0, stops)
    }

    @Test
    fun aNoOpProbeDoesNotResetAPersistentRecoveryWarning() {
        val after = Logs.recent().lastOrNull()?.sequence ?: 0L
        val failure = { throw IOException("Could not launch the watchdog") }
        onRecover = failure
        maintainNow()
        nowMs += 60_000L
        onRecover = {}
        maintainNow()
        nowMs += 60_000L
        onRecover = failure
        maintainNow()

        assertEquals(3, checks)
        assertEquals(1, recoveryWarnings(after))
        assertEquals(Phase.OFF, recorder.phase)
    }

    @Test
    fun aDifferentRecoveryFailureProducesANewWarning() {
        val after = Logs.recent().lastOrNull()?.sequence ?: 0L
        onRecover = { throw IOException("Shell unavailable") }
        maintainNow()
        nowMs += 60_000L
        onRecover = { throw IOException("Could not launch the watchdog") }
        maintainNow()
        nowMs += 60_000L
        onRecover = { throw IllegalStateException("Could not launch the watchdog") }
        maintainNow()

        assertEquals(3, checks)
        assertEquals(3, recoveryWarnings(after))
    }

    @Test
    fun aSuccessfulRecoveryAllowsTheSameFailureToBeReportedAgain() {
        val after = Logs.recent().lastOrNull()?.sequence ?: 0L
        val failure = { throw IOException("Could not launch the watchdog") }
        onRecover = failure
        maintainNow()
        nowMs += 60_000L
        onRecover = {}
        recover = true
        reply = JSONObject().put("uptimeMs", 3_000)
        maintainNow()
        assertEquals(Phase.RUNNING, recorder.phase)

        nowMs += 60_000L
        onRecover = failure
        maintainNow()
        assertEquals(2, recoveryWarnings(after))
        assertEquals(Phase.RUNNING, recorder.phase)
        assertEquals(0, stops)
    }

    @Test
    fun explicitlyStartingTheRecorderAllowsANewRecoveryWarning() {
        val after = Logs.recent().lastOrNull()?.sequence ?: 0L
        onRecover = { throw IOException("Could not launch the watchdog") }
        maintainNow()
        reply = JSONObject().put("uptimeMs", 3_000)
        recorder.start()
        tasks.removeFirst().run()
        assertEquals(Phase.RUNNING, recorder.phase)

        nowMs += 60_000L
        maintainNow()
        assertEquals(2, recoveryWarnings(after))
        assertEquals(Phase.RUNNING, recorder.phase)
    }

    private fun maintainNow() {
        recorder.maintain()
        tasks.removeFirst().run()
    }

    private fun recoveryWarnings(after: Long): Int = Logs.recent().count {
        it.sequence > after && it.tag == "Recorder" &&
            it.message == "Could not restore the recorder watchdog"
    }
}
