package com.strike.recording

import java.nio.ByteBuffer

// Parameter sets from real x264 Baseline and x265 Main 320x240 streams, and what ffmpeg's MP4
// muxer writes for them.
internal val AVC_SPS = hex("000000016742c00cd90141fb0110000003001000000301e0f142a480")
internal val AVC_PPS = hex("0000000168cb83cb20")
internal val HEVC_CSD = hex(
    "0000000140010c01ffff01600000030090000003000003003c928090" +
        "0000000142010101600000030090000003000003003ca00a080f16592a4932bc05a020000003002000000301e1" +
        "000000014401c172b46240"
)
internal val FFMPEG_AVCC = hex(
    "00000030617663430142c00cffe100186742c00cd90141fb0110000003001000000301e0f142a48001000568cb83cb20"
)
internal val FFMPEG_HVCC = hex(
    "00000076687663430101600000009000000000003cf000fcfdf8f800000f03a00001001840010c01ffff016000000300" +
        "90000003000003003c928090a10001002942010101600000030090000003000003003ca00a080f16592a4932bc05a0" +
        "20000003002000000301e1a2000100074401c172b46240"
)

internal fun hex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

internal fun frame(index: Int, keyFrame: Boolean, payloadBytes: Int = 40): ByteArray {
    val fill = (index % 250 + 1).toByte()
    return byteArrayOf(0, 0, 0, 1, if (keyFrame) 0x65 else 0x41) + ByteArray(payloadBytes) { fill }
}

internal class ReadBox(val type: String, val at: Long, val size: Long, val headerBytes: Int) {
    val payloadAt: Long get() = at + headerBytes
}

internal class ReadTrack(
    val handler: String,
    val timescale: Long,
    val durationTicks: Long,
    val sizes: List<Int>,
    val offsets: List<Long>,
    val syncSamples: List<Int>?,
    val durations: List<Long>,
    val compositions: List<Long>?,
    val edits: List<Pair<Long, Long>>?,
    val wideOffsets: Boolean
)

internal fun topLevel(clip: ByteArray): List<ReadBox> = boxesIn(clip, 0, clip.size.toLong())

internal fun boxesIn(clip: ByteArray, from: Long, until: Long): List<ReadBox> {
    val boxes = ArrayList<ReadBox>()
    var at = from
    while (at + 8 <= until) {
        var size = u32(clip, at)
        var header = 8
        if (size == 1L) {
            size = u64(clip, at + 8)
            header = 16
        }
        if (size == 0L) size = until - at
        check(size >= header && at + size <= until) { "box at $at runs past its parent" }
        boxes.add(ReadBox(String(clip, at.toInt() + 4, 4, Charsets.US_ASCII), at, size, header))
        at += size
    }
    check(at == until) { "trailing bytes after the last box" }
    return boxes
}

internal fun child(clip: ByteArray, parent: ReadBox, type: String): ReadBox? =
    boxesIn(clip, parent.payloadAt, parent.at + parent.size).firstOrNull { it.type == type }

internal fun path(clip: ByteArray, from: ReadBox, vararg types: String): ReadBox? {
    var at: ReadBox = from
    for (type in types) at = child(clip, at, type) ?: return null
    return at
}

internal fun tracks(clip: ByteArray): List<ReadTrack> {
    val moov = topLevel(clip).first { it.type == "moov" }
    return boxesIn(clip, moov.payloadAt, moov.at + moov.size).filter { it.type == "trak" }.map { readTrack(clip, it) }
}

private fun readTrack(clip: ByteArray, trak: ReadBox): ReadTrack {
    val mdhd = path(clip, trak, "mdia", "mdhd")!!
    val hdlr = path(clip, trak, "mdia", "hdlr")!!
    val stbl = path(clip, trak, "mdia", "minf", "stbl")!!
    val stsz = child(clip, stbl, "stsz")!!
    val count = u32(clip, stsz.payloadAt + 8).toInt()
    val sizes = List(count) { u32(clip, stsz.payloadAt + 12 + 4L * it).toInt() }
    val stco = child(clip, stbl, "stco")
    val co64 = child(clip, stbl, "co64")
    val chunks = if (co64 != null) table(clip, co64, 8) { u64(clip, it) } else table(clip, stco!!, 4) { u32(clip, it) }
    val stsc = child(clip, stbl, "stsc")!!
    val runs = table(clip, stsc, 12) { longArrayOf(u32(clip, it), u32(clip, it + 4)) }
    val offsets = ArrayList<Long>()
    var sample = 0
    for (c in chunks.indices) {
        val perChunk = runs.last { it[0] <= c + 1 }[1].toInt()
        var at = chunks[c]
        repeat(perChunk) {
            offsets.add(at)
            at += sizes[sample++]
        }
    }
    check(sample == count) { "chunks hold $sample samples, stsz lists $count" }
    val durations = expand(table(clip, child(clip, stbl, "stts")!!, 8) { longArrayOf(u32(clip, it), u32(clip, it + 4)) })
    val compositions = child(clip, stbl, "ctts")?.let { box ->
        expand(table(clip, box, 8) { longArrayOf(u32(clip, it), u32(clip, it + 4)) })
    }
    val sync = child(clip, stbl, "stss")?.let { box -> table(clip, box, 4) { u32(clip, it).toInt() } }
    val edits = path(clip, trak, "edts", "elst")?.let { box ->
        table(clip, box, 12) { u32(clip, it) to u32(clip, it + 4) }
    }
    return ReadTrack(
        String(clip, hdlr.payloadAt.toInt() + 8, 4, Charsets.US_ASCII),
        u32(clip, mdhd.payloadAt + 12), u32(clip, mdhd.payloadAt + 16),
        sizes, offsets, sync, durations, compositions, edits, co64 != null
    )
}

private fun <T> table(clip: ByteArray, box: ReadBox, width: Int, read: (Long) -> T): List<T> {
    val entries = u32(clip, box.payloadAt + 4).toInt()
    return List(entries) { read(box.payloadAt + 8 + width.toLong() * it) }
}

private fun expand(runs: List<LongArray>): List<Long> = runs.flatMap { run -> List(run[0].toInt()) { run[1] } }

internal fun u32(clip: ByteArray, at: Long): Long = ByteBuffer.wrap(clip, at.toInt(), 4).int.toLong() and 0xFFFF_FFFFL

internal fun u64(clip: ByteArray, at: Long): Long = ByteBuffer.wrap(clip, at.toInt(), 8).long
