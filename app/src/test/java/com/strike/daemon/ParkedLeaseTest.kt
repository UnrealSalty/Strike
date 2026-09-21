package com.strike.daemon

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

class ParkedLeaseTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun anotherConsumersPowerClaimIsVisibleWithoutTakingPower() {
        val file = temporary.newFile()
        val camera = ParkedLease(file, 1)
        val online = ParkedLease(file, 2)
        assertFalse(camera.otherHeld)
        assertFalse(camera.isHeld)
        assertTrue(online.acquire())
        assertTrue(camera.otherHeld)
        assertFalse(camera.isHeld)
        assertTrue(online.release {})
        assertFalse(camera.otherHeld)
        FileChannel.open(file.toPath(), StandardOpenOption.WRITE).use { channel ->
            channel.tryLock(0, 3, false).use { assertNotNull(it) }
        }
    }

    @Test fun observingTheOtherConsumerKeepsOurOwnPowerClaim() {
        val file = temporary.newFile()
        val camera = ParkedLease(file, 1)
        val online = ParkedLease(file, 2)
        assertTrue(camera.acquire())
        assertFalse(camera.otherHeld)
        assertTrue(online.acquire())
        repeat(3) {
            assertTrue(camera.otherHeld)
            assertTrue(online.otherHeld)
        }
        assertTrue(camera.isHeld)
        assertTrue(online.release { fail("Camera still owns power") })
        assertFalse(camera.otherHeld)
        assertTrue(camera.isHeld)
        var released = false
        assertTrue(camera.release { released = true })
        assertTrue(released)
    }

    @Test fun stoppingEitherConsumerKeepsPowerUntilTheOtherReleases() {
        for (first in 1..2) {
            val file = temporary.newFile()
            val consumers = listOf(ParkedLease(file, 1), ParkedLease(file, 2))
            var releases = 0
            for (consumer in consumers) assertTrue(consumer.acquire())
            assertTrue(consumers[first - 1].release { releases++ })
            assertEquals(0, releases)
            assertTrue(consumers[2 - first].isHeld)
            assertTrue(consumers[2 - first].release { releases++ })
            assertEquals(1, releases)
        }
    }

    @Test fun anOccupiedConsumerSlotCannotBeStolenOrReleased() {
        val file = temporary.newFile()
        val camera = ParkedLease(file, 1)
        val duplicate = ParkedLease(file, 1)
        val online = ParkedLease(file, 2)
        assertTrue(camera.acquire())
        assertFalse(duplicate.acquire())
        assertFalse(duplicate.isHeld)
        assertTrue(duplicate.release { fail("An unsuccessful claim cannot release power") })
        assertTrue(online.acquire())
        assertTrue(online.release { fail("Camera still owns power") })
        var released = false
        assertTrue(camera.release { released = true })
        assertTrue(released)
        assertTrue(duplicate.acquire())
        assertTrue(duplicate.release {})
    }

    @Test fun acquiringWhileTheGateIsBusyCanRetryWithoutLeakingAClaim() {
        val file = temporary.newFile()
        val online = ParkedLease(file, 2)
        FileChannel.open(file.toPath(), StandardOpenOption.WRITE).use { channel ->
            channel.lock(0, 1, false).use {
                assertFalse(online.acquire())
                assertFalse(online.isHeld)
            }
        }
        assertTrue(online.acquire())
        assertTrue(online.release {})
    }

    @Test fun aNewClaimCannotOvertakeTheFinalHardwareRelease() {
        val file = temporary.newFile()
        val camera = ParkedLease(file, 1)
        val online = ParkedLease(file, 2)
        assertTrue(camera.acquire())
        assertTrue(camera.release {
            assertFalse(online.acquire())
        })
        assertTrue(online.acquire())
        assertTrue(online.release {})
    }

    @Test fun aCancelledHoldOnlyClearsHardwareAfterTheOtherConsumerLeaves() {
        val file = temporary.newFile()
        val camera = ParkedLease(file, 1)
        val online = ParkedLease(file, 2)
        assertTrue(camera.acquire())
        var powered = true
        assertTrue(online.acquire())
        assertTrue(camera.release { powered = false })
        assertTrue(powered)
        assertTrue(online.release { powered = false })
        assertFalse(powered)
    }

    @Test fun aFailedFinalHardwareReleaseStillClosesTheClaimAndGate() {
        val file = temporary.newFile()
        val camera = ParkedLease(file, 1)
        val replacement = ParkedLease(file, 1)
        assertTrue(camera.acquire())
        try {
            camera.release { throw IOException("Release failed") }
            fail("Expected the hardware failure")
        } catch (expected: IOException) { assertEquals("Release failed", expected.message) }
        assertFalse(camera.isHeld)
        assertTrue(replacement.acquire())
        assertTrue(replacement.release {})
    }
    @Test fun recoveryKeepsPowerAcrossCameraReplacementAndOnlineStop() {
        val file = temporary.newFile()
        val camera = ParkedLease(file, 1)
        val online = ParkedLease(file, 2)
        val recovery = ParkedLease(file, 3)
        var releases = 0
        assertTrue(camera.acquire())
        assertTrue(recovery.acquire())
        assertTrue(online.acquire())
        assertTrue(camera.release { releases++ })
        assertTrue(online.release { releases++ })
        assertEquals(0, releases)
        val replacement = ParkedLease(file, 1)
        assertTrue(replacement.acquire())
        assertTrue(recovery.release { releases++ })
        assertEquals(0, releases)
        assertTrue(replacement.release { releases++ })
        assertEquals(1, releases)
    }

    @Test fun everyOwnerReleaseOrderClearsPowerExactlyOnce() {
        for (first in 1..3) for (second in 1..3) {
            if (first == second) continue
            val last = 6 - first - second
            val file = temporary.newFile()
            val owners = (1..3).associateWith { ParkedLease(file, it) }
            owners.values.forEach { assertTrue(it.acquire()) }
            var releases = 0
            assertTrue(owners.getValue(first).release { releases++ })
            assertTrue(owners.getValue(second).release { releases++ })
            assertEquals(0, releases)
            assertTrue(owners.getValue(last).release { releases++ })
            assertEquals(1, releases)
        }
    }

    @Test fun recoveryDoesNotPretendTheOnlinePanelIsHeld() {
        val file = temporary.newFile()
        val camera = ParkedLease(file, 1)
        val recovery = ParkedLease(file, 3)
        assertTrue(recovery.acquire())
        assertTrue(camera.otherHeld)
        assertTrue(camera.heldBy(3))
        assertFalse(camera.heldBy(2))
        assertTrue(recovery.release {})
        assertFalse(camera.otherHeld)
    }

}
