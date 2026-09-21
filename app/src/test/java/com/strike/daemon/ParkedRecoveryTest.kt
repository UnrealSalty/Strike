package com.strike.daemon

import com.strike.recording.sentryMode
import com.strike.vehicle.VehicleSnapshot
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.DataOutputStream
import java.io.File

class ParkedRecoveryTest {
    @get:Rule val folder = TemporaryFolder()
    private val saved: File get() = File(folder.root, "cam.recovery")
    private val stopped: File get() = File(folder.root, "cam.disabled")
    private var now = 1_000_000L
    private var wall = 1_700_000_000_000L

    private fun recovery(boot: String? = "boot-a", clock: () -> Long = { now }) =
        ParkedRecovery(saved, stopped, boot, nowMs = clock, wallNowMs = { wall })

    @Test
    fun eachParkedRecordingModeResumesOncePerDaemon() {
        for (mode in listOf("smart", "continuous")) {
            assertTrue(recovery().save(mode, "lock"))
            now += 3_000L
            val resumed = recovery()
            assertEquals(mode, resumed.resume(true, mode, "lock"))
            assertEquals(now + 180_000L, recovery().validUntilMs(true, mode, "lock"))
            val renewed = saved.readBytes()
            now += 3_000L
            assertNull(resumed.resume(true, mode, "lock"))
            assertArrayEquals(renewed, saved.readBytes())
            assertEquals(mode, recovery().resume(true, mode, "lock"))
        }
    }

    @Test
    fun acceptedResumeKeepsThePowerHoldersJournalUntilReplacementIsReady() {
        assertTrue(recovery().save("smart", "off"))
        val previousDeadline = now + 180_000L
        now += 60_000L
        val powerHolder = recovery()
        var observedBeforePublish = false
        val resumed = ParkedRecovery(saved, stopped, "boot-a", nowMs = { now }, wallNowMs = {
            assertTrue(saved.isFile)
            assertTrue(File(saved.path + ".tmp").isFile)
            assertEquals(previousDeadline, powerHolder.validUntilMs(true, "smart", "off"))
            observedBeforePublish = true
            wall
        })
        assertEquals("smart", resumed.resume(true, "smart", "off"))
        assertTrue(observedBeforePublish)
        assertEquals(now + 180_000L, powerHolder.validUntilMs(true, "smart", "off"))
        val renewed = saved.readBytes()
        now += 59_999L
        assertTrue(resumed.checkpoint("smart", "off"))
        assertArrayEquals(renewed, saved.readBytes())
    }

    @Test
    fun failedRenewalDoesNotEraseOrExtendThePreviousJournal() {
        assertTrue(recovery().save("smart", "off"))
        val previous = saved.readBytes()
        val previousDeadline = now + 180_000L
        now += 60_000L
        val temporary = File(saved.path + ".tmp")
        assertTrue(temporary.mkdir())
        val child = File(temporary, "busy")
        child.writeText("held")
        assertNull(recovery().resume(true, "smart", "off"))
        assertArrayEquals(previous, saved.readBytes())
        assertEquals(previousDeadline, recovery().validUntilMs(true, "smart", "off"))
        assertTrue(child.delete())
        assertTrue(temporary.delete())
        assertEquals("smart", recovery().resume(true, "smart", "off"))
    }

    @Test
    fun aManualStopDuringResumeCannotPublishRenewedIntent() {
        assertTrue(recovery().save("smart", "off"))
        val resumed = ParkedRecovery(saved, stopped, "boot-a", nowMs = { now }, wallNowMs = {
            stopped.writeText("stopped")
            wall
        })
        assertNull(resumed.resume(true, "smart", "off"))
        assertFalse(saved.exists())
        assertTrue(stopped.delete())
        assertNull(recovery().resume(true, "smart", "off"))
    }

    @Test
    fun recoveryAtTheDeadlineIsAccepted() {
        assertTrue(recovery().save("smart", "off"))
        now += 180_000L
        assertEquals("smart", recovery().resume(true, "smart", "off"))
    }

    @Test
    fun expiredIntentIsDiscarded() {
        assertTrue(recovery().save("smart", "off"))
        now += 180_001L
        assertNull(recovery().resume(true, "smart", "off"))
        assertFalse(saved.exists())
    }

    @Test
    fun eachParkedModeAndArmingConditionRenewsAfterAReboot() {
        for (mode in listOf("smart", "continuous")) for (arm in listOf("off", "lock")) {
            now = 1_000_000L
            assertTrue(recovery().save(mode, arm))
            now = 5_000L
            wall += 30_000L
            assertEquals(now + 150_000L, recovery("boot-b").validUntilMs(true, mode, arm))
            val resumed = recovery("boot-b")
            assertEquals(mode, resumed.resume(true, mode, arm))
            assertEquals(now + 180_000L, recovery("boot-b").validUntilMs(true, mode, arm))
            assertNull(resumed.resume(true, mode, arm))
            now += 3_000L
            assertEquals(mode, recovery("boot-b").resume(true, mode, arm))
        }
    }

