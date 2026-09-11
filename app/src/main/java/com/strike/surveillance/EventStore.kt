package com.strike.surveillance

import com.strike.recording.Kept
import com.strike.recording.Reapable
import com.strike.recording.keptIn
import com.strike.recording.readStamp
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale

private val EVENT_NAME = Regex("(event|watch)_(\\d{8})_(\\d{6})\\.mp4")

const val PERSON = "person"
const val VEHICLE = "vehicle"

enum class EventKind { EVENT, WATCH }

class Event(
    id: String,
    val startedAt: Long,
    bytes: Long,
    val kind: EventKind,
    val seen: String?,
    val score: Float,
    val date: String,
    val time: String,
    val bands: List<Band>
) : Kept(id, bytes)

class Band(val startMs: Long, val endMs: Long, val seen: String)

class EventSummary(val clips: Int, val latest: Event?)

// Merge adjacent sightings into one timeline band.
internal fun bandsOf(marks: List<Mark>): List<Band> {
    val bands = ArrayList<Band>()
    for (mark in marks.sortedBy { it.atMs }) {
        val last = bands.lastOrNull()
        if (last != null && last.seen == mark.seen && mark.atMs <= last.endMs) {
            bands[bands.size - 1] = Band(last.startMs, mark.atMs + SAMPLE_MS, mark.seen)
        } else {
            bands.add(Band(mark.atMs, mark.atMs + SAMPLE_MS, mark.seen))
        }
    }
    return bands
}

// Event metadata and detection stills share the clip's filename stem.
class EventStore(private val root: File) : Reapable {

    override fun list(): List<Event> {
        val files = root.listFiles() ?: return emptyList()
        val events = ArrayList<Event>()
        for (file in files) {
            val event = read(file.name, file.length())
            if (event != null) events.add(event)
        }
        events.sortByDescending { it.startedAt }
        return events
    }

    override fun kept(): List<Kept> = keptIn(root, EVENT_NAME)

    // The dashboard needs one sidecar, not the whole event history every poll.
    fun summary(): EventSummary {
        val files = root.listFiles()?.filter { it.isFile && EVENT_NAME.matches(it.name) } ?: emptyList()
        for (file in files.sortedByDescending { it.name.substringAfter('_') }) {
            val event = read(file.name, file.length())
            if (event != null) return EventSummary(files.size, event)
        }
        return EventSummary(files.size, null)
    }

    fun file(id: String): File? {
        if (EVENT_NAME.matchEntire(id) == null) return null
        val file = File(root, id)
        return if (file.isFile) file else null
    }

    fun hero(id: String): File? {
        if (EVENT_NAME.matchEntire(id) == null) return null
        val file = File(root, stem(id) + ".jpg")
        return if (file.isFile) file else null
    }

    override fun delete(id: String): Boolean {
        val file = file(id) ?: return false
        if (!file.delete()) return false
        File(root, stem(id) + ".json").delete()
        File(root, stem(id) + ".jpg").delete()
        return true
    }

    // Sidecars written by shell UID 2000 must remain readable by the app.
    fun flag(clip: String, seen: String, score: Float, hero: ByteArray?) {
        if (EVENT_NAME.matchEntire(clip) == null) return
        val facts = sidecar(clip) ?: JSONObject()
        facts.put("seen", seen)
        facts.put("score", score.toDouble())
        write(File(root, stem(clip) + ".json"), facts.toString().toByteArray())
        if (hero != null) write(File(root, stem(clip) + ".jpg"), hero)
    }

    // Persist sightings during recording so the timeline survives a process exit.
    fun mark(clip: String, clipStartedAtMs: Long, marks: List<Mark>) {
        if (EVENT_NAME.matchEntire(clip) == null || marks.isEmpty()) return
        val facts = sidecar(clip) ?: JSONObject()
        val spans = facts.optJSONArray("marks") ?: JSONArray()
        for (mark in marks) {
            val span = JSONObject()
            span.put("atMs", (mark.atMs - clipStartedAtMs).coerceAtLeast(0L))
            span.put("seen", mark.seen)
            spans.put(span)
        }
        facts.put("marks", spans)
        write(File(root, stem(clip) + ".json"), facts.toString().toByteArray())
    }

    private fun write(file: File, bytes: ByteArray) {
        try {
            file.writeBytes(bytes)
            file.setReadable(true, false)
        } catch (e: IOException) {
            // The clip is the evidence; a missing sidecar only costs the label.
        }
    }

    private fun read(name: String, bytes: Long): Event? {
        val match = EVENT_NAME.matchEntire(name) ?: return null
        val stamp = readStamp(match.groupValues[2], match.groupValues[3]) ?: return null
        val facts = sidecar(name)
        return Event(
            id = name,
            startedAt = stamp.startedAt,
            bytes = bytes,
            kind = EventKind.valueOf(match.groupValues[1].uppercase(Locale.US)),
            seen = facts?.optString("seen")?.ifEmpty { null },
            score = facts?.optDouble("score", 0.0)?.toFloat() ?: 0f,
            date = stamp.date,
            time = stamp.time,
            bands = bandsOf(marksIn(facts))
        )
    }

    private fun marksIn(facts: JSONObject?): List<Mark> {
        val spans = facts?.optJSONArray("marks") ?: return emptyList()
        val marks = ArrayList<Mark>(spans.length())
        for (at in 0 until spans.length()) {
            val span = spans.optJSONObject(at) ?: continue
            val seen = span.optString("seen")
            if (seen.isEmpty()) continue
            marks.add(Mark(span.optLong("atMs"), seen))
        }
        return marks
    }

    private fun sidecar(name: String): JSONObject? {
        val file = File(root, stem(name) + ".json")
        if (!file.isFile) return null
        return try {
            JSONObject(file.readText())
        } catch (e: JSONException) {
            null
        } catch (e: IOException) {
            null
        }
    }
}

private fun stem(name: String): String = name.removeSuffix(".mp4")
