package com.strike.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class CameraProfileTest {

    @Test
    fun sealUsesTheLegacyPanoramaWithoutNeedingCameraTags() {
        val inventory = cameras(CameraProfile.SEAL)
        val camera = roadCamera(inventory.cameras)!!
        assertEquals("", inventory.reason)
        assertEquals(1, camera.id)
        assertEquals(5120, camera.width)
        assertEquals(960, camera.height)
        val mosaic = frameOf(CameraView.ALL, camera.width, camera.height)
        assertEquals(2560, mosaic.width)
        assertEquals(1920, mosaic.height)
    }

    @Test
    fun attoThreeNeverInheritsTheLegacyCameraOne() {
        val camera = roadCamera(cameras(CameraProfile.ATTO_3).cameras)!!
        assertEquals(0, camera.id)
        assertEquals(5120, camera.width)
        assertEquals(960, camera.height)
    }

    @Test
    fun tangUsesCameraTwoAndItsShorterStripForEncoding() {
        val camera = roadCamera(cameras(CameraProfile.TANG_2022).cameras)!!
        assertEquals(2, camera.id)
        assertEquals(5120, camera.width)
        assertEquals(720, camera.height)
        val mosaic = frameOf(CameraView.ALL, camera.width, camera.height)
        val front = frameOf(CameraView.FRONT, camera.width, camera.height)
        assertEquals(2560, mosaic.width)
        assertEquals(1440, mosaic.height)
        assertEquals(1280, front.width)
        assertEquals(720, front.height)
    }

    @Test
    fun attoTwoKeepsItsTestedCameraAndGeometry() {
        assertSame(RAW_STRIP, roadCamera(cameras(CameraProfile.ATTO_2).cameras))
    }

    @Test
    fun automaticKeepsDiscoveredDimensionsAndUsesLegacyWhenNothingIsNamed() {
        assertNull(CameraProfile.AUTO.camera)
        val advertised = CameraChoice(3, "pano_h", 5120, 720)
        assertSame(advertised, roadCamera(listOf(advertised)))
        assertSame(CameraProfile.SEAL.camera, roadCamera(emptyList()))
    }

    @Test
    fun ambiguousModelNamesDoNotSelectACamera() {
        for (name in listOf(null, "", "BYD AUTO", "Seal 2026", "sealion7", "dilink5", "seal")) {
            assertNull(CameraProfile.of(name))
        }
    }

    @Test
    fun genericHeadUnitsAndSealUseTheLegacyFallback() {
        for (model in listOf(null, "", "BYD AUTO", "unknown", "BYD Seal 2026")) {
            val camera = roadCamera(emptyList(), model)!!
            assertEquals(1, camera.id)
            assertEquals(5120, camera.width)
            assertEquals(960, camera.height)
        }
    }

    @Test
    fun anIdentifiedAttoTwoRetainsCameraZero() {
        for (model in listOf("Atto 2", "BYD ATTO-2", "byd_atto_2")) {
            assertSame(RAW_STRIP, roadCamera(emptyList(), model))
        }
    }

    @Test
    fun attoThreeAndYuanPlusUseOverdrivesCameraZeroException() {
        for (model in listOf("Atto 3", "BYD ATTO-3", "byd_atto_3", "Yuan Plus", "yuan_plus")) {
            val camera = roadCamera(emptyList(), model)!!
            assertEquals(0, camera.id)
            assertEquals(5120, camera.width)
            assertEquals(960, camera.height)
        }
    }
}
