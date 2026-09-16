package com.strike.server.api

import com.strike.core.Logs
import com.strike.recording.Clip
import com.strike.recording.ClipStore
import com.strike.recording.MB
import com.strike.recording.Storage
import com.strike.recording.Volume
import com.strike.recording.clipsDir
import com.strike.recording.totalBytes
import com.strike.surveillance.EventStorage
import com.strike.surveillance.EventStore
import com.strike.surveillance.EventSummary
import com.strike.server.JSON
import com.strike.server.Response
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

internal fun libraryPending(status: Int = 200): Response = Response(
    status, JSON, "{\"pending\":true}".toByteArray(),
    headers = if (status == 503) mapOf("Retry-After" to "1") else emptyMap()
)

class LibraryStats(
    val location: String,
    val volume: Volume?,
    val usedMb: Int,
    val clips: Int,
    val clipsToday: Int,
    val events: EventSummary?
)

object LibraryScan {

    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "library-scan").apply { isDaemon = true }
    }
    private val recordings = DirectoryInventory({ root ->
        val startedAt = System.nanoTime()
        val clips = ClipStore(root).list()
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        Logs.d("Library", "PROBE clips ${clips.size} ${totalBytes(clips) / MB}MB in ${elapsedMs}ms")
        clips
    }, worker)
    private val summaries = DirectoryInventory({ root -> EventStore(root).summary() }, worker)

    internal fun clips(volume: Volume, revision: Long): DirectorySnapshot<List<Clip>> =
        recordings.snapshot(clipsDir(volume), revision)

    fun stats(storage: Storage, events: EventStorage): LibraryStats {
        val mounts = storage.volumeSnapshot()
        val location = storage.location()
        val volume = mounts.volumes[location]
        val clips = volume?.let { clips(it, mounts.revision).value }
        val summary = if (mounts.volumes[events.location()] == null) null else {
            summaries.snapshot(events.store().root, mounts.revision).value
        }
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        return LibraryStats(
            location = location,
            volume = if (clips == null) null else volume,
            usedMb = (totalBytes(clips.orEmpty()) / MB).toInt(),
            clips = clips?.size ?: 0,
            clipsToday = clips?.count { it.date == today } ?: 0,
            events = summary
        )
    }

    fun forget() {
        recordings.forget()
        summaries.forget()
    }
}
