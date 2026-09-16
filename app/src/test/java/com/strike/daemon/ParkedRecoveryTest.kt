package com.strike.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ParkedRecoveryTest {
    @get:Rule val folder = TemporaryFolder()
    private val saved: File get() = File(folder.root, "cam.recovery")
    private val stopped: File get() = File(folder.root, "cam.disabled")
    private var now = 1_000_000L

    private fun recovery(boot: String? = "boot-a", clock: () -> Long = { now }) =
        ParkedRecovery(saved, stopped, boot, clock)

    @Test
    fun eachParkedRecordingModeResumesOnlyOnce() {
        for (mode in listOf("smart", "continuous")) {
            assertTrue(recovery().save(mode, "lock"))
            now += 3_000L
            assertEquals(mode, recovery().consume(true, mode, "lock"))
            assertFalse(saved.exists())
            assertNull(recovery().consume(true, mode, "lock"))
        }
    }

    @Test
    fun recoveryAtTheDeadlineIsAccepted() {
        assertTrue(recovery().save("smart", "off"))
        now += 180_000L
        assertEquals("smart", recovery().consume(true, "smart", "off"))
    }

    @Test
    fun expiredIntentIsDiscarded() {
        assertTrue(recovery().save("smart", "off"))
        now += 180_001L
        assertNull(recovery().consume(true, "smart", "off"))
        assertFalse(saved.exists())
    }

    @Test
    fun anotherBootCannotResumeThePreviousPark() {
        assertTrue(recovery().save("smart", "off"))
        assertNull(recovery("boot-b").consume(true, "smart", "off"))
        assertFalse(saved.exists())
    }

    @Test
    fun disabledSurveillanceDiscardsTheIntent() {
        assertTrue(recovery().save("smart", "off"))
        assertNull(recovery().consume(false, "smart", "off"))
        assertNull(recovery().consume(true, "smart", "off"))
    }

    @Test
    fun changedRecordingModeDiscardsTheIntent() {
        assertTrue(recovery().save("smart", "off"))
        assertNull(recovery().consume(true, "continuous", "off"))
        assertNull(recovery().consume(true, "smart", "off"))
    }

    @Test
    fun changedArmingConditionDiscardsTheIntent() {
        assertTrue(recovery().save("smart", "off"))
        assertNull(recovery().consume(true, "smart", "lock"))
        assertNull(recovery().consume(true, "smart", "off"))
    }

    @Test
    fun aManualStopPreventsSaving() {
        stopped.writeText("stopped")
        assertFalse(recovery().save("smart", "off"))
        assertFalse(saved.exists())
    }

    @Test
    fun aManualStopAfterSavingDiscardsTheIntent() {
        assertTrue(recovery().save("continuous", "lock"))
        stopped.writeText("stopped")
        assertNull(recovery().consume(true, "continuous", "lock"))
        assertTrue(stopped.delete())
        assertNull(recovery().consume(true, "continuous", "lock"))
    }

    @Test
    fun aManualStopDuringSavingDiscardsTheIntent() {
        val handoff = recovery(clock = {
            stopped.writeText("stopped")
            now
        })
        assertFalse(handoff.save("smart", "off"))
        assertFalse(saved.exists())
    }

    @Test
    fun aFutureTimestampCannotResume() {
        assertTrue(recovery().save("smart", "off"))
        now--
        assertNull(recovery().consume(true, "smart", "off"))
        assertFalse(saved.exists())
    }

    @Test
    fun aMissingBootIdentityCannotSaveOrResume() {
        assertFalse(recovery(null).save("smart", "off"))
        assertFalse(recovery("").save("smart", "off"))
        assertTrue(recovery().save("smart", "off"))
        assertNull(recovery(null).consume(true, "smart", "off"))
        assertFalse(saved.exists())
    }

    @Test
    fun nonParkedModesAndInvalidArmingConditionsCannotSave() {
        assertFalse(recovery().save("off", "off"))
        assertFalse(recovery().save("drive", "off"))
        assertFalse(recovery().save("smart", "unknown"))
        assertFalse(saved.exists())
    }

    @Test
    fun truncatedAndOversizedIntentIsDiscarded() {
        for (bytes in listOf(byteArrayOf(0, 0, 0, 1), ByteArray(257))) {
            saved.writeBytes(bytes)
            assertNull(recovery().consume(true, "smart", "off"))
            assertFalse(saved.exists())
        }
    }

    @Test
    fun savingReplacesAnOlderIntent() {
        assertTrue(recovery().save("smart", "off"))
        now += 2_000L
        assertTrue(recovery().save("continuous", "lock"))
        assertEquals("continuous", recovery().consume(true, "continuous", "lock"))
    }

    @Test
    fun checkpointsKeepALongParkRecentWithoutRewritingEachPoll() {
        val handoff = recovery()
        assertTrue(handoff.checkpoint("smart", "off"))
        for (minute in 1..4) {
            val previous = saved.readBytes()
            now += 59_999L
            assertTrue(handoff.checkpoint("smart", "off"))
            org.junit.Assert.assertArrayEquals(previous, saved.readBytes())
            now++
            assertTrue(handoff.checkpoint("smart", "off"))
            assertFalse(previous.contentEquals(saved.readBytes()))
        }
        now += 120_000L
        assertEquals("smart", recovery().consume(true, "smart", "off"))
    }

    @Test
    fun aChangedIntentIsSavedImmediately() {
        val handoff = recovery()
        assertTrue(handoff.checkpoint("smart", "off"))
        now++
        assertTrue(handoff.checkpoint("continuous", "lock"))
        assertEquals("continuous", recovery().consume(true, "continuous", "lock"))
    }

    @Test
    fun leavingParkClearsTheCheckpointAndAllowsANewParkImmediately() {
        val handoff = recovery()
        assertTrue(handoff.checkpoint("smart", "off"))
        File(saved.path + ".tmp").writeText("unfinished")
        assertTrue(handoff.checkpoint(null, null))
        assertFalse(saved.exists())
        assertFalse(File(saved.path + ".tmp").exists())
        assertNull(recovery().consume(true, "smart", "off"))
        now++
        assertTrue(handoff.checkpoint("smart", "off"))
        assertEquals("smart", recovery().consume(true, "smart", "off"))
    }

    @Test
    fun anInactiveFirstCheckpointClearsAnExistingIntent() {
        assertTrue(recovery().save("smart", "off"))
        assertTrue(recovery().checkpoint(null, null))
        assertFalse(saved.exists())
    }

    @Test
    fun failedCheckpointsRetryOnceAMinute() {
        val dir = File(folder.root, "unavailable")
        val file = File(dir, "cam.recovery")
        val handoff = ParkedRecovery(file, stopped, "boot-a") { now }
        assertFalse(handoff.checkpoint("smart", "off"))
        assertTrue(dir.mkdir())
        now += 59_999L
        assertFalse(handoff.checkpoint("smart", "off"))
        assertFalse(file.exists())
        now++
        assertTrue(handoff.checkpoint("smart", "off"))
        assertEquals("smart", ParkedRecovery(file, stopped, "boot-a") { now }
            .consume(true, "smart", "off"))
    }

    @Test
    fun aManualStopBlocksCheckpointRefreshAndResume() {
        val handoff = recovery()
        assertTrue(handoff.checkpoint("smart", "off"))
        stopped.writeText("stopped")
        now += 60_000L
        assertFalse(handoff.checkpoint("smart", "off"))
        assertNull(recovery().consume(true, "smart", "off"))
        assertFalse(saved.exists())
    }

    @Test
    fun aManualStopDuringCheckpointWriteDiscardsTheIntent() {
        var clockReads = 0
        val handoff = recovery(clock = {
            if (++clockReads == 2) stopped.writeText("stopped")
            now
        })
        assertFalse(handoff.checkpoint("smart", "off"))
        assertFalse(saved.exists())
    }

    @Test
    fun failedCheckpointRemovalIsRetried() {
        assertTrue(saved.mkdir())
        val child = File(saved, "busy")
        child.writeText("held")
        val handoff = recovery()
        assertFalse(handoff.checkpoint(null, null))
        assertTrue(child.delete())
        now += 59_999L
        assertFalse(handoff.checkpoint(null, null))
        assertTrue(saved.exists())
        now++
        assertTrue(handoff.checkpoint(null, null))
        assertFalse(saved.exists())
    }

    @Test
    fun checkingTheDeadlineDoesNotConsumeOrExtendTheCheckpoint() {
        assertTrue(recovery().save("smart", "off"))
        val expectedDeadline = now + 180_000L
        val original = saved.readBytes()
        repeat(3) {
            now += 60_000L
            assertEquals(expectedDeadline, recovery().validUntilMs(true, "smart", "off"))
            org.junit.Assert.assertArrayEquals(original, saved.readBytes())
        }
        assertEquals("smart", recovery().consume(true, "smart", "off"))
    }

    @Test
    fun anExpiredCheckpointCannotKeepRecoveryAwake() {
        assertTrue(recovery().save("smart", "off"))
        now += 180_001L
        assertNull(recovery().validUntilMs(true, "smart", "off"))
        assertTrue(saved.exists())
        assertNull(recovery().consume(true, "smart", "off"))
        assertFalse(saved.exists())
    }

    @Test
    fun deadlineChecksRejectDisabledChangedOrForeignIntent() {
        assertTrue(recovery().save("smart", "off"))
        assertNull(recovery().validUntilMs(false, "smart", "off"))
        assertNull(recovery().validUntilMs(true, "continuous", "off"))
        assertNull(recovery().validUntilMs(true, "smart", "lock"))
        assertNull(recovery().validUntilMs(true, "off", "off"))
        assertNull(recovery("boot-b").validUntilMs(true, "smart", "off"))
        assertNull(recovery(null).validUntilMs(true, "smart", "off"))
        assertTrue(saved.exists())
        assertEquals("smart", recovery().consume(true, "smart", "off"))
    }

    @Test
    fun aManualStopDuringDeadlineValidationIsRejected() {
        assertTrue(recovery().save("smart", "off"))
        val handoff = recovery(clock = {
            stopped.writeText("stopped")
            now
        })
        assertNull(handoff.validUntilMs(true, "smart", "off"))
        assertNull(recovery().consume(true, "smart", "off"))
    }

    @Test
    fun futureAndMalformedCheckpointsCannotKeepRecoveryAwake() {
        assertNull(recovery().validUntilMs(true, "smart", "off"))
        assertTrue(recovery().save("smart", "off"))
        now--
        assertNull(recovery().validUntilMs(true, "smart", "off"))
        for (bytes in listOf(byteArrayOf(0, 0, 0, 1), ByteArray(257))) {
            saved.writeBytes(bytes)
            assertNull(recovery().validUntilMs(true, "smart", "off"))
            org.junit.Assert.assertArrayEquals(bytes, saved.readBytes())
        }
    }

    @Test
    fun anUnavailableDirectoryFailsWithoutSavingAnIntent() {
        val file = File(folder.root, "missing/cam.recovery")
        assertFalse(ParkedRecovery(file, stopped, "boot-a") { now }.save("smart", "off"))
        assertFalse(file.exists())
    }
}
