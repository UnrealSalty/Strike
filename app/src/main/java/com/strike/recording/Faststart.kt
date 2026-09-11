package com.strike.recording

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile

private const val HEADER_BYTES = 8
private const val LARGE_HEADER_BYTES = 16
private const val COPY_BUFFER = 64 * 1024
private const val UINT32_MAX = 0xFFFF_FFFFL

// One chunk offset per chunk of media; the longest clip stays far inside this.
private const val INDEX_MAX_BYTES = 64L * 1024 * 1024

// The boxes between moov and the chunk offset tables it holds.
private val CONTAINERS = setOf("trak", "mdia", "minf", "stbl")

private class Box(val type: String, val at: Long, val bytes: Long, val headerBytes: Long)

/**
 * Copies [source] to [target] with the moov index ahead of the media data. MediaMuxer leaves the
 * index at the end, which a forward-reading player never reaches. False leaves [target] unusable.
 */
internal fun moovToFront(source: File, target: File): Boolean = try {
    RandomAccessFile(source, "r").use { rewrite(it, source.length(), target) }
} catch (e: IOException) {
    false
}

private fun rewrite(source: RandomAccessFile, totalBytes: Long, target: File): Boolean {
    val boxes = topLevel(source, totalBytes) ?: return false
    val index = boxes.firstOrNull { it.type == "moov" } ?: return false
    val media = boxes.firstOrNull { it.type == "mdat" } ?: return false
    if (index.at < media.at) return false
    if (index.bytes > INDEX_MAX_BYTES) return false

    val moved = ByteArray(index.bytes.toInt())
    source.seek(index.at)
    source.readFully(moved)
    val children = if (readUInt32(moved, 0) == 1L) LARGE_HEADER_BYTES else HEADER_BYTES
    if (!shiftChunks(moved, children, moved.size, index.bytes, chunksAfter(media, index.bytes))) return false

    FileOutputStream(target).use { out ->
        for (box in boxes) {
            if (box === index) continue
            if (box === media) out.write(moved)
            copy(source, box.at, box.bytes, out)
        }
        out.fd.sync()
    }
    return target.length() == totalBytes
}

private fun topLevel(source: RandomAccessFile, totalBytes: Long): List<Box>? {
    val boxes = ArrayList<Box>()
    val header = ByteArray(LARGE_HEADER_BYTES)
    var at = 0L
    while (at < totalBytes) {
        if (totalBytes - at < HEADER_BYTES) return null
        source.seek(at)
        source.readFully(header, 0, HEADER_BYTES)
        var bytes = readUInt32(header, 0)
        var headerBytes = HEADER_BYTES.toLong()
        if (bytes == 1L) {
            if (totalBytes - at < LARGE_HEADER_BYTES) return null
            source.readFully(header, HEADER_BYTES, HEADER_BYTES)
            bytes = readUInt64(header, HEADER_BYTES)
            headerBytes = LARGE_HEADER_BYTES.toLong()
        }
        if (bytes < headerBytes || bytes > totalBytes - at) return null
        boxes.add(Box(typeOf(header, 4), at, bytes, headerBytes))
        at += bytes
    }
    return boxes
}

// Where the media payload lands once the index is moved ahead of it.
private fun chunksAfter(media: Box, delta: Long): LongRange =
    media.at + delta + media.headerBytes until media.at + delta + media.bytes

private fun shiftChunks(
    index: ByteArray, from: Int, until: Int, delta: Long, chunks: LongRange
): Boolean {
    var at = from
    while (at < until) {
        if (until - at < HEADER_BYTES) return false
        val bytes = readUInt32(index, at)
        if (bytes < HEADER_BYTES || bytes > until - at) return false
        val type = typeOf(index, at + 4)
        val ends = at + bytes.toInt()
        val payload = at + HEADER_BYTES
        val shifted = when {
            type == "stco" -> shiftOffsets(index, payload, ends, delta, chunks, wide = false)
            type == "co64" -> shiftOffsets(index, payload, ends, delta, chunks, wide = true)
            CONTAINERS.contains(type) -> shiftChunks(index, payload, ends, delta, chunks)
            else -> true
        }
        if (!shifted) return false
        at = ends
    }
    return true
}

// Chunk offsets are absolute positions in the file, so each one moves with the media data. An
// offset that misses the moved media means this is not a layout we understand; keep the original.
private fun shiftOffsets(
    index: ByteArray, from: Int, until: Int, delta: Long, chunks: LongRange, wide: Boolean
): Boolean {
    val width = if (wide) 8 else 4
    var at = from + 4
    if (until - at < 4) return false
    val entries = readUInt32(index, at)
    at += 4
    if (entries > ((until - at) / width).toLong()) return false
    repeat(entries.toInt()) {
        val moved = (if (wide) readUInt64(index, at) else readUInt32(index, at)) + delta
        if (moved !in chunks) return false
        if (!wide && moved > UINT32_MAX) return false
        if (wide) writeUInt64(index, at, moved) else writeUInt32(index, at, moved)
        at += width
    }
    return true
}

private fun copy(source: RandomAccessFile, at: Long, bytes: Long, out: OutputStream) {
    source.seek(at)
    val buffer = ByteArray(COPY_BUFFER)
    var left = bytes
    while (left > 0) {
        val count = source.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
        if (count < 0) throw IOException("the clip ended before its boxes did")
        out.write(buffer, 0, count)
        left -= count
    }
}

private fun typeOf(bytes: ByteArray, at: Int): String = String(bytes, at, 4, Charsets.US_ASCII)

private fun readUInt32(bytes: ByteArray, at: Int): Long =
    ((bytes[at].toLong() and 0xFF) shl 24) or
        ((bytes[at + 1].toLong() and 0xFF) shl 16) or
        ((bytes[at + 2].toLong() and 0xFF) shl 8) or
        (bytes[at + 3].toLong() and 0xFF)

private fun readUInt64(bytes: ByteArray, at: Int): Long {
    var value = 0L
    for (i in 0 until 8) value = (value shl 8) or (bytes[at + i].toLong() and 0xFF)
    return value
}

private fun writeUInt32(bytes: ByteArray, at: Int, value: Long) {
    bytes[at] = (value ushr 24).toByte()
    bytes[at + 1] = (value ushr 16).toByte()
    bytes[at + 2] = (value ushr 8).toByte()
    bytes[at + 3] = value.toByte()
}

private fun writeUInt64(bytes: ByteArray, at: Int, value: Long) {
    for (i in 0 until 8) bytes[at + i] = (value ushr (56 - 8 * i)).toByte()
}
