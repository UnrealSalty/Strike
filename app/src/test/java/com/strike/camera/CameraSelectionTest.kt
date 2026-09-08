package com.strike.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class CameraSelectionTest {

    @Test
    fun unnamedFirmwareKeepsTheTestedCameraZeroFallback() {
        assertSame(RAW_STRIP, roadCamera(emptyList()))
        assertSame(RAW_STRIP, roadCamera(listOf(RAW_STRIP)))
        assertEquals(0, RAW_STRIP.id)
        assertEquals(5120, RAW_STRIP.width)
        assertEquals(960, RAW_STRIP.height)
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
}
