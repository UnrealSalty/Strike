package com.strike.daemon

import com.strike.vehicle.VehicleSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class ParkedPowerSessionTest {
    @get:Rule val folder = TemporaryFolder()
    private val now = AtomicLong(1_000L)
    @Volatile private var settings = ParkedPowerSettings(true, "smart", "off")
    @Volatile private var desired = true
    private val stopped get() = File(folder.root, "cam.disabled")
    private val camera get() = journal("cam.recovery")
    private val owner get() = journal("cam.parked-owner")

    private fun journal(name: String) = ParkedRecovery(File(folder.root, name), stopped, "boot-a",
        nowMs = now::get, wallNowMs = { 1_700_000_000_000L + now.get() })

    private fun session(readVehicle: () -> VehicleSnapshot? = { null }): ParkedPowerSession =
        ParkedPowerSession({ settings }, { desired && !stopped.exists() }, { current ->
            camera.validUntilMs(current.enabled, current.mode, current.arm) != null ||
                owner.validUntilMs(current.enabled, current.mode, current.arm) != null
        }, owner, readVehicle, now::get)

    private fun snapshot(on: Boolean?, gear: String? = "P", locked: Boolean? = true) =
        VehicleSnapshot(null, null, null, null, null, gear, on, locked)

    @Test
    fun independentOwnerRemainsResumableAfterTheCameraCheckpointExpires() {
        assertTrue(camera.save("smart", "off"))
        val session = session()
        assertNotNull(session.refresh())
        repeat(10) {
            now.addAndGet(60_000L)
            assertEquals(now.get() + 180_000L, session.refresh())
        }
        assertNull(camera.validUntilMs(true, "smart", "off"))
        assertEquals(now.get() + 180_000L, owner.validUntilMs(true, "smart", "off"))
        assertNotNull(session().refresh())
    }

    @Test
    fun unknownVehicleStateCannotInventAParkedSessionWithoutRecentIntent() {
        val session = session()
        repeat(10) {
            assertNull(session.refresh())
            now.addAndGet(60_000L)
        }
        assertNull(owner.validUntilMs(true, "smart", "off"))
    }

    @Test
    fun confirmedVehicleOffCanStartAnIndependentOwnerWithoutACameraCheckpoint() {
        val session = session { snapshot(false) }
        assertNull(session.refresh())
        now.addAndGet(15_000L)
        assertNotNull(session.refresh())
        assertNotNull(owner.validUntilMs(true, "smart", "off"))
    }

    @Test
    fun newIgnitionOnEndsTheOwnerAndDoesNotReuseTheOlderCameraCheckpoint() {
        assertTrue(camera.save("smart", "off"))
        var vehicle: VehicleSnapshot? = null
        val session = session { vehicle }
        assertNotNull(session.refresh())
        now.addAndGet(15_000L)
        vehicle = snapshot(true)
        assertNull(session.refresh())
        assertNull(owner.validUntilMs(true, "smart", "off"))
        assertNotNull(camera.validUntilMs(true, "smart", "off"))
        now.addAndGet(15_000L)
        vehicle = null
        assertNull(session.refresh())
    }

    @Test
    fun aDrivingGearEndsParkedPowerEvenWhenIgnitionIsUnknown() {
        assertTrue(camera.save("smart", "off"))
        var vehicle: VehicleSnapshot? = null
        val session = session { vehicle }
        assertNotNull(session.refresh())
        now.addAndGet(15_000L)
        vehicle = snapshot(null, "D")
        assertNull(session.refresh())
        assertNull(owner.validUntilMs(true, "smart", "off"))
    }

    @Test
    fun unlockingEndsALockArmedOwnerEvenWhenIgnitionIsUnknown() {
        settings = settings.copy(arm = "lock")
        assertTrue(camera.save("smart", "lock"))
        var vehicle: VehicleSnapshot? = null
        val session = session { vehicle }
        assertNotNull(session.refresh())
        now.addAndGet(15_000L)
        vehicle = snapshot(null, locked = false)
        assertNull(session.refresh())
        assertNull(owner.validUntilMs(true, "smart", "lock"))
    }

    @Test
    fun anInitiallyStoppedRecorderCanLaterAdoptFreshParkedIntent() {
        assertTrue(camera.save("smart", "off"))
        desired = false
        val session = session()
        assertNull(session.refresh())
        desired = true
        assertNotNull(session.refresh())
        assertNotNull(owner.validUntilMs(true, "smart", "off"))
    }

    @Test
    fun anExpiredOwnerCannotStartAgainAfterBothProcessesStopRefreshing() {
        assertTrue(camera.save("smart", "off"))
        assertNotNull(session().refresh())
        now.addAndGet(180_001L)
        assertNull(session().refresh())
        assertNull(owner.validUntilMs(true, "smart", "off"))
    }

    @Test
    fun repeatedDeadlineChecksDoNotRepollHardwareButStillRespectStop() {
        val reads = AtomicInteger()
        assertTrue(camera.save("smart", "off"))
        val session = session { reads.incrementAndGet(); null }
        repeat(20) { assertNotNull(session.refresh()) }
        assertTrue(reads.get() <= 1)
        stopped.writeText("stopped")
        assertNull(session.refresh())
        assertNull(owner.validUntilMs(true, "smart", "off"))
    }

    @Test
    fun stopDuringABlockedVehicleReadPreventsALateCheckpoint() =
        stopWhileReading { stopped.writeText("stopped") }

    @Test
    fun settingsChangedDuringABlockedVehicleReadPreventALateCheckpoint() =
        stopWhileReading { settings = settings.copy(mode = "continuous") }

    @Test
    fun ignitionBroadcastEndsTheOwnerWithoutAvailableHardwareReadings() {
        assertTrue(camera.save("smart", "off"))
        val session = session()
        assertNotNull(session.refresh())
        session.edge(true)
        repeat(10) {
            assertNull(session.refresh())
            now.addAndGet(15_000L)
        }
        assertNull(owner.validUntilMs(true, "smart", "off"))
    }

    @Test
    fun ignitionOnWhileReadingRecoveryIntentCannotRestoreParkedPower() {
        val reading = CountDownLatch(1)
        val finishReading = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        assertTrue(camera.save("smart", "off"))
        val session = ParkedPowerSession({ settings }, { desired && !stopped.exists() }, { current ->
            reading.countDown()
            check(finishReading.await(5, TimeUnit.SECONDS))
            camera.validUntilMs(current.enabled, current.mode, current.arm) != null
        }, owner, { null }, now::get)
        try {
            val refresh = executor.submit<Long?> { session.refresh() }
            assertTrue(reading.await(5, TimeUnit.SECONDS))
            session.edge(true)
            finishReading.countDown()
            assertNull(refresh.get(5, TimeUnit.SECONDS))
            assertFalse(File(folder.root, "cam.parked-owner").exists())
            assertNull(session.refresh())
        } finally {
            finishReading.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun ignitionOnDuringABlockedVehicleReadPreventsALateCheckpoint() =
        stopWhileReading { it.edge(true) }

    private fun stopWhileReading(stop: (ParkedPowerSession) -> Unit) {
        val reading = CountDownLatch(1)
        val finishReading = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        assertTrue(camera.save("smart", "off"))
        val session = session {
            reading.countDown()
            check(finishReading.await(5, TimeUnit.SECONDS))
            snapshot(false)
        }
        try {
            assertNotNull(session.refresh())
            now.addAndGet(15_000L)
            val refresh = executor.submit<Long?> { session.refresh() }
            assertTrue(reading.await(5, TimeUnit.SECONDS))
            stop(session)
            finishReading.countDown()
            assertNull(refresh.get(5, TimeUnit.SECONDS))
            assertFalse(File(folder.root, "cam.parked-owner").exists())
            assertNull(owner.validUntilMs(true, "smart", "off"))
            assertNull(owner.validUntilMs(true, "continuous", "off"))
        } finally {
            finishReading.countDown()
            executor.shutdownNow()
        }
    }
}
