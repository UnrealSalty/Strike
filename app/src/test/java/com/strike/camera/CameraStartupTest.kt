package com.strike.camera

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CameraStartupTest {
    @get:Rule val folder = TemporaryFolder()

    private val saved: File get() = File(folder.root, "camera.raw")
    private val legacy = requireNotNull(CameraProfile.SEAL.camera)

    private fun startup(
        initial: CameraChoice = legacy,
        automatic: Boolean = true,
        firmware: String = "firmware-a"
    ) = CameraStartup(initial, automatic, saved, firmware)

    @Test
    fun waitsForWarmupBeforeEscapingACameraThatNeverStreams() {
        val camera = startup()
        assertFalse(camera.observe(0, 6_000))
        assertFalse(camera.observe(0, 24_999))
        assertSame(legacy, camera.choice)
        assertTrue(camera.observe(0, 25_000))
        assertSame(RAW_STRIP, camera.choice)
        assertFalse(saved.exists())
    }

    @Test
    fun aFirstFrameAtTheDeadlineKeepsTheOriginalCamera() {
        val camera = startup()
        assertFalse(camera.observe(1, 25_000))
        assertSame(legacy, camera.choice)
        assertFalse(saved.exists())
    }

    @Test
    fun aWorkingCameraIsNotReplacedWhenSleepOrReopeningResetsItsCounter() {
        val camera = startup()
        camera.observe(12, 5_000)
        assertFalse(camera.observe(0, 60_000))
        assertSame(legacy, camera.choice)
    }

    @Test
    fun rawCameraIsTriedOnlyOnceEvenIfItAlsoFailsToOpenOrStream() {
        val camera = startup()
        assertTrue(camera.observe(0, 25_000))
        for (waitingMs in listOf(0L, 25_000L, 60_000L, 120_000L)) {
            assertFalse(camera.observe(0, waitingMs))
            assertSame(RAW_STRIP, camera.choice)
        }
        assertFalse(saved.exists())
    }

    @Test
    fun everyExplicitProfileKeepsItsCameraThroughStartupFailure() {
        for (profile in CameraProfile.entries) {
            val choice = profile.camera ?: continue
            val camera = startup(choice, automatic = false)
            assertFalse(camera.observe(0, 120_000))
            assertSame(choice, camera.choice)
        }
    }

    @Test
    fun attoThreeNeverProbesTheInvalidLegacyCameraOne() {
        val initial = requireNotNull(CameraProfile.ATTO_3.camera)
        val camera = startup(initial)
        assertFalse(camera.observe(0, 120_000))
        assertSame(initial, camera.choice)
    }

    @Test
    fun discoveredGeometryIsRetainedWhileItsCameraDeliversFrames() {
        val advertised = CameraChoice(2, "pano_h", 5120, 720)
        val camera = startup(advertised)
        camera.observe(8, 5_000)
        assertSame(advertised, camera.choice)
    }

    @Test
    fun rawRecoveryStartsDirectlyOnTheNextDaemonLaunchOnlyAfterFramesArrive() {
        val camera = startup()
        camera.observe(0, 25_000)
        assertSame(legacy, startup().choice)
        camera.observe(1, 2_000)
        assertSame(RAW_STRIP, startup().choice)
        assertEquals("firmware-a", saved.readText())
        assertFalse(File(saved.path + ".tmp").exists())
    }

    @Test
    fun aRememberedChoiceDoesNotOverrideAManualProfile() {
        saved.writeText("firmware-a")
        val tang = requireNotNull(CameraProfile.TANG_2022.camera)
        assertSame(tang, startup(tang, automatic = false).choice)
    }

    @Test
    fun changedFirmwareOrAnIncompleteSaveUsesDiscoveryAgain() {
        saved.writeText("firmware-a")
        assertSame(legacy, startup(firmware = "firmware-b").choice)
        saved.writeText("firm")
        assertSame(legacy, startup().choice)
    }

    @Test
    fun rawRecoveryCanReplaceAPreferenceFromOlderFirmware() {
        saved.writeText("firmware-old")
        val camera = startup()
        camera.observe(0, 25_000)
        camera.observe(1, 1_000)
        assertEquals("firmware-a", saved.readText())
        assertSame(RAW_STRIP, startup().choice)
    }

    @Test
    fun aFailedSaveDoesNotStopTheWorkingCameraOrRepeatWritesEveryFrame() {
        val blocked = folder.newFile("blocked")
        val camera = CameraStartup(legacy, true, File(blocked, "camera.raw"), "firmware-a")
        camera.observe(0, 25_000)
        assertFalse(camera.observe(1, 1_000))
        assertSame(RAW_STRIP, camera.choice)
        assertTrue(blocked.delete())
        assertTrue(blocked.mkdir())
        camera.observe(2, 2_000)
        assertFalse(File(blocked, "camera.raw").exists())
        assertFalse(camera.observe(0, 60_000))
        assertSame(RAW_STRIP, camera.choice)
    }
}
