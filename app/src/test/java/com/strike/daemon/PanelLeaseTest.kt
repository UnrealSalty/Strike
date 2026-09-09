package com.strike.daemon

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

class PanelLeaseTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun cameraWaitsUntilOnlineReleasesItsPanelAndThenKeepsPriority() {
        val file = temporary.newFile()
        val online = PanelLease(file, false)
        val camera = PanelLease(file, true)
        try {
            assertTrue(online.acquire())
            assertFalse(online.cameraWaiting)
            assertFalse(camera.acquire())
            assertFalse(camera.isHeld)
            assertTrue(online.isHeld)
            assertTrue(online.cameraWaiting)
            assertFalse(camera.acquire())
            online.release()
            assertFalse(online.isHeld)
            assertFalse(online.acquire())
            assertTrue(camera.acquire())
            assertTrue(camera.isHeld)
        } finally { camera.close(); online.close() }
    }

    @Test fun cameraReservationSurvivesIdleAndPanelReleaseForItsNextPreview() {
        val file = temporary.newFile()
        val camera = PanelLease(file, true)
        val online = PanelLease(file, false)
        try {
            assertTrue(camera.acquire())
            camera.release()
            assertFalse(camera.isHeld)
            assertTrue(online.cameraWaiting)
            assertFalse(online.acquire())
            assertTrue(camera.acquire())
            camera.close()
            assertFalse(online.cameraWaiting)
            assertTrue(online.acquire())
        } finally { camera.close(); online.close() }
    }

    @Test fun closingEitherWaitingOrOwningCameraAllowsOnlineToContinue() {
        for (onlineFirst in listOf(true, false)) {
            val file = temporary.newFile()
            val camera = PanelLease(file, true)
            val online = PanelLease(file, false)
            try {
                if (onlineFirst) assertTrue(online.acquire())
                assertEquals(!onlineFirst, camera.acquire())
                camera.close()
                assertFalse(camera.isHeld)
                assertFalse(online.cameraWaiting)
                assertTrue(online.acquire())
            } finally { camera.close(); online.close() }
        }
    }

    @Test fun anUnsuccessfulOwnerCannotReleaseAnotherProcessesPanel() {
        val file = temporary.newFile()
        val online = PanelLease(file, false)
        val camera = PanelLease(file, true)
        val replacement = PanelLease(file, false)
        try {
            assertTrue(online.acquire())
            assertFalse(camera.acquire())
            camera.release()
            assertTrue(online.isHeld)
            camera.close()
            assertFalse(replacement.acquire())
            online.close()
            assertTrue(replacement.acquire())
        } finally { camera.close(); online.close(); replacement.close() }
    }

    @Test fun aBusyGateCanRetryWithoutLeavingAReservation() {
        val file = temporary.newFile()
        val camera = PanelLease(file, true)
        val online = PanelLease(file, false)
        try {
            FileChannel.open(file.toPath(), StandardOpenOption.WRITE).use { channel ->
                channel.lock(0, 1, false).use {
                    assertFalse(camera.acquire())
                    assertFalse(camera.isHeld)
                    assertFalse(online.cameraWaiting)
                }
            }
            assertTrue(camera.acquire())
            assertTrue(online.cameraWaiting)
        } finally { camera.close(); online.close() }
    }

    @Test fun anOpenFailureDoesNotPreventRetryingAfterTheDirectoryExists() {
        val directory = temporary.root.resolve("panel")
        val camera = PanelLease(directory.resolve("lease"), true)
        try {
            try {
                camera.acquire()
                fail("Expected the missing directory")
            } catch (expected: IOException) {
                assertFalse(camera.isHeld)
            }
            assertTrue(directory.mkdir())
            assertTrue(camera.acquire())
            camera.close()
            FileChannel.open(directory.resolve("lease").toPath(), StandardOpenOption.WRITE).use { channel ->
                channel.tryLock(0, 3, false).use { assertNotNull(it) }
            }
        } finally { camera.close() }
    }
}
