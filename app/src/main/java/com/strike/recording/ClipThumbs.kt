package com.strike.recording

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import com.strike.core.Logs
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

private const val WIDE = 320
private const val HIGH = 240
private const val QUALITY = 75
private const val MEMORY_THUMBS = 64
private const val QUEUED_THUMBS = 8
private const val FAILURE_MS = 30_000L

// Skip the first second while camera exposure settles.
private const val AT_US = 1_000_000L

private val thumbnailWorker = ThreadPoolExecutor(
    1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(QUEUED_THUMBS),
    { task -> Thread(task, "thumbnails").apply { isDaemon = true } }
)

class Thumb(val jpeg: ByteArray, val durationMs: Long, val codec: String?)

sealed class ThumbResult {
    class Ready(val thumb: Thumb) : ThumbResult()
    object Pending : ThumbResult()
    object Missing : ThumbResult()
    object Busy : ThumbResult()
}

class ClipThumbs internal constructor(
    private val dir: File,
    private val executor: Executor,
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
    private val decode: (File, File?) -> Thumb? = ::decodeThumb
) {
    constructor(dir: File) : this(dir, thumbnailWorker)

    private class Entry(val fingerprint: String) {
        var result: ThumbResult = ThumbResult.Pending
        var completedAtMs = 0L
    }

    private val lock = Any()
    private val held = LinkedHashMap<String, Entry>()

    fun of(clip: File, id: String, hero: File? = null): ThumbResult {
        val fingerprint = fingerprint(clip, hero)
        val entry = synchronized(lock) {
            val last = held.remove(id)
            if (last != null && last.fingerprint == fingerprint &&
                (last.result != ThumbResult.Missing || nowMs() - last.completedAtMs < FAILURE_MS)) {
                held[id] = last
                return last.result
            }
            if (held.size >= MEMORY_THUMBS) {
                val idle = held.entries.firstOrNull { it.value.result != ThumbResult.Pending }
                    ?: return ThumbResult.Busy
                held.remove(idle.key)
            }
            Entry(fingerprint).also { held[id] = it }
        }
        try {
            executor.execute { refresh(clip, hero, id, entry) }
        } catch (e: RejectedExecutionException) {
            synchronized(lock) { if (held[id] === entry) held.remove(id) }
            return ThumbResult.Busy
        }
        return synchronized(lock) {
            if (held[id] === entry) entry.result else ThumbResult.Busy
        }
    }

    fun forget(id: String) {
        synchronized(lock) { held.remove(id) }
        file(id).delete()
        facts(id).delete()
    }

    private fun refresh(clip: File, hero: File?, id: String, entry: Entry) {
        if (!current(id, entry)) return
        var thumb: Thumb? = null
        var obsolete = false
        try {
            obsolete = fingerprint(clip, hero) != entry.fingerprint
            if (obsolete) return
            val cached = cached(id, entry.fingerprint)
            thumb = cached ?: decode(clip, hero)
            obsolete = fingerprint(clip, hero) != entry.fingerprint || !current(id, entry)
            if (obsolete) return
            if (cached == null && thumb != null) keep(id, entry.fingerprint, thumb)
        } catch (e: RuntimeException) {
            thumb = null
            Logs.w("Thumbnails", "Could not read clip thumbnail", e)
        } catch (e: IOException) {
            thumb = null
            Logs.w("Thumbnails", "Could not read clip thumbnail", e)
        } finally {
            val retained = synchronized(lock) {
                if (held[id] !== entry) false else {
                    if (obsolete) {
                        held.remove(id)
                        false
                    } else {
                        entry.result = thumb?.let { ThumbResult.Ready(it) } ?: ThumbResult.Missing
                        entry.completedAtMs = nowMs()
                        true
                    }
                }
            }
            if (!retained) {
                file(id).delete()
                facts(id).delete()
            }
        }
    }

    private fun current(id: String, entry: Entry): Boolean = synchronized(lock) { held[id] === entry }

    private fun cached(id: String, fingerprint: String): Thumb? {
        return try {
            val lines = facts(id).readLines()
            if (lines.getOrNull(2) != fingerprint) return null
            val durationMs = lines.getOrNull(0)?.toLongOrNull() ?: return null
            val codec = lines.getOrNull(1)?.ifEmpty { null }
            val jpeg = file(id).readBytes()
            if (jpeg.isEmpty()) null else Thumb(jpeg, durationMs, codec)
        } catch (e: IOException) {
            null
        }
    }

    private fun keep(id: String, fingerprint: String, thumb: Thumb) {
        try {
            dir.mkdirs()
            val facts = facts(id)
            if (facts.exists() && !facts.delete()) return
            file(id).writeBytes(thumb.jpeg)
            facts.writeText("${thumb.durationMs}\n${thumb.codec ?: ""}\n$fingerprint")
        } catch (e: IOException) {
            // The memory snapshot remains usable when the disk cache is unavailable.
        }
    }

    private fun file(id: String): File = File(dir, "$id.jpg")

    private fun facts(id: String): File = File(dir, "$id.facts")
}

private fun fingerprint(clip: File, hero: File?): String {
    val stamp = "${clip.absolutePath}\n${clip.length()}\n${clip.lastModified()}\n" +
        "${hero?.absolutePath}\n${hero?.length()}\n${hero?.lastModified()}"
    return MessageDigest.getInstance("SHA-256").digest(stamp.toByteArray())
        .joinToString("") { "%02x".format(it) }
}

private fun decodeThumb(clip: File, hero: File?): Thumb? {
    val boxed = try {
        hero?.readBytes()?.takeIf { it.isNotEmpty() }
    } catch (e: IOException) {
        null
    }
    val reader = MediaMetadataRetriever()
    return try {
        reader.setDataSource(clip.absolutePath)
        val durationMs = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull() ?: 0L
        val jpeg = boxed ?: frame(reader) ?: return null
        Thumb(jpeg, durationMs, codecOf(clip))
    } catch (e: IOException) {
        boxed?.let { Thumb(it, 0L, null) }
    } catch (e: RuntimeException) {
        // A clip cut off by the car losing power has no readable header.
        boxed?.let { Thumb(it, 0L, null) }
    } finally {
        try {
            reader.release()
        } catch (e: IOException) {
            Logs.w("Thumbnails", "Could not close thumbnail reader", e)
        } catch (e: RuntimeException) {
            Logs.w("Thumbnails", "Could not close thumbnail reader", e)
        }
    }
}

private fun frame(reader: MediaMetadataRetriever): ByteArray? {
    val frame = reader.getScaledFrameAtTime(
        AT_US, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, WIDE, HIGH
    ) ?: reader.getFrameAtTime(AT_US, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        ?: return null
    return try {
        val jpeg = ByteArrayOutputStream()
        if (!frame.compress(Bitmap.CompressFormat.JPEG, QUALITY, jpeg)) return null
        jpeg.toByteArray()
    } finally {
        frame.recycle()
    }
}
