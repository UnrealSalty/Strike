package com.strike.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RecorderRecoveryPowerTest {
    @get:Rule val folder = TemporaryFolder()
    private var now = 1_000_000L
    private var deadline: Long? = null
    private val acquired = mutableListOf<Long>()
    private var releases = 0

    private fun power(readDeadline: () -> Long? = { deadline }) = RecorderRecoveryPower(
        readDeadline, { acquired.add(it) }, { releases++ }, { now }
    )

    @Test
    fun noParkedIntentDoesNotAcquirePower() {
        val power = power()
        repeat(4) { assertTrue(power.refresh()) }
        assertTrue(power.close())
        assertTrue(acquired.isEmpty())
        assertEquals(0, releases)
    }

    @Test
    fun anUnchangedCheckpointDoesNotReacquireOrExtendTheHold() {
        deadline = now + 180_000L
        val power = power()
        assertTrue(power.refresh())
        repeat(11) {
            now += 15_000L
            assertTrue(power.refresh())
        }
        assertEquals(listOf(180_000L), acquired)
        now += 15_000L
        assertTrue(power.refresh())
        assertEquals(1, releases)
        assertTrue(power.refresh())
        assertEquals(1, releases)
    }

    @Test
    fun aNewCheckpointRenewsOnlyUntilItsOwnDeadline() {
        deadline = now + 180_000L
        val power = power()
        assertTrue(power.refresh())
        now += 60_000L
        deadline = now + 180_000L
        now += 15_000L
        assertTrue(power.refresh())
        assertEquals(listOf(180_000L, 165_000L), acquired)
        assertTrue(power.close())
        assertEquals(1, releases)
    }

    @Test
    fun disablingOrChangingSurveillanceReleasesTheHold() {
        val file = File(folder.root, "cam.recovery")
        val stopped = File(folder.root, "cam.disabled")
        val recovery = ParkedRecovery(file, stopped, "boot-a") { now }
        assertTrue(recovery.save("smart", "off"))
        var enabled = true
        var mode = "smart"
        val power = power { recovery.validUntilMs(enabled, mode, "off") }
        assertTrue(power.refresh())
        enabled = false
        assertTrue(power.refresh())
        assertEquals(1, releases)
        enabled = true
        assertTrue(power.refresh())
        mode = "continuous"
        assertTrue(power.refresh())
        assertEquals(2, releases)
    }

    @Test
    fun manualStopReleasesEvenWhenTheCheckpointIsStillFresh() {
        val file = File(folder.root, "cam.recovery")
        val stopped = File(folder.root, "cam.disabled")
        val recovery = ParkedRecovery(file, stopped, "boot-a") { now }
        assertTrue(recovery.save("smart", "off"))
        val power = power { recovery.validUntilMs(true, "smart", "off") }
        assertTrue(power.refresh())
        stopped.writeText("stopped")
        assertTrue(power.refresh())
        assertEquals(1, releases)
        assertTrue(power.refresh())
        assertEquals(listOf(180_000L), acquired)
    }

    @Test
    fun clearingTheParkedCheckpointReleasesTheHold() {
        val file = File(folder.root, "cam.recovery")
        val recovery = ParkedRecovery(file, File(folder.root, "cam.disabled"), "boot-a") { now }
        assertTrue(recovery.checkpoint("smart", "lock"))
        val power = power { recovery.validUntilMs(true, "smart", "lock") }
        assertTrue(power.refresh())
        assertTrue(recovery.checkpoint(null, null))
        assertTrue(power.refresh())
        assertEquals(1, releases)
    }

    @Test
    fun aFailedAcquireRetriesWithoutPretendingPowerIsHeld() {
        var attempts = 0
        val power = RecorderRecoveryPower({ now + 180_000L }, {
            if (++attempts == 1) throw SecurityException("denied")
            acquired.add(it)
        }, { releases++ }, { now })
        assertFalse(power.refresh())
        assertTrue(power.refresh())
        assertEquals(listOf(180_000L), acquired)
        assertTrue(power.close())
        assertEquals(1, releases)
    }

    @Test
    fun aFailedReleaseCanRetryButClosingPreventsAnotherAcquire() {
        deadline = now + 180_000L
        var attempts = 0
        val power = RecorderRecoveryPower({ deadline }, { acquired.add(it) }, {
            if (++attempts == 1) throw IllegalStateException("unavailable")
            releases++
        }, { now })
        assertTrue(power.refresh())
        assertFalse(power.close())
        assertTrue(power.close())
        now += 60_000L
        deadline = now + 180_000L
        assertTrue(power.refresh())
        assertEquals(listOf(180_000L), acquired)
        assertEquals(1, releases)
    }

    @Test
    fun closingDuringRefreshReleasesItsHoldAndCannotReacquire() {
        val acquiring = CountDownLatch(1)
        val finishAcquire = CountDownLatch(1)
        val closing = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val power = RecorderRecoveryPower({ now + 180_000L }, {
            acquiring.countDown()
            check(finishAcquire.await(5, TimeUnit.SECONDS))
            acquired.add(it)
        }, { releases++ }, { now })
        try {
            val refresh = executor.submit<Boolean> { power.refresh() }
            assertTrue(acquiring.await(5, TimeUnit.SECONDS))
            val close = executor.submit<Boolean> {
                closing.countDown()
                power.close()
            }
            assertTrue(closing.await(5, TimeUnit.SECONDS))
            finishAcquire.countDown()
            assertTrue(refresh.get(5, TimeUnit.SECONDS))
            assertTrue(close.get(5, TimeUnit.SECONDS))
            assertTrue(power.refresh())
            assertEquals(listOf(180_000L), acquired)
            assertEquals(1, releases)
        } finally {
            finishAcquire.countDown()
            executor.shutdownNow()
        }
    }
}
