package com.strike.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipRotateTest {

    @Test
    fun aSpliceSampleAlwaysOpensTheNextClip() {
        assertTrue(clipShouldRotate(true, 0L, 120_000L, false))
    }

    @Test
    fun aKeyframeAfterTheLengthOpensTheNextClipIfTheSpliceWasDropped() {
        assertTrue(clipShouldRotate(false, 120_000L, 120_000L, true))
    }

    @Test
    fun aKeyframeBeforeTheLengthDoesNotCutTheClipShort() {
        assertFalse(clipShouldRotate(false, 30_000L, 120_000L, true))
    }

    @Test
    fun lateAudioStartsAtTheNextKeyframeWithoutWaitingForTheClipLength() {
        assertTrue(clipShouldRotate(false, 1_000L, 120_000L, true, audioReady = true))
    }

    @Test
    fun lateAudioCannotStartOnADependentVideoFrame() {
        assertFalse(clipShouldRotate(false, 5_000L, 120_000L, false, audioReady = true))
    }

    @Test
    fun lateAudioCannotReuseTheCurrentClipsSecond() {
        assertFalse(clipShouldRotate(false, 999L, 120_000L, true, audioReady = true))
    }
}
