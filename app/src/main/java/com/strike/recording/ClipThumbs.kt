package com.strike.recording

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

private const val WIDE = 320
private const val HIGH = 240
private const val QUALITY = 75

// Skip the first second while camera exposure settles.
private const val AT_US = 1_000_000L

class Thumb(val jpeg: ByteArray, val durationMs: Long, val codec: String?)

// Cache the decoded frame and what the clip says about itself. Opening the clip costs a
// container parse, so a cached thumbnail must answer without touching the file again.
class ClipThumbs(private val dir: File) {

    fun of(clip: File, id: String): Thumb? = cached(id) ?: decode(clip, id)

    fun forget(id: String) {
        file(id).delete()
        facts(id).delete()
    }

    private fun decode(clip: File, id: String): Thumb? {
        val reader = MediaMetadataRetriever()
        return try {
            reader.setDataSource(clip.absolutePath)
            val durationMs = reader
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val jpeg = frame(reader) ?: return null
            val codec = codecOf(clip)
            keep(id, jpeg, durationMs, codec)
            Thumb(jpeg, durationMs, codec)
        } catch (e: RuntimeException) {
            // A clip cut off by the car losing power has no readable header.
            null
        } finally {
            reader.release()
        }
    }

    private fun frame(reader: MediaMetadataRetriever): ByteArray? {
        val frame = reader.getScaledFrameAtTime(
            AT_US, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, WIDE, HIGH
        ) ?: reader.getFrameAtTime(AT_US, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ?: return null
        val jpeg = ByteArrayOutputStream()
        frame.compress(Bitmap.CompressFormat.JPEG, QUALITY, jpeg)
        frame.recycle()
        return jpeg.toByteArray()
    }

    private fun cached(id: String): Thumb? {
        val jpeg = file(id)
        val facts = facts(id)
        if (!jpeg.isFile || !facts.isFile) return null
        return try {
            val lines = facts.readLines()
            val durationMs = lines.getOrNull(0)?.toLongOrNull() ?: return null
            val codec = lines.getOrNull(1)?.ifEmpty { null }
            Thumb(jpeg.readBytes(), durationMs, codec)
        } catch (e: IOException) {
            null
        }
    }

    private fun keep(id: String, jpeg: ByteArray, durationMs: Long, codec: String?) {
        try {
            dir.mkdirs()
            file(id).writeBytes(jpeg)
            facts(id).writeText(durationMs.toString() + "\n" + (codec ?: ""))
        } catch (e: IOException) {
            // Losing the cache only costs the next request a decode.
        }
    }

    private fun file(id: String): File = File(dir, "$id.jpg")

    private fun facts(id: String): File = File(dir, "$id.facts")
}
