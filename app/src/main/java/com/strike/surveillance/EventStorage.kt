package com.strike.surveillance

import android.content.Context
import com.strike.core.Config
import com.strike.core.Logs
import com.strike.daemon.Shell
import com.strike.recording.MB
import com.strike.recording.RecordingSettings
import com.strike.recording.Retention
import com.strike.recording.Volume
import com.strike.recording.Volumes
import com.strike.recording.forgetMounted
import com.strike.recording.publicRoot
import com.strike.recording.publishWriteDir
import com.strike.recording.storageUuid
import com.strike.recording.totalBytes
import java.io.File

private const val TAG = "Events"
private const val CLIPS_DIR = "Strike/clips"

/** Decides which volume events live on and how much room they may take. */
class EventStorage(context: Context, shell: Shell) {

    private val volumes = Volumes(context, shell)

    fun mounted(): Map<String, Volume> = volumes.mounted()

    fun location(): String =
        Config.getString(SurveillanceSettings.LOCATION, SurveillanceSettings.fallback(SurveillanceSettings.LOCATION))

    fun selected(): Volume? = volumes.mounted()[location()]

    fun eventsOn(volume: Volume): EventStore = EventStore(eventsDir(volume))

    /** Where the daemon was told to write, which is empty until that folder exists. */
    fun store(): EventStore {
        val published = Config.getString(SurveillanceSettings.EVENTS_DIR, "")
        publishedStore(published)?.let { return it }
        val clips = Config.getString(RecordingSettings.CLIPS_DIR, "")
        publishedStore(clips)?.let { return it }
        val volume = selected() ?: return EventStore(File(""))
        return eventsOn(volume)
    }

    fun usedMb(volume: Volume): Int = (totalBytes(eventsOn(volume).list()) / MB).toInt()

    fun budgetMb(): Int = Config.getInt(
        SurveillanceSettings.BUDGET_MB, SurveillanceSettings.BUDGET_FALLBACK_MB
    )

    /** The daemon has no Context to find volumes with, so the app tells it where to write. */
    fun publish(shell: Shell) {
        forgetMounted()
        val root = volumes.rootFor(location()) ?: return
        publishWriteDir(shell, File(publicRoot(root.path), CLIPS_DIR), SurveillanceSettings.EVENTS_DIR)
    }

    fun reap() {
        val volume = selected() ?: return
        val budgetMb = budgetMb()
        val dropped = Retention(eventsOn(volume), budgetMb * MB).enforce(null)
        if (dropped > 0) Logs.d(TAG, "dropped $dropped oldest events to stay under $budgetMb MB")
    }
}

private fun publishedStore(path: String): EventStore? {
    if (path.isEmpty()) return null
    if (storageUuid(path) != null) return EventStore(File(path))
    val dir = File(path)
    return if (dir.exists()) EventStore(dir) else null
}

internal fun eventsDir(volume: Volume): File = File(publicRoot(volume.dir.path), CLIPS_DIR)

/**
 * Two features can point at one card, and neither slider may promise room the
 * other already claimed. On different volumes there is nothing to share.
 */
fun reservedOn(location: String, otherLocation: String, otherBudgetMb: Int): Int =
    if (location == otherLocation) otherBudgetMb else 0
