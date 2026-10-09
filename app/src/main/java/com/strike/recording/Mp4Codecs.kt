package com.strike.recording

import android.media.MediaFormat
import com.strike.daemon.AudioConfig

private const val AVC_SPS = 7
private const val AVC_PPS = 8
private const val HEVC_VPS = 32
private const val HEVC_SPS = 33
private const val HEVC_PPS = 34
private const val PROFILE_TIER_LEVEL_BYTES = 12

internal class UnreadableCodecHeader(message: String) : Exception(message)

internal class VideoTrack(val width: Int, val height: Int, val sampleEntry: ByteArray)

internal fun videoTrackOf(format: MediaFormat): VideoTrack {
    val mime = format.getString(MediaFormat.KEY_MIME)
    if (mime != MediaFormat.MIMETYPE_VIDEO_AVC && mime != MediaFormat.MIMETYPE_VIDEO_HEVC) {
        throw UnreadableCodecHeader("the encoder reports video as $mime")
    }
    if (!format.containsKey(MediaFormat.KEY_WIDTH) || !format.containsKey(MediaFormat.KEY_HEIGHT)) {
        throw UnreadableCodecHeader("the encoder reports no frame size")
    }
    val width = format.getInteger(MediaFormat.KEY_WIDTH)
    val height = format.getInteger(MediaFormat.KEY_HEIGHT)
    val csd0 = headerOf(format, "csd-0") ?: throw UnreadableCodecHeader("the encoder gave no codec header")
    return if (mime == MediaFormat.MIMETYPE_VIDEO_AVC) {
        avcTrack(width, height, csd0, headerOf(format, "csd-1") ?: ByteArray(0))
    } else {
        hevcTrack(width, height, csd0)
    }
}

internal fun headerOf(format: MediaFormat, key: String): ByteArray? {
    val header = format.getByteBuffer(key) ?: return null
    val bytes = ByteArray(header.remaining())
    header.duplicate().get(bytes)
    return bytes
}

internal fun avcTrack(width: Int, height: Int, csd0: ByteArray, csd1: ByteArray): VideoTrack {
    val units = nalUnits(csd0) + nalUnits(csd1)
    val sps = units.filter { it.isNotEmpty() && it[0].toInt() and 0x1F == AVC_SPS }
    val pps = units.filter { it.isNotEmpty() && it[0].toInt() and 0x1F == AVC_PPS }
    if (sps.isEmpty() || pps.isEmpty() || sps[0].size < 4) {
        throw UnreadableCodecHeader("the H.264 header has no usable SPS and PPS")
    }
    val config = Mp4Boxes(256).apply {
        box("avcC") {
            u8(1)
            u8(sps[0][1].toInt())
            u8(sps[0][2].toInt())
            u8(sps[0][3].toInt())
            u8(0xFF)
            u8(0xE0 or sps.size)
            for (unit in sps) parameterSet(unit)
            u8(pps.size)
            for (unit in pps) parameterSet(unit)
        }
    }
    return VideoTrack(width, height, videoEntry("avc1", width, height, config.toByteArray()))
}

internal fun hevcTrack(width: Int, height: Int, csd0: ByteArray): VideoTrack {
    val units = nalUnits(csd0)
    fun ofType(type: Int) = units.filter { it.size > 2 && (it[0].toInt() ushr 1) and 0x3F == type }
    val vps = ofType(HEVC_VPS)
    val sps = ofType(HEVC_SPS)
    val pps = ofType(HEVC_PPS)
    if (vps.isEmpty() || sps.isEmpty() || pps.isEmpty()) {
        throw UnreadableCodecHeader("the H.265 header has no usable VPS, SPS and PPS")
    }
    val header = HevcSps(unescaped(sps[0], 2))
    val config = Mp4Boxes(512).apply {
        box("hvcC") {
            u8(1)
            bytes(header.profileTierLevel)
            u16(0xF000)
            u8(0xFC)
            u8(0xFC or header.chromaFormat)
            u8(0xF8 or header.lumaBitDepthMinus8)
            u8(0xF8 or header.chromaBitDepthMinus8)
            u16(0)
            u8((header.subLayers shl 3) or (if (header.temporalIdNested) 1 shl 2 else 0) or 3)
            u8(3)
            for ((type, list) in listOf(HEVC_VPS to vps, HEVC_SPS to sps, HEVC_PPS to pps)) {
                u8(0x80 or type)
                u16(list.size)
                for (unit in list) parameterSet(unit)
            }
        }
    }
    return VideoTrack(width, height, videoEntry("hvc1", width, height, config.toByteArray()))
}

