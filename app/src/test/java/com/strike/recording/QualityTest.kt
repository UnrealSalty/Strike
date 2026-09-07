package com.strike.recording

import android.media.MediaFormat
import org.junit.Assert.assertEquals
import org.junit.Test

class QualityTest {

    @Test
    fun theLadderRisesWithQuality() {
        var previous = 0
        for (quality in RecordingSettings.choices.getValue(RecordingSettings.QUALITY).options) {
            val bps = bitrateBps(quality, "h265")
            assertEquals(true, bps > previous)
            previous = bps
        }
    }

    @Test
    fun h264CostsHalfAgainMoreThanH265() {
        assertEquals(2_000_000, bitrateBps("standard", "h265"))
        assertEquals(3_000_000, bitrateBps("standard", "h264"))
    }

    @Test
    fun anUnknownQualityFallsBackToStandard() {
        assertEquals(bitrateBps("standard", "h265"), bitrateBps("cinema", "h265"))
    }

    @Test
    fun codecNamesMapToMimeTypes() {
        assertEquals(MediaFormat.MIMETYPE_VIDEO_HEVC, mimeTypeOf("h265"))
        assertEquals(MediaFormat.MIMETYPE_VIDEO_AVC, mimeTypeOf("h264"))
    }
}
