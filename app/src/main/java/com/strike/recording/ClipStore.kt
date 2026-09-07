package com.strike.recording

import java.io.File
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale

private val CLIP_NAME = Regex("(drive|parked|manual)_(\\d{8})_(\\d{6})(?:_\\d+)?\\.mp4")
private const val STAMP = "yyyyMMddHHmmss"

/** WATCH and EVENT are surveillance names, and live in the events store. */
enum class RecordingMode { DRIVE, PARKED, MANUAL, WATCH, EVENT }

class ClipStore(private val root: File) : Reapable {

    override fun list(): List<Clip> {
        val files = root.listFiles() ?: return emptyList()
        val clips = ArrayList<Clip>()
        for (file in files) {
            val clip = readClip(file.name, file.length())
            if (clip != null) clips.add(clip)
        }
        clips.sortByDescending { it.startedAt }
        return clips
    }

    fun file(id: String): File? {
        if (readClip(id, 0) == null) return null
        val file = File(root, id)
        return if (file.isFile) file else null
    }

    override fun delete(id: String): Boolean {
        val file = file(id) ?: return false
        return file.delete()
    }
}

fun totalBytes(clips: List<Kept>): Long {
    var bytes = 0L
    for (clip in clips) bytes += clip.bytes
    return bytes
}

class Clip(
    id: String,
    val startedAt: Long,
    bytes: Long,
    val mode: RecordingMode,
    val date: String,
    val time: String
) : Kept(id, bytes)

/**
 * The name carries the kind and the wall clock the car recorded at, so date and
 * time are read straight off it rather than reformatted in another zone.
 */
internal fun readClip(name: String, bytes: Long): Clip? {
    val match = CLIP_NAME.matchEntire(name) ?: return null
    val stamp = readStamp(match.groupValues[2], match.groupValues[3]) ?: return null
    return Clip(
        id = name,
        startedAt = stamp.startedAt,
        bytes = bytes,
        mode = RecordingMode.valueOf(match.groupValues[1].uppercase(Locale.US)),
        date = stamp.date,
        time = stamp.time
    )
}

internal class Stamp(val startedAt: Long, val date: String, val time: String)

internal fun readStamp(day: String, second: String): Stamp? {
    val startedAt = clipStamp(day + second) ?: return null
    return Stamp(
        startedAt = startedAt,
        date = "${day.substring(0, 4)}-${day.substring(4, 6)}-${day.substring(6, 8)}",
        time = "${second.substring(0, 2)}:${second.substring(2, 4)}"
    )
}

private fun clipStamp(stamp: String): Long? {
    val format = SimpleDateFormat(STAMP, Locale.US)
    format.isLenient = false
    return try {
        format.parse(stamp)?.time
    } catch (e: ParseException) {
        null
    }
}
