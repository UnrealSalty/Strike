package com.strike.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class CameraSelectionTest {

    @Test
    fun unnamedFirmwareUsesOverdrivesLegacyCamera() {
        val camera = roadCamera(emptyList())!!
        assertEquals(1, camera.id)
        assertEquals(5120, camera.width)
        assertEquals(960, camera.height)
    }

    @Test
    fun panoramicPriorityDoesNotDependOnDiscoveryOrder() {
        val low = CameraChoice(1, "pano_l", 5120, 720)
        val high = CameraChoice(0, "pano_h", 5120, 960)
        val front = CameraChoice(2, "front", 1280, 960)
        assertSame(high, roadCamera(listOf(front, low, high)))
        assertSame(low, roadCamera(listOf(front, low)))
    }

    @Test
    fun knownRoadTagsKeepTheirAdvertisedIdAndDimensions() {
        for (tag in listOf("pano_h", "pano_l", "byd_apa", "apa", "front")) {
            val road = CameraChoice(2, tag, 5120, 720)
            val cabin = CameraChoice(3, "dms", 1280, 720)
            assertSame(road, roadCamera(listOf(cabin, road)))
        }
    }

    @Test
    fun cabinAndUnrecognizedCamerasDoNotBecomeTheRoadCamera() {
        val found = listOf(
            CameraChoice(0, "dms", 1280, 720),
            CameraChoice(1, "face", 1280, 720),
            CameraChoice(2, "unrecognized", 5120, 960)
        )
        assertNull(roadCamera(found))
        assertNull(roadCamera(found.reversed()))
    }

    @Test
    fun aRearCameraAloneDoesNotReplaceThePanoramicSource() {
        assertNull(roadCamera(listOf(CameraChoice(1, "rear", 1280, 960))))
    }

    @Test
    fun discoveredPanoramaTakesPriorityOverModelDefaults() {
        val advertised = CameraChoice(2, "pano_h", 5120, 720)
        for (model in listOf(null, "BYD AUTO", "Atto 2", "Atto 3", "Seal 2026")) {
            assertSame(advertised, roadCamera(listOf(advertised), model))
        }
    }

    @Test
    fun explicitProfilesTakePriorityOverConflictingModelNames() {
        assertSame(RAW_STRIP, roadCamera(cameras(CameraProfile.ATTO_2).cameras, "BYD AUTO"))
        val seal = roadCamera(cameras(CameraProfile.SEAL).cameras, "Atto 2")!!
        assertEquals(1, seal.id)
    }
}
