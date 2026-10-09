package com.strike.recording

import com.strike.daemon.AudioConfig
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer

internal const val CHECKPOINT_US = 15_000_000L
private const val VIDEO_TIMESCALE = 90_000
private const val BUFFER_BYTES = 256 * 1024
private const val SLOT_ALIGN = 4096
private const val HEADER_BYTES = 8
private const val UINT32_MAX = 0xFFFF_FFFFL

private const val AAC_FRAME_SAMPLES = 1024

// Room kept for the samples that arrive before the keyframe that ends the clip.
private const val WARNING_SECONDS = 10L

// Plans for the fastest rate on offer whatever the encoder was asked for; a faster one rotates early.
private val BUDGET_FPS = RecordingSettings.choices.getValue(RecordingSettings.FRAME_RATE_FPS).options.maxOf { it.toInt() }

private val FTYP = Mp4Boxes(32).apply {
    box("ftyp") {
        type("isom")
        u32(0x200)
        type("isom")
        type("iso2")
        type("mp41")
        type("mp42")
    }
}.toByteArray()

/**
 * A clip written once, front to back. Two slots ahead of the media take turns holding the index,
 * so a cut at any byte leaves one complete index that only names media already on disk.
 */
internal class Mp4File(
    private val file: RandomAccessFile,
    video: VideoTrack,
    audio: AudioConfig?,
    budgetMs: Long
) {
    private val channel = file.channel
    private val video = TrackIndex(VIDEO_TIMESCALE, video.sampleEntry, isVideo = true)
    private val sound = audio?.let { TrackIndex(it.sampleRate, aacEntry(it), isVideo = false) }
    private val tracks = listOfNotNull(this.video, sound)

    val slotBytes: Int
    private val slotA = FTYP.size.toLong()
    private val slotB: Long
    private val wideAt: Long
    private val mediaAt: Long

    private val buffer = ByteBuffer.allocate(BUFFER_BYTES)
    private var bufferedAt: Long
    private var endBytes: Long
    private var activeSlot = -1L
    private var checkpointAtUs = Long.MIN_VALUE
    private var indexBytes: Long

    private val warnAtBytes: Long

    init {
        val seconds = budgetMs / 1000L + 1
        val audioFrames = if (audio == null) 0L else audio.sampleRate.toLong() / AAC_FRAME_SAMPLES + 1
        val perSecond = BUDGET_FPS * VIDEO_INDEX_BYTES_PER_SAMPLE + audioFrames * AUDIO_INDEX_BYTES_PER_SAMPLE
        indexBytes = INDEX_FIXED_BYTES.toLong() + tracks.sumOf { it.sampleEntry.size }
        slotBytes = ((indexBytes + seconds * perSecond + SLOT_ALIGN - 1) / SLOT_ALIGN * SLOT_ALIGN).toInt()
        warnAtBytes = slotBytes - HEADER_BYTES - WARNING_SECONDS * perSecond
        slotB = slotA + slotBytes
        wideAt = slotB + slotBytes
        mediaAt = wideAt + 2 * HEADER_BYTES
        bufferedAt = mediaAt
        endBytes = mediaAt
        file.setLength(0)
        put(0, FTYP)
        put(slotA, header(slotBytes.toLong(), "free"))
        put(slotB, header(slotBytes.toLong(), "free"))
        put(wideAt, header(HEADER_BYTES.toLong(), "wide"))
        put(wideAt + HEADER_BYTES, header(0, "mdat"))
    }

    /** The index is close to its slot; the clip should end at the next keyframe. */
    val isNearlyFull: Boolean get() = indexBytes > warnAtBytes

    val videoSamples: Int get() = video.count

    val audioSamples: Int get() = sound?.count ?: 0

    /** False when the slot has no room left for this sample's index entry; the sample is dropped. */
    fun writeVideo(bytes: ByteArray, timeUs: Long, keyFrame: Boolean): Boolean {
        if (!room(VIDEO_INDEX_BYTES_PER_SAMPLE)) return false
        if (checkpointAtUs == Long.MIN_VALUE) checkpointAtUs = timeUs
        append(video, lengthPrefixed(bytes), timeUs, keyFrame)
        indexBytes += VIDEO_INDEX_BYTES_PER_SAMPLE
        if (timeUs - checkpointAtUs >= CHECKPOINT_US) {
            checkpointAtUs = timeUs
            index()
        }
        return true
    }

    fun writeAudio(bytes: ByteArray, timeUs: Long): Boolean {
        val track = sound ?: return false
        if (!room(AUDIO_INDEX_BYTES_PER_SAMPLE)) return false
        append(track, bytes, timeUs, true)
        indexBytes += AUDIO_INDEX_BYTES_PER_SAMPLE
        return true
    }

    /** False when no video reached the clip, which leaves nothing to play. */
    fun finish(): Boolean {
        try {
            if (video.count == 0) return false
            flush()
            val mediaBytes = endBytes - (wideAt + HEADER_BYTES)
            if (mediaBytes <= UINT32_MAX) {
                put(wideAt + HEADER_BYTES, header(mediaBytes, "mdat"))
            } else {
                val large = ByteBuffer.allocate(2 * HEADER_BYTES)
                large.putInt(1).put("mdat".toByteArray(Charsets.US_ASCII)).putLong(endBytes - wideAt)
                put(wideAt, large.array())
            }
            index()
            return true
        } finally {
            file.close()
        }
    }

    fun abandon() = file.close()

    private fun room(entryBytes: Int): Boolean = indexBytes + entryBytes <= slotBytes - HEADER_BYTES

    private fun append(track: TrackIndex, bytes: ByteArray, timeUs: Long, sync: Boolean) {
        val at = endBytes
        if (bytes.size > buffer.remaining()) flush()
        if (bytes.size > buffer.capacity()) {
            put(at, bytes)
            bufferedAt = at + bytes.size
        } else {
            buffer.put(bytes)
        }
        endBytes += bytes.size
        track.add(at, bytes.size, timeUs, sync)
    }

    private fun flush() {
        buffer.flip()
        while (buffer.hasRemaining()) bufferedAt += channel.write(buffer, bufferedAt)
        buffer.clear()
    }

    // Each 8-byte header switch is forced before the next, so the disk never holds zero indexes.
    private fun index() {
        flush()
        val moov = movieIndex(tracks)
        if (moov.size > slotBytes - HEADER_BYTES) throw IOException("the clip index outgrew its ${slotBytes / 1024} KB slot")
        val target = if (activeSlot == slotA) slotB else slotA
        put(target + HEADER_BYTES, moov, from = HEADER_BYTES)
        put(target + moov.size, header((slotBytes - moov.size).toLong(), "free"))
        channel.force(false)
        put(target, moov, from = 0, count = HEADER_BYTES)
        channel.force(false)
        if (activeSlot >= 0) {
            put(activeSlot, header(slotBytes.toLong(), "free"))
            channel.force(false)
        }
        activeSlot = target
    }

    private fun put(at: Long, bytes: ByteArray, from: Int = 0, count: Int = bytes.size - from) {
        val out = ByteBuffer.wrap(bytes, from, count)
        var position = at
        while (out.hasRemaining()) position += channel.write(out, position)
    }
}

