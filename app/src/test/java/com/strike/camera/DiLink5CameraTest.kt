package com.strike.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DiLink5CameraTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun automaticAisSelectionDoesNotNeedLegacyCameraClasses() {
        val inventory = cameras(CameraProfile.AUTO, ais = true)
        assertSame(DILINK5_STRIP, roadCamera(inventory.cameras))
        assertEquals("", inventory.reason)
        assertEquals(CameraBackend.AIS, inventory.cameras.single().backend)
    }

    @Test
    fun anExplicitLegacyProfileKeepsItsExistingCameraAndGeometry() {
        for (profile in CameraProfile.entries) {
            val expected = profile.camera ?: continue
            for (ais in listOf(false, true)) {
                val selected = roadCamera(cameras(profile, ais).cameras)!!
                assertSame(expected, selected)
                assertEquals(CameraBackend.LEGACY, selected.backend)
            }
        }
    }

    @Test
    fun newerCaptureCannotUseASavedLegacyFallback() {
        val saved = folder.newFile("camera.raw").also { it.writeText("same-firmware") }
        val startup = CameraStartup(DILINK5_STRIP, true, saved, "same-firmware")
        assertSame(DILINK5_STRIP, startup.choice)
        assertFalse(startup.observe(0, 120_000))
        assertFalse(startup.observe(10, 1_000))
        assertFalse(startup.observe(0, 120_000))
        assertSame(DILINK5_STRIP, startup.choice)
        assertEquals("same-firmware", saved.readText())
    }

    @Test
    fun newerCaptureNeverWritesALegacyFallbackPreference() {
        val saved = File(folder.root, "camera.raw")
        val startup = CameraStartup(DILINK5_STRIP, true, saved, "firmware")
        startup.observe(0, 120_000)
        startup.observe(1, 1_000)
        assertFalse(saved.exists())
    }

    @Test
    fun theCanonicalStripFitsExistingRecordingAndLiveViews() {
        val camera = DILINK5_STRIP
        val all = frameOf(CameraView.ALL, camera.width, camera.height)
        assertEquals(1920, all.width)
        assertEquals(1300, all.height)
        for ((index, view) in listOf(CameraView.REAR, CameraView.LEFT, CameraView.RIGHT, CameraView.FRONT).withIndex()) {
            val frame = frameOf(view, camera.width, camera.height)
            assertEquals(960, frame.width)
            assertEquals(650, frame.height)
            val tile = tilesOf(view).single()
            assertEquals(index * 0.25f, tile.sourceX, 0.0001f)
            assertEquals(0.25f, tile.sourceWidth, 0.0001f)
        }
        val live = liveFrameOf(CameraView.ALL, camera.width, camera.height)
        assertFalse(live.width > 1280 || live.height > 960)
    }
}