    @Test
    fun anotherBootRejectsExpiredAndFutureWallTimestamps() {
        for (age in listOf(-1L, 180_001L)) {
            assertTrue(recovery().save("smart", "off"))
            wall += age
            assertNull(recovery("boot-b").validUntilMs(true, "smart", "off"))
            assertNull(recovery("boot-b").resume(true, "smart", "off"))
            assertFalse(saved.exists())
        }
    }

    @Test
    fun anotherBootAcceptsTheDeadlineRegardlessOfItsUptime() {
        for (uptime in listOf(1L, 2_000_000L)) {
            assertTrue(recovery().save("continuous", "lock"))
            now = uptime
            wall += 180_000L
            assertEquals(now, recovery("boot-b").validUntilMs(true, "continuous", "lock"))
            assertEquals("continuous", recovery("boot-b").resume(true, "continuous", "lock"))
        }
    }

    @Test
    fun wallClockChangesCannotShortenOrExtendSameBootRecovery() {
        for (jump in listOf(-86_400_000L, 86_400_000L)) {
            assertTrue(recovery().save("smart", "off"))
            wall += jump
            now += 60_000L
            assertEquals(now + 120_000L, recovery().validUntilMs(true, "smart", "off"))
            now += 120_001L
            assertNull(recovery().resume(true, "smart", "off"))
        }
    }

    @Test
    fun checkingAnotherBootsDeadlineDoesNotRenewTheRemainingTime() {
        assertTrue(recovery().save("smart", "off"))
        val original = saved.readBytes()
        now = 5_000L
        wall += 30_000L
        val expectedDeadline = now + 150_000L
        repeat(3) {
            now += 50_000L
            wall += 50_000L
            assertEquals(expectedDeadline, recovery("boot-b").validUntilMs(true, "smart", "off"))
            assertArrayEquals(original, saved.readBytes())
        }
        now++
        wall++
        assertNull(recovery("boot-b").validUntilMs(true, "smart", "off"))
    }

    @Test
    fun legacyJournalsRecoverOnlyWithinTheirOriginalBoot() {
        for (boot in listOf("boot-a", "boot-b")) {
            DataOutputStream(saved.outputStream()).use {
                it.writeInt(1)
                it.writeUTF("boot-a")
                it.writeLong(now - 60_000L)
                it.writeUTF("smart")
                it.writeUTF("off")
            }
            val handoff = recovery(boot)
            assertEquals(if (boot == "boot-a") now + 120_000L else null,
                handoff.validUntilMs(true, "smart", "off"))
            assertEquals(if (boot == "boot-a") "smart" else null, handoff.resume(true, "smart", "off"))
            assertEquals(boot == "boot-a", saved.exists())
        }
    }

    @Test
    fun recoveredIntentDoesNotInventVehicleReadingsOrOverrideFreshUse() {
        for (mode in listOf("smart", "continuous")) for (arm in listOf("off", "lock")) {
            assertTrue(recovery().save(mode, arm))
            val recovered = recovery("boot-b").resume(true, mode, arm)
            assertEquals(mode, recovered)
            assertNull(sentryMode(true, mode, null, arm, wasWatching = recovered != null))
            assertTrue(accUnsafe(null, now, now))
            val readings = mutableListOf(
                VehicleSnapshot(null, null, null, null, null, "P", true, null),
                VehicleSnapshot(null, null, null, null, null, "D", null, null)
            )
            if (arm == "lock") readings.add(VehicleSnapshot(null, null, null, null, null, "P", null, false))
            for (reading in readings) {
                assertEquals("off", sentryMode(true, mode, reading, arm, wasWatching = recovered != null))
            }
        }
    }

    @Test
    fun disabledSurveillanceDiscardsTheIntent() {
        assertTrue(recovery().save("smart", "off"))
        assertNull(recovery().resume(false, "smart", "off"))
        assertNull(recovery().resume(true, "smart", "off"))
    }

    @Test
    fun changedRecordingModeDiscardsTheIntent() {
        assertTrue(recovery().save("smart", "off"))
        assertNull(recovery().resume(true, "continuous", "off"))
        assertNull(recovery().resume(true, "smart", "off"))
    }

    @Test
    fun changedArmingConditionDiscardsTheIntent() {
        assertTrue(recovery().save("smart", "off"))
        assertNull(recovery().resume(true, "smart", "lock"))
        assertNull(recovery().resume(true, "smart", "off"))
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
        assertNull(recovery().resume(true, "continuous", "lock"))
        assertTrue(stopped.delete())
        assertNull(recovery().resume(true, "continuous", "lock"))
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
        assertNull(recovery().resume(true, "smart", "off"))
        assertFalse(saved.exists())
    }

