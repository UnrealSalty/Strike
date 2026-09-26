package com.strike.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class RecoveryPowerWorkerTest {
    @Test
    fun blockedHardwareDoesNotBlockRequestsOrGrowThePendingWork() {
        val acquiring = CountDownLatch(1)
        val finishAcquire = CountDownLatch(1)
        val maintainedTwice = CountDownLatch(2)
        val maintenance = AtomicInteger()
        val acquisitions = AtomicInteger()
        val now = AtomicLong(1_000L)
        val power = RecorderRecoveryPower({ 181_000L }, {
            acquisitions.incrementAndGet()
            acquiring.countDown()
            check(finishAcquire.await(5, TimeUnit.SECONDS))
        }, {}, now::get, {
            maintenance.incrementAndGet()
            maintainedTwice.countDown()
        })
        val worker = RecoveryPowerWorker(power, now::get)
        val caller = Executors.newSingleThreadExecutor()
        try {
            worker.request()
            assertTrue(acquiring.await(5, TimeUnit.SECONDS))
            caller.submit {
                repeat(10_000) {
                    worker.request()
                    assertEquals(1_000L, worker.snapshot().inFlightSinceMs)
                }
            }.get(2, TimeUnit.SECONDS)
            assertEquals(1, acquisitions.get())
            assertEquals(0, maintenance.get())
            finishAcquire.countDown()
            assertTrue(maintainedTwice.await(5, TimeUnit.SECONDS))
            assertTrue(worker.awaitClosed(2_000L))
            assertEquals(1, acquisitions.get())
            assertEquals(2, maintenance.get())
        } finally {
            finishAcquire.countDown()
            worker.awaitClosed(2_000L)
            caller.shutdownNow()
        }
    }

    @Test
    fun queuedSdkCallbacksRunBetweenCoalescedPowerRefreshes() {
        val acquiring = CountDownLatch(1)
        val finishAcquire = CountDownLatch(1)
        val callbackRan = AtomicInteger()
        val maintenance = AtomicInteger()
        val afterCallback = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val power = RecorderRecoveryPower({ 181_000L }, {
            acquiring.countDown()
            check(finishAcquire.await(5, TimeUnit.SECONDS))
        }, {}, { 1_000L }, {
            if (maintenance.incrementAndGet() == 2 && callbackRan.get() == 1) afterCallback.countDown()
        })
        val worker = RecoveryPowerWorker(power, { 1_000L }, executor)
        try {
            worker.request()
            assertTrue(acquiring.await(5, TimeUnit.SECONDS))
            worker.request()
            executor.execute { callbackRan.set(1) }
            finishAcquire.countDown()
            assertTrue(afterCallback.await(5, TimeUnit.SECONDS))
        } finally {
            finishAcquire.countDown()
            worker.awaitClosed(2_000L)
            executor.shutdownNow()
        }
    }

    @Test
    fun aHandoffWaitEndsEvenWhenThePowerServiceNeverAnswers() {
        val acquiring = CountDownLatch(1)
        val finishAcquire = CountDownLatch(1)
        val power = RecorderRecoveryPower({ 181_000L }, {
            acquiring.countDown()
            check(finishAcquire.await(5, TimeUnit.SECONDS))
        }, {}, { 1_000L })
        val worker = RecoveryPowerWorker(power, { 1_000L })
        val caller = Executors.newSingleThreadExecutor()
        try {
            worker.request()
            assertTrue(acquiring.await(5, TimeUnit.SECONDS))
            assertFalse(caller.submit<Boolean> { worker.awaitHeld(25L) }.get(2, TimeUnit.SECONDS))
            assertNotNull(worker.snapshot().inFlightSinceMs)
        } finally {
            finishAcquire.countDown()
            worker.awaitClosed(2_000L)
            caller.shutdownNow()
        }
    }

    @Test
    fun closingDuringAcquisitionReleasesTheLateHoldWithoutMaintenance() {
        val acquiring = CountDownLatch(1)
        val finishAcquire = CountDownLatch(1)
        val hardwareHeld = AtomicInteger()
        val maintenance = AtomicInteger()
        val releases = AtomicInteger()
        val reads = AtomicInteger()
        val power = RecorderRecoveryPower({ reads.incrementAndGet(); 181_000L }, {
            acquiring.countDown()
            check(finishAcquire.await(5, TimeUnit.SECONDS))
            hardwareHeld.set(1)
        }, {
            hardwareHeld.set(0)
            releases.incrementAndGet()
        }, { 1_000L }, { maintenance.incrementAndGet() })
        val worker = RecoveryPowerWorker(power, { 1_000L })
        try {
            worker.request()
            assertTrue(acquiring.await(5, TimeUnit.SECONDS))
            assertFalse(worker.close())
            repeat(1_000) { worker.request() }
            assertFalse(worker.awaitClosed(0L))
            finishAcquire.countDown()
            assertTrue(worker.awaitClosed(2_000L))
            assertEquals(0, hardwareHeld.get())
            assertEquals(1, releases.get())
            assertEquals(0, maintenance.get())
            assertEquals(1, reads.get())
            assertFalse(worker.awaitHeld(0L))
        } finally {
            finishAcquire.countDown()
            worker.awaitClosed(2_000L)
        }
    }

    @Test
    fun manualStopWhileHardwareIsBlockedCancelsTheLateAcquisition() {
        val acquiring = CountDownLatch(1)
        val finishAcquire = CountDownLatch(1)
        val deadline = AtomicReference<Long?>(181_000L)
        val releases = AtomicInteger()
        val maintenance = AtomicInteger()
        val power = RecorderRecoveryPower(deadline::get, {
            acquiring.countDown()
            check(finishAcquire.await(5, TimeUnit.SECONDS))
        }, { releases.incrementAndGet() }, { 1_000L }, { maintenance.incrementAndGet() })
        val worker = RecoveryPowerWorker(power, { 1_000L })
        try {
            worker.request()
            assertTrue(acquiring.await(5, TimeUnit.SECONDS))
            deadline.set(null)
            worker.request()
            finishAcquire.countDown()
            assertFalse(worker.awaitHeld(2_000L))
            assertEquals(1, releases.get())
            assertEquals(0, maintenance.get())
            assertFalse(worker.snapshot().held)
        } finally {
            finishAcquire.countDown()
            worker.awaitClosed(2_000L)
        }
    }

    @Test
    fun closeStillReturnsWhileHardwareMaintenanceIsBlocked() {
        val maintaining = CountDownLatch(1)
        val finishMaintenance = CountDownLatch(1)
        val held = AtomicInteger()
        val power = RecorderRecoveryPower({ 181_000L }, { held.set(1) }, { held.set(0) }, { 1_000L }, {
            maintaining.countDown()
            check(finishMaintenance.await(5, TimeUnit.SECONDS))
        })
        val worker = RecoveryPowerWorker(power, { 1_000L })
        val caller = Executors.newSingleThreadExecutor()
        try {
            worker.request()
            assertTrue(maintaining.await(5, TimeUnit.SECONDS))
            assertFalse(caller.submit<Boolean> { worker.close() }.get(2, TimeUnit.SECONDS))
            finishMaintenance.countDown()
            assertTrue(worker.awaitClosed(2_000L))
            assertEquals(0, held.get())
        } finally {
            finishMaintenance.countDown()
            worker.awaitClosed(2_000L)
            caller.shutdownNow()
        }
    }

    @Test
    fun failedCheckpointReadsDoNotKillTheWorkerOrHideTheFailure() {
        val reads = AtomicInteger()
        val power = RecorderRecoveryPower({
            if (reads.incrementAndGet() == 1) throw IllegalStateException("unavailable")
            181_000L
        }, {}, {}, { 1_000L })
        val worker = RecoveryPowerWorker(power, { 1_000L })
        try {
            assertFalse(worker.awaitHeld(2_000L))
            assertEquals("IllegalStateException", worker.snapshot().failure)
            assertTrue(worker.awaitHeld(2_000L))
            assertTrue(worker.snapshot().ready)
            assertEquals(1_000L, worker.snapshot().lastCompletedAtMs)
        } finally {
            assertTrue(worker.awaitClosed(2_000L))
        }
    }

    @Test
    fun aFailedCloseCanRetryWithoutAcquiringAgain() {
        val acquisitions = AtomicInteger()
        val releases = AtomicInteger()
        val power = RecorderRecoveryPower({ 181_000L }, { acquisitions.incrementAndGet() }, {
            if (releases.incrementAndGet() == 1) throw IllegalStateException("service unavailable")
        }, { 1_000L })
        val worker = RecoveryPowerWorker(power, { 1_000L })
        try {
            assertTrue(worker.awaitHeld(2_000L))
            assertFalse(worker.awaitClosed(2_000L))
            assertTrue(worker.snapshot().held)
            assertTrue(worker.awaitClosed(2_000L))
            worker.request()
            assertFalse(worker.awaitHeld(0L))
            assertEquals(1, acquisitions.get())
            assertEquals(2, releases.get())
        } finally {
            worker.awaitClosed(2_000L)
        }
    }

    @Test
    fun closingAnUnusedWorkerDoesNotAcquirePower() {
        val acquisitions = AtomicInteger()
        val releases = AtomicInteger()
        val power = RecorderRecoveryPower({ 181_000L }, { acquisitions.incrementAndGet() },
            { releases.incrementAndGet() }, { 1_000L })
        val worker = RecoveryPowerWorker(power, { 1_000L })
        assertTrue(worker.awaitClosed(2_000L))
        assertEquals(0, acquisitions.get())
        assertEquals(0, releases.get())
    }
}