internal fun aacEntry(audio: AudioConfig): ByteArray {
    if (audio.csd.size < 2) throw UnreadableCodecHeader("the AAC header is ${audio.csd.size} bytes")
    if (audio.sampleRate !in 1..0xFFFF) throw UnreadableCodecHeader("AAC at ${audio.sampleRate} Hz")
    return Mp4Boxes(128).apply {
        box("mp4a") {
            zeros(6)
            u16(1)
            zeros(8)
            u16(audio.channelCount)
            u16(16)
            u32(0)
            u32(audio.sampleRate.toLong() shl 16)
            fullBox("esds", 0, 0) {
                val specific = audio.csd.size
                val decoder = 13 + 2 + specific
                u8(3)
                u8(3 + 2 + decoder + 3)
                u16(0)
                u8(0)
                u8(4)
                u8(decoder)
                u8(0x40)
                u8(0x15)
                u8(0)
                u16(0)
                u32(audio.bitrateBps)
                u32(audio.bitrateBps)
                u8(5)
                u8(specific)
                bytes(audio.csd)
                u8(6)
                u8(1)
                u8(2)
            }
        }
    }.toByteArray()
}

/** Rewrites Annex-B start codes as 4-byte lengths; in place when every start code is 4 bytes. */
internal fun lengthPrefixed(sample: ByteArray): ByteArray {
    val ranges = unitRanges(sample)
    if (ranges.isEmpty()) return withLength(sample)
    var inPlace = true
    var previousEnd = 0
    for (range in ranges) {
        if (range[0] - 4 != previousEnd || sample[previousEnd].toInt() != 0) inPlace = false
        previousEnd = range[1]
    }
    if (previousEnd != sample.size) inPlace = false
    if (inPlace) {
        for (range in ranges) putLength(sample, range[0] - 4, range[1] - range[0])
        return sample
    }
    val out = ByteArray(ranges.sumOf { 4 + it[1] - it[0] })
    var at = 0
    for (range in ranges) {
        val count = range[1] - range[0]
        putLength(out, at, count)
        System.arraycopy(sample, range[0], out, at + 4, count)
        at += 4 + count
    }
    return out
}

internal fun nalUnits(annexB: ByteArray): List<ByteArray> =
    unitRanges(annexB).map { annexB.copyOfRange(it[0], it[1]) }

// Emulation prevention keeps 00 00 01 out of NAL payloads, and NAL units never end in a zero byte.
private fun unitRanges(bytes: ByteArray): List<IntArray> {
    val payloads = ArrayList<Int>()
    var i = 0
    while (i + 2 < bytes.size) {
        if (bytes[i].toInt() == 0 && bytes[i + 1].toInt() == 0 && bytes[i + 2].toInt() == 1) {
            payloads.add(i + 3)
            i += 3
        } else i++
    }
    val ranges = ArrayList<IntArray>(payloads.size)
    for (k in payloads.indices) {
        val until = trimZeros(bytes, payloads[k], if (k + 1 < payloads.size) payloads[k + 1] - 3 else bytes.size)
        if (until > payloads[k]) ranges.add(intArrayOf(payloads[k], until))
    }
    return ranges
}

private fun trimZeros(bytes: ByteArray, from: Int, until: Int): Int {
    var end = until
    while (end > from && bytes[end - 1].toInt() == 0) end--
    return end
}

private fun withLength(unit: ByteArray): ByteArray {
    val out = ByteArray(unit.size + 4)
    putLength(out, 0, unit.size)
    System.arraycopy(unit, 0, out, 4, unit.size)
    return out
}

private fun putLength(bytes: ByteArray, at: Int, length: Int) {
    bytes[at] = (length ushr 24).toByte()
    bytes[at + 1] = (length ushr 16).toByte()
    bytes[at + 2] = (length ushr 8).toByte()
    bytes[at + 3] = length.toByte()
}