    @Test
    fun aMissingBootIdentityCannotSaveOrResume() {
        assertFalse(recovery(null).save("smart", "off"))
        assertFalse(recovery("").save("smart", "off"))
        assertTrue(recovery().save("smart", "off"))
        assertNull(recovery(null).resume(true, "smart", "off"))
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
            assertNull(recovery().resume(true, "smart", "off"))
            assertFalse(saved.exists())
        }
    }

    @Test
    fun savingReplacesAnOlderIntent() {
        assertTrue(recovery().save("smart", "off"))
        now += 2_000L
        assertTrue(recovery().save("continuous", "lock"))
        assertEquals("continuous", recovery().resume(true, "continuous", "lock"))
    }

    @Test
    fun checkpointsKeepALongParkRecentWithoutRewritingEachPoll() {
        val handoff = recovery()
        assertTrue(handoff.checkpoint("smart", "off"))
        for (minute in 1..4) {
            val previous = saved.readBytes()
            now += 59_999L
            assertTrue(handoff.checkpoint("smart", "off"))
            assertArrayEquals(previous, saved.readBytes())
            now++
            assertTrue(handoff.checkpoint("smart", "off"))
            assertFalse(previous.contentEquals(saved.readBytes()))
        }
        now += 120_000L
        assertEquals("smart", recovery().resume(true, "smart", "off"))
    }

    @Test
    fun aChangedIntentIsSavedImmediately() {
        val handoff = recovery()
        assertTrue(handoff.checkpoint("smart", "off"))
        now++
        assertTrue(handoff.checkpoint("continuous", "lock"))
        assertEquals("continuous", recovery().resume(true, "continuous", "lock"))
    }

    @Test
    fun leavingParkClearsTheCheckpointAndAllowsANewParkImmediately() {
        val handoff = recovery()
        assertTrue(handoff.checkpoint("smart", "off"))
        File(saved.path + ".tmp").writeText("unfinished")
        assertTrue(handoff.checkpoint(null, null))
        assertFalse(saved.exists())
        assertFalse(File(saved.path + ".tmp").exists())
        assertNull(recovery().resume(true, "smart", "off"))
        now++
        assertTrue(handoff.checkpoint("smart", "off"))
        assertEquals("smart", recovery().resume(true, "smart", "off"))
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
        val handoff = ParkedRecovery(file, stopped, "boot-a", nowMs = { now }, wallNowMs = { wall })
        assertFalse(handoff.checkpoint("smart", "off"))
        assertTrue(dir.mkdir())
        now += 59_999L
        assertFalse(handoff.checkpoint("smart", "off"))
        assertFalse(file.exists())
        now++
        assertTrue(handoff.checkpoint("smart", "off"))
        assertEquals("smart", ParkedRecovery(file, stopped, "boot-a", nowMs = { now }, wallNowMs = { wall })
            .resume(true, "smart", "off"))
    }

    @Test
    fun aManualStopBlocksCheckpointRefreshAndResume() {
        val handoff = recovery()
        assertTrue(handoff.checkpoint("smart", "off"))
        stopped.writeText("stopped")
        now += 60_000L
        assertFalse(handoff.checkpoint("smart", "off"))
        assertNull(recovery().resume(true, "smart", "off"))
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
            assertArrayEquals(original, saved.readBytes())
        }
        assertEquals("smart", recovery().resume(true, "smart", "off"))
    }

    @Test
    fun anExpiredCheckpointCannotKeepRecoveryAwake() {
        assertTrue(recovery().save("smart", "off"))
        now += 180_001L
        assertNull(recovery().validUntilMs(true, "smart", "off"))
        assertTrue(saved.exists())
        assertNull(recovery().resume(true, "smart", "off"))
        assertFalse(saved.exists())
    }

    @Test
    fun deadlineChecksRejectDisabledChangedOrStoppedIntentAcrossBoots() {
        assertTrue(recovery().save("smart", "off"))
        for (boot in listOf("boot-a", "boot-b")) {
            assertNull(recovery(boot).validUntilMs(false, "smart", "off"))
            assertNull(recovery(boot).validUntilMs(true, "continuous", "off"))
            assertNull(recovery(boot).validUntilMs(true, "smart", "lock"))
            assertNull(recovery(boot).validUntilMs(true, "off", "off"))
            stopped.writeText("stopped")
            assertNull(recovery(boot).validUntilMs(true, "smart", "off"))
            assertTrue(stopped.delete())
        }
        assertNull(recovery(null).validUntilMs(true, "smart", "off"))
        assertTrue(saved.exists())
        assertEquals("smart", recovery().resume(true, "smart", "off"))
    }

    @Test
    fun aManualStopDuringDeadlineValidationIsRejected() {
        assertTrue(recovery().save("smart", "off"))
        val handoff = recovery(clock = {
            stopped.writeText("stopped")
            now
        })
        assertNull(handoff.validUntilMs(true, "smart", "off"))
        assertNull(recovery().resume(true, "smart", "off"))
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
            assertArrayEquals(bytes, saved.readBytes())
        }
    }

    @Test
    fun anUnavailableDirectoryFailsWithoutSavingAnIntent() {
        val file = File(folder.root, "missing/cam.recovery")
        assertFalse(ParkedRecovery(file, stopped, "boot-a", nowMs = { now }, wallNowMs = { wall }).save("smart", "off"))
        assertFalse(file.exists())
    }
}