/** Finds the index of a clip cut off mid-write and gives its media box the length it reached. */
internal fun sealUnfinished(file: File): Boolean = try {
    RandomAccessFile(file, "rw").use { clip -> seal(clip) }
} catch (e: IOException) {
    false
}

private fun seal(clip: RandomAccessFile): Boolean {
    val length = clip.length()
    val head = ByteArray(HEADER_BYTES)
    var at = 0L
    var indexed = false
    var previous = ""
    var previousAt = 0L
    while (at + HEADER_BYTES <= length) {
        clip.seek(at)
        clip.readFully(head)
        val size = ByteBuffer.wrap(head).int.toLong() and UINT32_MAX
        val type = String(head, 4, 4, Charsets.US_ASCII)
        if (type == "mdat") {
            if (!indexed) return false
            if (size != 0L) return size == 1L || at + size <= length
            val mediaBytes = length - at
            if (mediaBytes <= UINT32_MAX) {
                clip.seek(at)
                clip.write(header(mediaBytes, "mdat"))
            } else {
                if (previous != "wide" || previousAt + HEADER_BYTES != at) return false
                clip.seek(previousAt)
                clip.writeInt(1)
                clip.write("mdat".toByteArray(Charsets.US_ASCII))
                clip.writeLong(length - previousAt)
            }
            clip.fd.sync()
            return true
        }
        if (type == "moov") indexed = true
        if (size < HEADER_BYTES) return false
        previous = type
        previousAt = at
        at += size
    }
    return false
}

private fun header(size: Long, type: String): ByteArray =
    ByteBuffer.allocate(HEADER_BYTES).putInt(size.toInt()).put(type.toByteArray(Charsets.US_ASCII)).array()
