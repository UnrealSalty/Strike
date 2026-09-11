package com.strike.server.api

import com.strike.recording.MB
import com.strike.recording.Storage
import com.strike.recording.Volume
import com.strike.recording.totalBytes
import com.strike.surveillance.EventStorage
import com.strike.surveillance.EventSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Every screen polls the dashboard every two seconds. Walking the clip and event
// directories at that rate stalls the head unit once a card holds hundreds of clips,
// so the counts come from a snapshot and the recorder's own writes land within a cycle.
private const val REVALIDATE_MS = 30_000L

class LibraryStats(
    val location: String,
    val volume: Volume?,
    val usedMb: Int,
    val clips: Int,
    val clipsToday: Int,
    val eventsLocation: String,
    val eventsMounted: Boolean,
    val events: EventSummary
)

object LibraryScan {

    private val lock = Any()
    private var readAtMs = 0L
    private var held: LibraryStats? = null

    fun stats(storage: Storage, events: EventStorage): LibraryStats = synchronized(lock) {
        val location = storage.location()
        val eventsLocation = events.location()
        val last = held
        val now = System.currentTimeMillis()
        val fresh = now - readAtMs < REVALIDATE_MS
        if (last != null && fresh && last.location == location && last.eventsLocation == eventsLocation) {
            return last
        }
        val scanned = scan(storage, events, location, eventsLocation)
        held = scanned
        readAtMs = now
        scanned
    }

    fun forget() = synchronized(lock) {
        readAtMs = 0L
    }

    private fun scan(
        storage: Storage,
        events: EventStorage,
        location: String,
        eventsLocation: String
    ): LibraryStats {
        val volume = storage.selected()
        val clips = if (volume == null) emptyList() else storage.clipsOn(volume).list()
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        return LibraryStats(
            location = location,
            volume = volume,
            usedMb = (totalBytes(clips) / MB).toInt(),
            clips = clips.size,
            clipsToday = clips.count { it.date == today },
            eventsLocation = eventsLocation,
            eventsMounted = events.selected() != null,
            events = events.store().summary()
        )
    }
}
