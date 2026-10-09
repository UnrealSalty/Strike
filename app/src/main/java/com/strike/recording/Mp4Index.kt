package com.strike.recording

private const val MOVIE_TIMESCALE = 1000
private const val UINT32_MAX = 0xFFFF_FFFFL
private const val LONE_SAMPLE_FRACTION = 30

// Worst case per sample: stsz, stts, ctts, stss, co64 and an stsc run each.
internal const val VIDEO_INDEX_BYTES_PER_SAMPLE = 4 + 8 + 8 + 4 + 8 + 12
internal const val AUDIO_INDEX_BYTES_PER_SAMPLE = 4 + 8 + 8 + 12
internal const val INDEX_FIXED_BYTES = 4096

internal class TrackIndex(val timescale: Int, val sampleEntry: ByteArray, val isVideo: Boolean) {

    var count = 0
        private set

    private var sizes = IntArray(1024)
    private var offsets = LongArray(1024)
    private var timesUs = LongArray(1024)
    private var sync = BooleanArray(1024)

    val firstTimeUs: Long get() = timesUs[0]

    fun add(offsetBytes: Long, sizeBytes: Int, timeUs: Long, isSync: Boolean) {
        if (count == sizes.size) {
            val grown = sizes.size * 2
            sizes = sizes.copyOf(grown)
            offsets = offsets.copyOf(grown)
            timesUs = timesUs.copyOf(grown)
            sync = sync.copyOf(grown)
        }
        sizes[count] = sizeBytes
        offsets[count] = offsetBytes
        timesUs[count] = timeUs
        sync[count] = isSync
        count++
    }

    internal fun timing(): Timing {
        val n = count
        val presentedUs = timesUs.copyOf(n)
        var monotonic = true
        for (i in 1 until n) if (presentedUs[i] < presentedUs[i - 1]) monotonic = false
        val decodedUs = if (monotonic) presentedUs else {
            val sorted = presentedUs.sortedArray()
            var shiftUs = 0L
            for (i in 0 until n) shiftUs = maxOf(shiftUs, sorted[i] - presentedUs[i])
            LongArray(n) { sorted[it] - shiftUs }
        }
        val decodeTicks = LongArray(n)
        val compositionTicks = IntArray(n)
        for (i in 0 until n) {
            val ticks = ticks(decodedUs[i] - decodedUs[0])
            decodeTicks[i] = if (i > 0 && ticks <= decodeTicks[i - 1]) decodeTicks[i - 1] + 1 else ticks
            compositionTicks[i] = ticks(presentedUs[i] - decodedUs[i]).toInt()
        }
        val durations = IntArray(n)
        for (i in 0 until n - 1) durations[i] = (decodeTicks[i + 1] - decodeTicks[i]).toInt()
        if (n > 0) durations[n - 1] = if (n > 1) durations[n - 2] else timescale / LONE_SAMPLE_FRACTION
        val firstShownUs = presentedUs.minOrNull() ?: 0L
        val lastShownUs = presentedUs.maxOrNull() ?: 0L
        return Timing(
            durations, compositionTicks, firstShownUs,
            mediaStartTicks = ticks(firstShownUs - (decodedUs.firstOrNull() ?: 0L)),
            mediaTicks = (decodeTicks.lastOrNull() ?: 0L) + (durations.lastOrNull() ?: 0),
            shownTicks = ticks(lastShownUs - firstShownUs) + (durations.lastOrNull() ?: 0)
        )
    }

    internal fun ticks(us: Long): Long = us * timescale / 1_000_000L

    internal fun Mp4Boxes.sampleTables(timing: Timing) {
        val n = count
        box("stbl") {
            fullBox("stsd", 0, 0) {
                u32(1)
                bytes(sampleEntry)
            }
            runs("stts", n) { timing.durations[it] }
            if (timing.compositionTicks.any { it != 0 }) runs("ctts", n) { timing.compositionTicks[it] }
            if (isVideo && (0 until n).any { !sync[it] }) fullBox("stss", 0, 0) {
                u32((0 until n).count { sync[it] })
                for (i in 0 until n) if (sync[i]) u32(i + 1)
            }
            fullBox("stsz", 0, 0) {
                u32(0)
                u32(n)
                for (i in 0 until n) u32(sizes[i])
            }
            chunks(n)
        }
    }

    private fun Mp4Boxes.chunks(n: Int) {
        val chunkOffsets = ArrayList<Long>()
        val chunkSamples = ArrayList<Int>()
        for (i in 0 until n) {
            if (i > 0 && offsets[i] == offsets[i - 1] + sizes[i - 1]) {
                chunkSamples[chunkSamples.size - 1]++
            } else {
                chunkOffsets.add(offsets[i])
                chunkSamples.add(1)
            }
        }
        fullBox("stsc", 0, 0) {
            val at = size
            u32(0)
            var entries = 0
            for (c in chunkSamples.indices) {
                if (c > 0 && chunkSamples[c] == chunkSamples[c - 1]) continue
                u32(c + 1)
                u32(chunkSamples[c])
                u32(1)
                entries++
            }
            patch32(at, entries.toLong())
        }
        val wide = chunkOffsets.any { it > UINT32_MAX }
        fullBox(if (wide) "co64" else "stco", 0, 0) {
            u32(chunkOffsets.size)
            for (offset in chunkOffsets) if (wide) u64(offset) else u32(offset)
        }
    }

