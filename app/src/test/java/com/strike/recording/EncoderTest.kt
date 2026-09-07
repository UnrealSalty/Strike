package com.strike.recording

import android.media.MediaCodecInfo.CodecProfileLevel
import org.junit.Assert.assertEquals
import org.junit.Test

private const val ASKED_AT_MS = 1_000_000L

class EncoderTest {

    @Test
    fun nothingSplitsUntilARotationIsAskedFor() {
        assertEquals(false, splitsNow(keyFrame = true, askedAtMs = 0L, nowMs = ASKED_AT_MS))
    }

    @Test
    fun theClipEndsOnTheFirstKeyFrameAfterTheAsk() {
        assertEquals(false, splitsNow(keyFrame = false, askedAtMs = ASKED_AT_MS, nowMs = ASKED_AT_MS + 400))
        assertEquals(true, splitsNow(keyFrame = true, askedAtMs = ASKED_AT_MS, nowMs = ASKED_AT_MS + 400))
    }

    @Test
    fun aStreamWithNoKeyFramesStillSplitsEventually() {
        assertEquals(false, splitsNow(keyFrame = false, askedAtMs = ASKED_AT_MS, nowMs = ASKED_AT_MS + 2_999))
        assertEquals(true, splitsNow(keyFrame = false, askedAtMs = ASKED_AT_MS, nowMs = ASKED_AT_MS + 3_000))
    }

    @Test
    fun theLevelCarriesTheFrameTheCameraActuallyProduces() {
        assertEquals(CodecProfileLevel.AVCLevel31, avcLevelFor(1280, 720))
        assertEquals(CodecProfileLevel.AVCLevel32, avcLevelFor(1280, 960))
        assertEquals(CodecProfileLevel.AVCLevel5, avcLevelFor(2560, 1920))
    }
}
