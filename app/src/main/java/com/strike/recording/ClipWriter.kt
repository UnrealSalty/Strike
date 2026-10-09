package com.strike.recording

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import com.strike.daemon.AudioConfig
import com.strike.daemon.AudioStart
import com.strike.daemon.DaemonLog
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "Clip"
private const val WRITING = ".tmp"
private const val BROKEN = ".broken"
internal const val FAST = ".fast"
private const val SWEEP_AGE_MS = 5 * 60_000L

// Covers the event pre-roll and the wait for the keyframe that ends a clip.
private const val CLIP_SLACK_MS = 30_000L
private val LONGEST_CLIP_MS =
    RecordingSettings.choices.getValue(RecordingSettings.CLIP_LENGTH_MINUTES).options.maxOf { it.toLong() } * 60_000L

// Keep .tmp until the clip closes successfully; stores only list finalized clips.
class ClipWriter(
    private val dir: File,
    private val output: (File, String, Boolean, (File) -> Unit) -> File = { root, name, internal, open ->
        openClipOutput(root, name, internal, open = open).first
    }
) {

    private var mp4: Mp4File? = null
    private var muxer: MediaMuxer? = null
    private var track = -1
    private var audioTrack = -1
    private var writing: File? = null
    private var out = dir
    private var videoSamples = 0L
    private var audioSamples = 0L
    private var droppedSamples = 0L

    internal var releaseFailed = false
        private set

    internal var outputFailure: IOException? = null
        private set

    /** Writing stopped mid-clip; the clip keeps what its last index checkpoint names. */
    internal var writeFailure: IOException? = null
        private set

    /** Written by Android's muxer, which leaves the index at the end. */
    internal var needsIndexMove = false
        private set

    internal val directory: File get() = out

    internal val isNearlyFull: Boolean get() = mp4?.isNearlyFull == true

    val hasAudio: Boolean get() = audioTrack >= 0

    var name: String? = null
        private set

    var startedAtMs = 0L
        private set

    fun open(
        mode: RecordingMode,
        format: MediaFormat,
        audio: AudioStart? = null,
        clipLengthMs: Long = LONGEST_CLIP_MS
    ): Boolean {
        val clip = clipName(mode, System.currentTimeMillis())
        outputFailure = null
        writeFailure = null
        val own = try {
            videoTrackOf(format).also { if (audio != null) aacEntry(audio.config) }
        } catch (e: UnreadableCodecHeader) {
            DaemonLog.w(TAG, "${e.message}; clip $clip goes through Android's muxer")
            null
        }
        var freshMuxer: MediaMuxer? = null
        var freshFile: Mp4File? = null
        return try {
            val file = output(dir, clip + WRITING, mode == RecordingMode.DRIVE || mode == RecordingMode.MANUAL) {
                if (own == null) {
                    freshMuxer = MediaMuxer(it.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                } else {
                    val handle = RandomAccessFile(it, "rw")
                    freshFile = try {
                        Mp4File(handle, own, audio?.config, minOf(clipLengthMs, LONGEST_CLIP_MS) + CLIP_SLACK_MS)
                    } catch (e: IOException) {
                        handle.close()
                        throw e
                    }
                }
            }
            out = file.parentFile!!
            val opened = freshMuxer
            if (opened != null) {
                track = opened.addTrack(format)
                audioTrack = if (audio == null) -1 else opened.addTrack(aacFormat(audio.config))
                opened.start()
            } else {
                track = 0
                audioTrack = if (audio == null) -1 else 1
            }
            muxer = opened
            mp4 = freshFile
            needsIndexMove = opened != null
            writing = file
            name = clip
            startedAtMs = System.currentTimeMillis()
            if (audio != null) writeAudio(audio.first)
            true
        } catch (e: Exception) {
            val openedMuxer = freshMuxer
            val openedFile = freshFile
            if (openedMuxer == null && openedFile == null && e is IOException) outputFailure = e
            else DaemonLog.e(TAG, "cannot start a clip on this volume: ${e.message}")
            if (openedMuxer != null) release(openedMuxer, clip)
            if (openedFile != null) abandon(openedFile, clip)
            muxer = null
            mp4 = null
            writing = null
            name = null
            track = -1
            audioTrack = -1
            false
        }
    }

    fun write(sample: Sample) = writeTo(track, sample)

    fun writeAudio(sample: Sample) {
        if (audioTrack < 0) return
        writeTo(audioTrack, sample)
    }

    private fun writeTo(which: Int, sample: Sample) {
        val own = mp4
        if (own != null) {
            if (writeFailure != null) return
            val written = try {
                if (which == track) {
                    own.writeVideo(sample.bytes, sample.timeUs, sample.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
                } else {
                    own.writeAudio(sample.bytes, sample.timeUs)
                }
            } catch (e: IOException) {
                writeFailure = e
                DaemonLog.e(TAG, "clip $name stopped writing after $videoSamples video samples: ${e.message}")
                return
            }
            if (!written && droppedSamples++ == 0L) {
                DaemonLog.w(TAG, "clip $name has no index room left; dropping samples until it ends")
            }
            if (written) {
                if (which == track) videoSamples++ else audioSamples++
            }
            return
        }
        val open = muxer ?: return
        val info = MediaCodec.BufferInfo()
        info.set(0, sample.bytes.size, sample.timeUs, sample.flags)
        open.writeSampleData(which, ByteBuffer.wrap(sample.bytes), info)
        if (which == track) videoSamples++ else audioSamples++
    }

    // A muxer clip gets its index only from a successful stop, and the caller moves that index to
    // the front off this thread because it copies the whole file.
    fun close(): File? {
        val own = mp4
        if (own != null) return closeOwn(own)
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
            DaemonLog.e(TAG, "clip $clip did not close cleanly ($videoSamples video, $audioSamples audio samples, " +
                "${out.usableSpace / MB} MB free): ${e.message}; keeping it aside")
            false
        } finally {
            release(open, clip)
        }
        if (file == null || clip == null) return null
        if (!stopped) {
            file.renameTo(File(out, clip + BROKEN))
            return null
        }
        return publish(file, clip)
    }

    // A clip that cannot be finished stays .tmp, so the next start publishes its last checkpoint.
    private fun closeOwn(own: Mp4File): File? {
        val file = writing
        mp4 = null
        writing = null
        val clip = name
        name = null
        track = -1
        audioTrack = -1
        val finished = try {
            own.finish()
        } catch (e: IOException) {
            DaemonLog.e(TAG, "clip $clip could not be finished ($videoSamples video, $audioSamples audio samples, " +
                "${out.usableSpace / MB} MB free): ${e.message}; keeping what it saved")
            return null
        }
        if (file == null || clip == null) return null
        if (!finished) {
            file.renameTo(File(out, clip + BROKEN))
            return null
        }
        return publish(file, clip)
    }

    private fun abandon(file: Mp4File, clip: String) {
        try {
            file.abandon()
        } catch (e: IOException) {
            DaemonLog.w(TAG, "cannot close clip $clip: ${e.message}")
        }
    }

    private fun publish(file: File, clip: String): File? {
        val finished = File(out, clip)
        val published = try {
            withClipFileLock(finished) { file.renameTo(finished) }
        } catch (e: IOException) {
            DaemonLog.e(TAG, "cannot publish $clip: ${e.message}")
            return null
        }
        if (!published) {
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
            releaseFailed = true
            DaemonLog.e(TAG, "cannot release clip $clip: ${e.message}")
        }
    }

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

// A clip cut off mid-write is published if an index checkpoint reached it, and dropped if none did.
fun sweepUnfinished(dir: File, nowMs: Long) {
    val files = dir.listFiles() ?: return
    var swept = 0
    var recovered = 0
    for (file in files) {
        val leftover = file.name.endsWith(WRITING) || file.name.endsWith(BROKEN) ||
            file.name.endsWith(FAST)
        if (!leftover || nowMs - file.lastModified() <= SWEEP_AGE_MS) continue
        if (file.name.endsWith(WRITING) && !lacksClipIndex(file)) {
            if (sealUnfinished(file) && publishRecovered(file)) recovered++
            continue
        }
        if (file.delete()) swept++
    }
    if (swept > 0) DaemonLog.w(TAG, "$swept clip(s) were cut off mid-write and could not be played")
    if (recovered > 0) DaemonLog.w(TAG, "$recovered clip(s) were cut off mid-write and kept up to their last save")
}

private fun publishRecovered(file: File): Boolean {
    val finished = File(file.parentFile, file.name.removeSuffix(WRITING))
    return try {
        withClipFileLock(finished) { !finished.exists() && file.renameTo(finished) }.also {
            if (it) finished.setReadable(true, false)
        }
    } catch (e: IOException) {
        false
    }
}
