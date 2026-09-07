package com.strike.surveillance

import com.strike.camera.Mosaic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SentryLumaTest {

    @Test
    fun allZeroFramesReadAsBlack() {
        val mosaic = Mosaic(8, 8, ByteArray(8 * 8 * 4), 0L)

        assertEquals(0, sampleLuma(mosaic))
    }

    @Test
    fun aLitFrameIsNotBlack() {
        val rgba = ByteArray(8 * 8 * 4) { 80.toByte() }
        val mosaic = Mosaic(8, 8, rgba, 0L)

        assertTrue(sampleLuma(mosaic) > 4)
    }
}
