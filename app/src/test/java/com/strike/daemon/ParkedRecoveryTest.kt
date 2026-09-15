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
        now += 120_000L
        assertEquals("smart", recovery().consume(true, "smart", "off"))
    }

    @Test
    fun expiredIntentIsDiscarded() {
        assertTrue(recovery().save("smart", "off"))
        now += 120_001L
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
    fun anUnavailableDirectoryFailsWithoutSavingAnIntent() {
        val file = File(folder.root, "missing/cam.recovery")
        assertFalse(ParkedRecovery(file, stopped, "boot-a") { now }.save("smart", "off"))
        assertFalse(file.exists())
    }
}