    private inline fun Mp4Boxes.runs(name: String, n: Int, value: (Int) -> Int) = fullBox(name, 0, 0) {
        val at = size
        u32(0)
        var entries = 0
        var i = 0
        while (i < n) {
            val current = value(i)
            var run = 1
            while (i + run < n && value(i + run) == current) run++
            u32(run)
            u32(current)
            entries++
            i += run
        }
        patch32(at, entries.toLong())
    }
}

internal class Timing(
    val durations: IntArray,
    val compositionTicks: IntArray,
    val firstShownUs: Long,
    val mediaStartTicks: Long,
    val mediaTicks: Long,
    val shownTicks: Long
)

internal fun movieIndex(tracks: List<TrackIndex>): ByteArray {
    val present = tracks.filter { it.count > 0 }
    val timings = present.map { it.timing() }
    val originUs = timings.minOf { it.firstShownUs }
    val shownMs = present.indices.map { i ->
        val track = present[i]
        timings[i].shownTicks * MOVIE_TIMESCALE / track.timescale
    }
    val gapsMs = timings.map { (it.firstShownUs - originUs) / 1000L }
    val movieMs = present.indices.maxOf { gapsMs[it] + shownMs[it] }
    return Mp4Boxes(64 * 1024).apply {
        box("moov") {
            fullBox("mvhd", 0, 0) {
                u32(0)
                u32(0)
                u32(MOVIE_TIMESCALE)
                u32(movieMs)
                u32(0x00010000)
                u16(0x0100)
                zeros(10)
                matrix()
                zeros(24)
                u32(present.size + 1)
            }
            for (i in present.indices) {
                trak(present[i], i + 1, timings[i], gapsMs[i], shownMs[i])
            }
        }
    }.toByteArray()
}

private fun Mp4Boxes.trak(track: TrackIndex, id: Int, timing: Timing, gapMs: Long, shownMs: Long) = box("trak") {
    fullBox("tkhd", 0, 3) {
        u32(0)
        u32(0)
        u32(id)
        u32(0)
        u32(gapMs + shownMs)
        zeros(8)
        u16(0)
        u16(0)
        u16(if (track.isVideo) 0 else 0x0100)
        u16(0)
        matrix()
        if (track.isVideo) {
            u32(entryWidth(track.sampleEntry).toLong() shl 16)
            u32(entryHeight(track.sampleEntry).toLong() shl 16)
        } else {
            u32(0)
            u32(0)
        }
    }
    if (gapMs > 0 || timing.mediaStartTicks > 0) box("edts") {
        fullBox("elst", 0, 0) {
            u32(if (gapMs > 0) 2 else 1)
            if (gapMs > 0) {
                u32(gapMs)
                u32(UINT32_MAX)
                u32(0x00010000)
            }
            u32(shownMs)
            u32(timing.mediaStartTicks)
            u32(0x00010000)
        }
    }
    box("mdia") {
        fullBox("mdhd", 0, 0) {
            u32(0)
            u32(0)
            u32(track.timescale)
            u32(timing.mediaTicks)
            u16(0x55C4)
            u16(0)
        }
        fullBox("hdlr", 0, 0) {
            u32(0)
            type(if (track.isVideo) "vide" else "soun")
            zeros(12)
            type(if (track.isVideo) "Strike video" else "Strike audio")
            u8(0)
        }
        box("minf") {
            if (track.isVideo) fullBox("vmhd", 0, 1) { zeros(8) }
            else fullBox("smhd", 0, 0) { zeros(4) }
            box("dinf") {
                fullBox("dref", 0, 0) {
                    u32(1)
                    fullBox("url ", 0, 1) {}
                }
            }
            with(track) { sampleTables(timing) }
        }
    }
}

private fun Mp4Boxes.matrix() {
    u32(0x00010000)
    u32(0)
    u32(0)
    u32(0)
    u32(0x00010000)
    u32(0)
    u32(0)
    u32(0)
    u32(0x40000000)
}

// A visual sample entry keeps its width and height 32 bytes into the box.
private fun entryWidth(entry: ByteArray): Int = (entry[32].toInt() and 0xFF shl 8) or (entry[33].toInt() and 0xFF)

private fun entryHeight(entry: ByteArray): Int = (entry[34].toInt() and 0xFF shl 8) or (entry[35].toInt() and 0xFF)
