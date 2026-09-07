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

class Thumb(val jpeg: ByteArray, val durationMs: Long)

// Cache decoded thumbnails on disk; read duration from the clip.
class ClipThumbs(private val dir: File) {

    fun of(clip: File, id: String): Thumb? {
        val reader = MediaMetadataRetriever()
        return try {
            reader.setDataSource(clip.absolutePath)
            val durationMs = reader
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val jpeg = cached(id) ?: decode(reader, id) ?: return null
            Thumb(jpeg, durationMs)
        } catch (e: RuntimeException) {
            // A clip cut off by the car losing power has no readable header.
            null
        } finally {
            reader.release()
        }
    }

    fun forget(id: String) {
        file(id).delete()
    }

    private fun decode(reader: MediaMetadataRetriever, id: String): ByteArray? {
        val frame = reader.getScaledFrameAtTime(
            AT_US, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, WIDE, HIGH
        ) ?: reader.getFrameAtTime(AT_US, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ?: return null
        val jpeg = ByteArrayOutputStream()
        frame.compress(Bitmap.CompressFormat.JPEG, QUALITY, jpeg)
        frame.recycle()
        val bytes = jpeg.toByteArray()
        keep(id, bytes)
        return bytes
    }

    private fun cached(id: String): ByteArray? {
        val file = file(id)
        if (!file.isFile) return null
        return try {
            file.readBytes()
        } catch (e: IOException) {
            null
        }
    }

    private fun keep(id: String, jpeg: ByteArray) {
        try {
            dir.mkdirs()
            file(id).writeBytes(jpeg)
        } catch (e: IOException) {
            // Losing the cache only costs the next request a decode.
        }
    }

    private fun file(id: String): File = File(dir, "$id.jpg")
}
