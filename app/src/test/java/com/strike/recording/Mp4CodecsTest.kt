package com.strike.recording

import com.strike.daemon.AudioConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class Mp4CodecsTest {

    @Test
    fun h264HeaderMatchesWhatFfmpegWritesForTheSameStream() {
        val entry = avcTrack(320, 240, AVC_SPS, AVC_PPS).sampleEntry
        assertArrayEquals(FFMPEG_AVCC, entry.copyOfRange(86, entry.size))
    }

    @Test
    fun h265HeaderMatchesWhatFfmpegWritesForTheSameStream() {
        val entry = hevcTrack(320, 240, HEVC_CSD).sampleEntry
        assertArrayEquals(FFMPEG_HVCC, entry.copyOfRange(86, entry.size))
    }

    @Test
    fun sampleEntryCarriesTheFrameSize() {
        val entry = avcTrack(2560, 1920, AVC_SPS, AVC_PPS).sampleEntry
        assertEquals("avc1", String(entry, 4, 4, Charsets.US_ASCII))
        assertEquals(2560, u32(entry, 30).toInt() and 0xFFFF)
        assertEquals(1920, u32(entry, 32).toInt() and 0xFFFF)
    }

    @Test
    fun parameterSetsInOneHeaderBufferAreBothFound() {
        val entry = avcTrack(320, 240, AVC_SPS + AVC_PPS, ByteArray(0)).sampleEntry
        assertArrayEquals(FFMPEG_AVCC, entry.copyOfRange(86, entry.size))
    }

    @Test(expected = UnreadableCodecHeader::class)
    fun anH264HeaderWithoutPpsIsUnreadable() {
        avcTrack(320, 240, AVC_SPS, ByteArray(0))
    }

    @Test(expected = UnreadableCodecHeader::class)
    fun anH265HeaderWithoutVpsIsUnreadable() {
        hevcTrack(320, 240, HEVC_CSD.copyOfRange(28, HEVC_CSD.size))
    }

    @Test(expected = UnreadableCodecHeader::class)
    fun aTruncatedH265SpsIsUnreadable() {
        hevcTrack(320, 240, hex("0000000140010c01ffff0160000003009000000300000300") + hex("00000001420101016000") + hex("000000014401c172b46240"))
    }

    @Test(expected = UnreadableCodecHeader::class)
    fun anEmptyAacHeaderIsUnreadable() {
        aacEntry(AudioConfig(48_000, 1, 64_000, ByteArray(0)))
    }

    @Test
    fun fourByteStartCodesBecomeLengthsWithoutACopy() {
        val sample = hex("00000001" + "65aabb" + "00000001" + "41cc")
        val converted = lengthPrefixed(sample)
        assertSame(sample, converted)
        assertArrayEquals(hex("00000003" + "65aabb" + "00000002" + "41cc"), converted)
    }

    @Test
    fun threeByteStartCodesAreRewrittenAsFourByteLengths() {
        val sample = hex("00000001" + "06ee" + "000001" + "65aabb")
        assertArrayEquals(hex("00000002" + "06ee" + "00000003" + "65aabb"), lengthPrefixed(sample))
    }

    @Test
    fun trailingZerosAfterAUnitAreNotPartOfIt() {
        val sample = hex("00000001" + "65aabb" + "0000")
        assertArrayEquals(hex("00000003" + "65aabb"), lengthPrefixed(sample))
    }

    @Test
    fun aSampleWithoutStartCodesIsOneUnit() {
        assertArrayEquals(hex("00000003" + "65aabb"), lengthPrefixed(hex("65aabb")))
    }
}
