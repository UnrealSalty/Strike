package com.strike.recording

import android.media.MediaCodecInfo.CodecProfileLevel
import org.junit.Assert.assertEquals
import org.junit.Test

class EncoderTest {

    @Test
    fun theLevelCarriesTheFrameTheCameraActuallyProduces() {
        assertEquals(CodecProfileLevel.AVCLevel31, avcLevelFor(1280, 720))
        assertEquals(CodecProfileLevel.AVCLevel32, avcLevelFor(1280, 960))
        assertEquals(CodecProfileLevel.AVCLevel5, avcLevelFor(2560, 1920))
    }
}
