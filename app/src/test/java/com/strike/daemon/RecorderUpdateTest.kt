package com.strike.daemon

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.Executor

class RecorderUpdateTest {
    private var running = true
    private var canFinish = true
    private var now = 0L
    private var forcedStops = 0
    private var resumeCalls = 0
    private var savedIntent: Boolean? = null
    private val recorder = RecorderDaemon({ true }, {}, { true }, { forcedStops++; true },
        { if (running) JSONObject().put("uptimeMs", 3000) else null }, { null },
        Executor { it.run() }, { now }, { now += it },
        haltForUpdate = {
            assertNotNull(savedIntent)
            if (canFinish) running = false
            canFinish
        }, resumeDaemon = { resumeCalls++; true })

    @Test
    fun savingThePreviousStatePrecedesFinalizingTheClip() {
        recorder.pauseForUpdate { savedIntent = it }
        assertEquals(true, savedIntent)
        assertFalse(running)
        assertEquals(Phase.OFF, recorder.phase)
        assertEquals(0, forcedStops)
    }

    @Test
    fun aStoppedRecorderIsNotMarkedForRestart() {
        running = false
        recorder.pauseForUpdate { savedIntent = it }
        assertEquals(false, savedIntent)
        assertEquals(0, forcedStops)
    }

    @Test
    fun aClipThatCannotFinishAbortsTheUpdateWithoutBeingKilled() {
        canFinish = false
        assertThrows(IOException::class.java) { recorder.pauseForUpdate { savedIntent = it } }
        assertTrue(running)
        assertEquals(Phase.FAILED, recorder.phase)
        assertTrue(recorder.canStop)
        assertEquals(0, forcedStops)
        recorder.resumeAfterUpdate()
        assertEquals(Phase.RUNNING, recorder.phase)
        assertEquals(1, resumeCalls)
        assertEquals(0, forcedStops)
    }

    @Test
    fun aSlowRestartCannotForceKillTheCameraAfterAnUpdate() {
        running = false
        recorder.resumeAfterUpdate()
        assertEquals(Phase.FAILED, recorder.phase)
        assertEquals(0, forcedStops)
    }

    @Test
    fun aSlowRestartKeepsStopAvailableForTheWatchdog() {
        running = false
        recorder.resumeAfterUpdate()
        assertEquals(Phase.FAILED, recorder.phase)
        assertTrue(recorder.canStop)
        recorder.stop()
        assertEquals(Phase.OFF, recorder.phase)
        assertFalse(recorder.canStop)
    }

    @Test
    fun aFailedJournalWriteLeavesTheRecorderUntouched() {
        assertThrows(IOException::class.java) { recorder.pauseForUpdate { throw IOException("Disk full") } }
        assertTrue(running)
        assertEquals(0, forcedStops)
    }
}
