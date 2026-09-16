package com.strike.daemon

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
}