private fun Mp4Boxes.parameterSet(unit: ByteArray) {
    u16(unit.size)
    bytes(unit)
}

private fun videoEntry(type: String, width: Int, height: Int, config: ByteArray): ByteArray {
    if (width !in 1..0xFFFF || height !in 1..0xFFFF) throw UnreadableCodecHeader("a ${width}x$height frame")
    return Mp4Boxes(config.size + 128).apply {
        box(type) {
            zeros(6)
            u16(1)
            zeros(16)
            u16(width)
            u16(height)
            u32(0x00480000)
            u32(0x00480000)
            u32(0)
            u16(1)
            zeros(32)
            u16(0x0018)
            u16(0xFFFF)
            bytes(config)
        }
    }.toByteArray()
}

private fun unescaped(unit: ByteArray, from: Int): ByteArray {
    val out = ByteArray(unit.size - from)
    var count = 0
    var zeros = 0
    for (i in from until unit.size) {
        val b = unit[i].toInt() and 0xFF
        if (zeros >= 2 && b == 3) {
            zeros = 0
            continue
        }
        zeros = if (b == 0) zeros + 1 else 0
        out[count++] = b.toByte()
    }
    return out.copyOf(count)
}

private class HevcSps(rbsp: ByteArray) {
    val profileTierLevel: ByteArray
    val subLayers: Int
    val temporalIdNested: Boolean
    val chromaFormat: Int
    val lumaBitDepthMinus8: Int
    val chromaBitDepthMinus8: Int

    init {
        if (rbsp.size < 1 + PROFILE_TIER_LEVEL_BYTES) throw UnreadableCodecHeader("the H.265 SPS is ${rbsp.size} bytes")
        val bits = Bits(rbsp)
        bits.skip(4)
        val maxSubLayersMinus1 = bits.read(3)
        subLayers = maxSubLayersMinus1 + 1
        temporalIdNested = bits.read(1) == 1
        profileTierLevel = rbsp.copyOfRange(1, 1 + PROFILE_TIER_LEVEL_BYTES)
        bits.skip(PROFILE_TIER_LEVEL_BYTES * 8)
        val profilePresent = BooleanArray(maxSubLayersMinus1)
        val levelPresent = BooleanArray(maxSubLayersMinus1)
        for (i in 0 until maxSubLayersMinus1) {
            profilePresent[i] = bits.read(1) == 1
            levelPresent[i] = bits.read(1) == 1
        }
        if (maxSubLayersMinus1 > 0) bits.skip(2 * (8 - maxSubLayersMinus1))
        for (i in 0 until maxSubLayersMinus1) {
            if (profilePresent[i]) bits.skip(88)
            if (levelPresent[i]) bits.skip(8)
        }
        bits.golomb()
        chromaFormat = bits.golomb()
        if (chromaFormat > 3) throw UnreadableCodecHeader("H.265 chroma format $chromaFormat")
        if (chromaFormat == 3) bits.skip(1)
        bits.golomb()
        bits.golomb()
        if (bits.read(1) == 1) repeat(4) { bits.golomb() }
        lumaBitDepthMinus8 = bits.golomb()
        chromaBitDepthMinus8 = bits.golomb()
        if (lumaBitDepthMinus8 > 7 || chromaBitDepthMinus8 > 7) {
            throw UnreadableCodecHeader("H.265 bit depth ${lumaBitDepthMinus8 + 8}/${chromaBitDepthMinus8 + 8}")
        }
    }
}

private class Bits(private val bytes: ByteArray) {
    private var at = 0

    fun read(count: Int): Int {
        var value = 0
        repeat(count) {
            if (at >= bytes.size * 8) throw UnreadableCodecHeader("the H.265 SPS ends early")
            value = (value shl 1) or ((bytes[at ushr 3].toInt() ushr (7 - (at and 7))) and 1)
            at++
        }
        return value
    }

    fun skip(count: Int) {
        if (at + count > bytes.size * 8) throw UnreadableCodecHeader("the H.265 SPS ends early")
        at += count
    }

    fun golomb(): Int {
        var zeros = 0
        while (read(1) == 0) {
            if (++zeros > 31) throw UnreadableCodecHeader("the H.265 SPS is malformed")
        }
        return (1 shl zeros) - 1 + read(zeros)
    }
}
