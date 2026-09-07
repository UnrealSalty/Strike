package com.strike.daemon

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

class RecorderDaemonTest {

    private val tasks = ArrayDeque<Runnable>()
    private var nowMs = 0L
    private var launches = 0
    private var stops = 0
    private var watched = false
    private var authorised = true
    private var canHalt = true
    private var reply: JSONObject? = null
    private var prepare: () -> Unit = {}
    private var onRead: () -> Unit = {}
    private var onHalt: () -> Unit = {}
    private var onPause: () -> Unit = {}
    private val recorder = RecorderDaemon(
        authorise = { authorised },
        prepare = { prepare() },
        launchDaemon = { launches++; watched = true; true },
        haltDaemon = {
            stops++
            onHalt()
            if (canHalt) watched = false
            canHalt
        },
        readStatus = { val answer = reply; onRead(); answer },
        lastError = { "daemon exited with 137 after 1s" },
        worker = Executor { tasks.addLast(it) },
        nowMs = { nowMs },
        pause = { nowMs += it; onPause() }
    )

    @Test
    fun failedStartStopsRecoveryBeforeReportingFailure() {
        onHalt = { assertEquals(Phase.STARTING, recorder.phase) }
        recorder.start()
        tasks.removeFirst().run()

        assertEquals(Phase.FAILED, recorder.phase)
        assertEquals("daemon exited with 137 after 1s", recorder.failure)
        assertFalse(watched)
        assertFalse(recorder.canStop)
    }

    @Test
    fun repeatedOneSecondDaemonsNeverCountAsRunning() {
        reply = JSONObject().put("uptimeMs", 1_000)
        recorder.start()
        tasks.removeFirst().run()

        assertEquals(Phase.FAILED, recorder.phase)
        assertFalse(watched)
    }

    @Test
    fun pollingDuringStartCannotSkipTheStartupCheck() {
        reply = JSONObject().put("uptimeMs", 3_000)
        recorder.start()
        recorder.status()

        assertEquals(Phase.STARTING, recorder.phase)
        tasks.removeFirst().run()
        assertEquals(Phase.RUNNING, recorder.phase)
        assertTrue(watched)
    }

    @Test
    fun repeatedStartRequestsLaunchOnlyOneWatchdog() {
        reply = JSONObject().put("uptimeMs", 3_000)
        recorder.start()
        recorder.start()
        assertEquals(1, tasks.size)
        tasks.removeFirst().run()
        recorder.start()

        assertEquals(1, launches)
        assertTrue(tasks.isEmpty())
        assertEquals(Phase.RUNNING, recorder.phase)
    }

    @Test
    fun stopCancelsAStartBeforeItRuns() {
        recorder.start()
        recorder.stop()
        tasks.removeFirst().run()
        tasks.removeFirst().run()

        assertEquals(0, launches)
        assertEquals(Phase.OFF, recorder.phase)
        assertFalse(watched)
    }

    @Test
    fun stopWhilePublishingSettingsCannotLaunchAfterward() {
        prepare = { recorder.stop() }
        recorder.start()
        tasks.removeFirst().run()
        assertEquals(Phase.STOPPING, recorder.phase)
        tasks.removeFirst().run()

        assertEquals(0, launches)
        assertEquals(Phase.OFF, recorder.phase)
        assertFalse(watched)
    }

    @Test
    fun stopDuringTheStartupWaitKeepsItsIntent() {
        onPause = { recorder.stop() }
        recorder.start()
        tasks.removeFirst().run()
        assertEquals(Phase.STOPPING, recorder.phase)
        tasks.removeFirst().run()

        assertEquals(Phase.OFF, recorder.phase)
        assertFalse(watched)
        assertEquals("", recorder.failure)
    }

    @Test
    fun anOldStatusReplyCannotUndoStop() {
        reply = JSONObject().put("uptimeMs", 3_000)
        onRead = { recorder.stop() }

        assertNull(recorder.status())
        assertEquals(Phase.STOPPING, recorder.phase)
        tasks.removeFirst().run()
        assertEquals(Phase.OFF, recorder.phase)
    }

    @Test
    fun failedCleanupLeavesStopAvailable() {
        canHalt = false
        recorder.start()
        tasks.removeFirst().run()

        assertEquals(Phase.FAILED, recorder.phase)
        assertTrue(watched)
        assertTrue(recorder.canStop)
        canHalt = true
        recorder.stop()
        tasks.removeFirst().run()
        assertEquals(Phase.OFF, recorder.phase)
        assertFalse(watched)
        assertFalse(recorder.canStop)
    }

    @Test
    fun losingAnEstablishedDaemonWaitsForItsExistingWatchdog() {
        reply = JSONObject().put("uptimeMs", 3_000)
        recorder.start()
        tasks.removeFirst().run()
        reply = null
        recorder.status()
        assertEquals(Phase.STARTING, recorder.phase)
        reply = JSONObject().put("uptimeMs", 3_000)
        tasks.removeFirst().run()

        assertEquals(Phase.RUNNING, recorder.phase)
        assertEquals(1, launches)
        assertEquals(0, stops)
    }

    @Test
    fun failedRecoveryStopsTheWatchdog() {
        reply = JSONObject().put("uptimeMs", 3_000)
        recorder.start()
        tasks.removeFirst().run()
        reply = null
        recorder.status()
        tasks.removeFirst().run()

        assertEquals(Phase.FAILED, recorder.phase)
        assertFalse(watched)
        assertEquals(1, launches)
    }

    @Test
    fun deniedShellAccessDoesNotStartAnything() {
        authorised = false
        recorder.start()
        tasks.removeFirst().run()

        assertEquals(Phase.FAILED, recorder.phase)
        assertEquals(0, launches)
        assertEquals(0, stops)
        assertFalse(watched)
    }

    @Test
    fun watchdogExitReasonSurvivesLaterStartupChatter() {
        assertEquals(
            "daemon exited with 137 after 1s, waiting 3s",
            lastDaemonError(
                "1000 warn watchdog daemon exited with 137 after 1s, waiting 3s\n" +
                    "2000 debug Relay live packets on 127.0.0.1:19887"
            )
        )
    }
}
