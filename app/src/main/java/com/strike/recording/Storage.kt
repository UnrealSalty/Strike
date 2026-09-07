package com.strike.recording

import android.content.Context
import com.strike.core.Config
import com.strike.core.Logs
import com.strike.daemon.Shell
import java.io.File
import java.io.IOException

private const val TAG = "Storage"
private const val CLIPS_DIR = "Strike/clips"

/** Decides which volume clips live on and how much room they may take. */
class Storage(context: Context, shell: Shell) {

    private val volumes = Volumes(context, shell)

    fun mounted(): Map<String, Volume> = volumes.mounted()

    fun location(): String =
        Config.getString(RecordingSettings.LOCATION, RecordingSettings.fallback(RecordingSettings.LOCATION))

    fun selected(): Volume? = volumes.mounted()[location()]

    fun clipsOn(volume: Volume): ClipStore = ClipStore(clipsDir(volume))

    fun usedMb(volume: Volume): Int = (totalBytes(clipsOn(volume).list()) / MB).toInt()

    fun budgetMb(): Int =
        Config.getInt(RecordingSettings.BUDGET_MB, RecordingSettings.BUDGET_FALLBACK_MB)

    /** The daemon has no Context to find volumes with, so the app tells it where to write. */
    fun publish(shell: Shell) {
        forgetMounted()
        val root = volumes.rootFor(location()) ?: return
        publishWriteDir(shell, File(publicRoot(root.path), CLIPS_DIR), RecordingSettings.CLIPS_DIR)
    }

    fun reap() {
        val volume = selected() ?: return
        val budgetMb = budgetMb()
        val dropped = Retention(clipsOn(volume), budgetMb * MB).enforce(null)
        if (dropped > 0) Logs.d(TAG, "dropped $dropped oldest clips to stay under $budgetMb MB")
    }
}

/**
 * Clips sit at the top of the volume, not under Android/data, because the
 * daemon writes them as uid 2000 and cannot create files in the app's own
 * folder. Overdrive keeps its own recordings there for the same reason.
 */
internal fun clipsDir(volume: Volume): File = File(publicRoot(volume.dir.path), CLIPS_DIR)

internal fun publicRoot(path: String): String {
    val marker = path.indexOf("/Android/")
    return if (marker > 0) path.substring(0, marker) else path
}

/**
 * The app uid can mkdir on /storage/<uuid>. The daemon (uid 2000) cannot,
 * which is why events never appeared while an already-created clips folder
 * kept taking files. Shell mkdir is only a backup; chmod does not stick on
 * FUSE, so a real write from this process is the probe.
 *
 * A card that will not take a probe still keeps this path. The daemon
 * remounts before it writes. Switching config to internal is what left
 * parked clips on the phone storage after the owner picked the card.
 */
internal fun publishWriteDir(shell: Shell, wanted: File, key: String) {
    val ready = prepareDir(wanted, shell)
    val path = keepWritePath(ready, wanted.absolutePath) ?: run {
        Logs.w(TAG, "cannot write ${wanted.path}")
        return
    }
    if (!ready) Logs.w(TAG, "${wanted.path} will not take files yet")
    if (Config.getString(key, "") == path) return
    Config.put(shell, key, path)
}

internal fun keepWritePath(ready: Boolean, path: String): String? {
    if (ready) return path
    if (storageUuid(path) != null) return path
    return null
}

internal fun prepareDir(dir: File, shell: Shell?): Boolean {
    if (storageUuid(dir.path) != null) remount(dir)
    if (!dir.exists() && !dir.mkdirs()) {
        shell?.check("mkdir -p \"${dir.absolutePath}\"")
    }
    if (!dir.exists()) return false
    openForDaemon(dir)
    val probe = File(dir, ".probe")
    return try {
        probe.writeText("ok")
        probe.delete()
        true
    } catch (e: IOException) {
        false
    }
}

private fun openForDaemon(dir: File) {
    var walk: File? = dir
    repeat(3) {
        val at = walk ?: return
        at.setReadable(true, false)
        at.setWritable(true, false)
        at.setExecutable(true, false)
        walk = at.parentFile
    }
}
