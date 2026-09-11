package com.strike.recording

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import com.strike.daemon.AudioConfig
import com.strike.daemon.DaemonLog
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "Clip"
private const val WRITING = ".tmp"
private const val BROKEN = ".broken"
private const val FAST = ".fast"
private const val SWEEP_AGE_MS = 5 * 60_000L

// Keep .tmp until the muxer closes successfully; stores only list finalized clips.
class ClipWriter(private val dir: File) {

    private var muxer: MediaMuxer? = null
    private var track = -1
    private var audioTrack = -1
    private var writing: File? = null
    private var out = dir

    val hasAudio: Boolean get() = audioTrack >= 0

    var name: String? = null
        private set

    var startedAtMs = 0L
        private set

    fun open(mode: RecordingMode, format: MediaFormat, audio: AudioConfig? = null): Boolean {
        val dest = writableDir(mode == RecordingMode.DRIVE || mode == RecordingMode.MANUAL)
            ?: return false
        out = dest
        val clip = clipName(mode, System.currentTimeMillis())
        val file = File(dest, clip + WRITING)
        var fresh: MediaMuxer? = null
        return try {
            fresh = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            track = fresh.addTrack(format)
            audioTrack = if (audio == null) -1 else fresh.addTrack(aacFormat(audio))
            fresh.start()
            muxer = fresh
            writing = file
            name = clip
            startedAtMs = System.currentTimeMillis()
            true
        } catch (e: Exception) {
            DaemonLog.e(TAG, "cannot start a clip on this volume: ${e.message}")
            if (fresh != null) release(fresh, clip)
            track = -1
            audioTrack = -1
            false
        }
    }

    // Remount before checking the FUSE path; isDirectory can be stale after ACC off.
    private fun writableDir(mayUseInternal: Boolean): File? {
        if (storageUuid(dir.path) != null) remountUntil(dir)
        if (dir.exists() || ensureDir(dir)) return dir
        if (mayUseInternal) {
            val internal = File("/storage/emulated/0/Strike/clips")
            if (internal != dir && (internal.exists() || ensureDir(internal))) {
                DaemonLog.w(TAG, "cannot write $dir, writing to $internal")
                return internal
            }
        }
        DaemonLog.e(TAG, "cannot create $dir")
        return null
    }

    fun write(sample: Sample) = writeTo(track, sample)

    fun writeAudio(sample: Sample) {
        if (audioTrack < 0) return
        writeTo(audioTrack, sample)
    }

    private fun writeTo(which: Int, sample: Sample) {
        val open = muxer ?: return
        val info = MediaCodec.BufferInfo()
        info.set(0, sample.bytes.size, sample.timeUs, sample.flags)
        open.writeSampleData(which, ByteBuffer.wrap(sample.bytes), info)
    }

    // Only a successful stop writes the index required to list a playable clip.
    // Moving the index to the front is left to the caller; it copies the whole file and must
    // not run on the thread muxing live frames.
    fun close(): File? {
        val open = muxer ?: return null
        val file = writing
        muxer = null
        writing = null
        val clip = name
        name = null
        track = -1
        audioTrack = -1
        val stopped = try {
            open.stop()
            true
        } catch (e: RuntimeException) {
            DaemonLog.e(TAG, "clip $clip did not close cleanly, keeping it aside")
            false
        } finally {
            release(open, clip)
        }
        if (file == null || clip == null) return null
        if (!stopped) {
            file.renameTo(File(out, clip + BROKEN))
            return null
        }
        val finished = File(out, clip)
        if (!file.renameTo(finished)) {
            DaemonLog.e(TAG, "cannot rename $clip into place")
            return null
        }
        finished.setReadable(true, false)
        return finished
    }

    private fun release(muxer: MediaMuxer, clip: String?) {
        try {
            muxer.release()
        } catch (e: RuntimeException) {
            DaemonLog.e(TAG, "cannot release clip $clip: ${e.message}")
        }
    }

}

// The head unit's WebView reads the index out of the first bytes it is given.
fun indexUpFront(finished: File) {
    val scratch = File(finished.parentFile, finished.name + FAST)
    if (moovToFront(finished, scratch) && swap(scratch, finished)) return
    scratch.delete()
    DaemonLog.w(TAG, "clip ${finished.name} keeps its index at the end, the player may not start it")
}

// rename(2) replaces the clip in one step either way, so no reader ever sees a missing file.
private fun swap(scratch: File, finished: File): Boolean {
    if (!moved(scratch, finished) && !scratch.renameTo(finished)) return false
    finished.setReadable(true, false)
    return true
}

private fun moved(scratch: File, finished: File): Boolean = try {
    Files.move(
        scratch.toPath(), finished.toPath(),
        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
    )
    true
} catch (e: IOException) {
    false
}

internal fun ensureDir(dir: File): Boolean {
    if (dir.exists()) return true
    if (dir.mkdirs() && dir.exists()) {
        worldAccess(dir)
        return true
    }
    return try {
        ProcessBuilder("mkdir", "-p", dir.absolutePath).start().waitFor()
        if (!dir.exists()) return false
        worldAccess(dir)
        true
    } catch (e: IOException) {
        false
    }
}

private fun worldAccess(dir: File) {
    dir.setReadable(true, false)
    dir.setWritable(true, false)
    dir.setExecutable(true, false)
}

private fun aacFormat(audio: AudioConfig): MediaFormat {
    val format = MediaFormat.createAudioFormat(
        MediaFormat.MIMETYPE_AUDIO_AAC, audio.sampleRate, audio.channelCount
    )
    format.setInteger(MediaFormat.KEY_BIT_RATE, audio.bitrateBps)
    format.setByteBuffer("csd-0", ByteBuffer.wrap(audio.csd))
    return format
}

internal fun clipName(mode: RecordingMode, atMs: Long): String {
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(atMs))
    return "${mode.name.lowercase(Locale.US)}_$stamp.mp4"
}

// An unfinished MP4 may lack the index required for playback.
fun sweepUnfinished(dir: File, nowMs: Long) {
    val files = dir.listFiles() ?: return
    var swept = 0
    for (file in files) {
        val leftover = file.name.endsWith(WRITING) || file.name.endsWith(BROKEN) ||
            file.name.endsWith(FAST)
        if (leftover && nowMs - file.lastModified() > SWEEP_AGE_MS && file.delete()) swept++
    }
    if (swept > 0) DaemonLog.w(TAG, "$swept clip(s) were cut off mid-write and could not be played")
}
