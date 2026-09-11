package com.strike.recording

import android.media.MediaCodec
import android.media.MediaFormat
import com.strike.camera.CameraView
import com.strike.camera.Consumer
import com.strike.camera.Frame
import com.strike.camera.FrameBus
import com.strike.camera.frameOf
import com.strike.daemon.AudioIngest
import com.strike.daemon.DaemonLog
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

private const val TAG = "Recorder"
private const val CONSUMER = "recorder"
private const val QUEUED_SAMPLES = 240
private const val TAKE_TIMEOUT_MS = 500L
// Allow HAL startup before declaring the encoder stalled; the first frame can take seconds.
private const val FORMAT_WAIT_MS = 25_000L
private const val FINALISE_WAIT_MS = 6_000L

// Wait briefly for the app's AAC format before starting the first muxer.
private const val AUDIO_WAIT_MS = 1_000L

data class RecordingOptions(
    val clipLengthMs: Long,
    val quality: String,
    val codec: String,
    val frameRateFps: Int,
    val audio: Boolean
)

// The writer thread owns muxing and rotates clips between encoded samples.
class Recorder(
    private val dir: File,
    private val mode: RecordingMode,
    private val audio: AudioIngest?,
    private val onClipFinished: () -> Unit
) {

    private val samples = ArrayBlockingQueue<Sample>(QUEUED_SAMPLES)

    private var bus: FrameBus? = null
    private var encoder: Encoder? = null
    private var writerThread: Thread? = null
    private var closer: Thread? = null
    private val toClose = ArrayBlockingQueue<ClipWriter>(8)

    @Volatile
    private var running = false

    @Volatile
    private var writerFinished = true

    @Volatile
    var clip: String? = null
        private set

    @Volatile
    var clipStartedAtMs = 0L
        private set

    @Volatile
    var frame: Frame? = null
        private set

    val isRecording: Boolean get() = running && encoder?.isDead != true

    fun start(options: RecordingOptions, bus: FrameBus): Boolean {
        if (running) return true
        sweepUnfinished(dir, System.currentTimeMillis())
        val wanted = frameOf(CameraView.ALL, bus.stripWidth, bus.stripHeight)
        val encoder = Encoder(
            wanted.width,
            wanted.height,
            options.frameRateFps,
            bitrateBps(options.quality, options.codec),
            mimeTypeOf(options.codec),
            ::enqueue
        )
        val surface = encoder.start() ?: return false
        samples.clear()
        running = true
        bus.add(Consumer(CONSUMER, surface, CameraView.ALL, wanted))
        this.bus = bus
        this.encoder = encoder
        frame = wanted
        writerFinished = false
        closer = Thread({ closeFinished() }, "finalise").also { it.start() }
        writerThread = Thread({
            try {
                writeClips(encoder, options)
            } catch (e: RuntimeException) {
                DaemonLog.e(TAG, "recording stopped writing: ${e.message}")
            } finally {
                running = false
                writerFinished = true
            }
        }, "clips").also { it.start() }
        DaemonLog.d(TAG, "recording ${wanted.width}x${wanted.height}, all angles")
        return true
    }

    fun stop() {
        running = false
        bus?.remove(CONSUMER)
        bus = null
        encoder?.stop()
        encoder = null
        writerThread?.join(FINALISE_WAIT_MS)
        writerThread = null
        closer?.join(FINALISE_WAIT_MS)
        closer = null
        clip = null
        frame = null
    }

    private fun writeClips(encoder: Encoder, options: RecordingOptions) {
        val format = awaitFormat(encoder) ?: return
        if (options.audio) awaitAudio()
        if (!running) return
        var current = openClip(format) ?: return
        val clipLengthMs = options.clipLengthMs

        try {
            while (running || samples.isNotEmpty()) {
                val sample = samples.poll(TAKE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                drainAudio(current)
                if (sample == null) {
                    if (running && elapsed(current) >= clipLengthMs) encoder.splitAtNextKeyFrame()
                    continue
                }
                if (running && shouldRotate(sample, current, clipLengthMs)) {
                    val done = current
                    current = openClip(format) ?: break
                    finish(done)
                }
                current.write(sample)
                if (running && elapsed(current) >= clipLengthMs) encoder.splitAtNextKeyFrame()
            }
        } finally {
            finish(current)
        }
    }

    // Tracks cannot be added after muxer start; audio changes apply at a clip boundary.
    private fun openClip(format: MediaFormat): ClipWriter? {
        val writer = ClipWriter(dir)
        if (!writer.open(mode, format, audio?.config)) {
            running = false
            return null
        }
        clipStartedAtMs = writer.startedAtMs
        clip = writer.name
        return writer
    }

    private fun awaitAudio() {
        val queue = audio ?: return
        val deadline = System.currentTimeMillis() + AUDIO_WAIT_MS
        while (running && queue.config == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        if (queue.config == null) DaemonLog.w(TAG, "the microphone is not sending, this clip has no sound")
    }

    private fun drainAudio(writer: ClipWriter) {
        val queue = audio ?: return
        if (!writer.hasAudio) {
            queue.clear()
            return
        }
        while (true) {
            val sample = queue.take() ?: return
            writer.writeAudio(sample)
        }
    }

    // Preserve splice samples: losing one can prevent the next clip from opening.
    private fun enqueue(sample: Sample) {
        if (!running) return
        if (sample.startsClip) {
            try {
                while (running) {
                    if (samples.offer(sample, TAKE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            return
        }
        if (samples.offer(sample)) return
        samples.poll()
        samples.offer(sample)
    }

    private fun shouldRotate(sample: Sample, writer: ClipWriter, clipLengthMs: Long): Boolean =
        clipShouldRotate(
            sample.startsClip,
            elapsed(writer),
            clipLengthMs,
            sample.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        )

    private fun elapsed(writer: ClipWriter): Long = System.currentTimeMillis() - writer.startedAtMs

    // Finalize muxers off the writer thread so closing a clip cannot block sample delivery.
    private fun finish(writer: ClipWriter) {
        if (!toClose.offer(writer)) {
            if (writer.close()) onClipFinished()
        }
    }

    private fun closeFinished() {
        while (true) {
            val writer = toClose.poll(200, TimeUnit.MILLISECONDS)
            if (writer != null) {
                if (writer.close()) onClipFinished()
                continue
            }
            if (writerFinished && toClose.isEmpty()) return
        }
    }

    private fun awaitFormat(encoder: Encoder): MediaFormat? {
        val deadline = System.currentTimeMillis() + FORMAT_WAIT_MS
        while (running && System.currentTimeMillis() < deadline) {
            encoder.format?.let { return it }
            Thread.sleep(50)
        }
        if (running) {
            DaemonLog.e(TAG, "no frame reached the encoder in ${FORMAT_WAIT_MS / 1000}s, no clip was written")
            running = false
        }
        return null
    }
}

/** A lost splice still rotates on the next keyframe once the clip is due. */
internal fun clipShouldRotate(
    startsClip: Boolean,
    elapsedMs: Long,
    clipLengthMs: Long,
    keyFrame: Boolean
): Boolean {
    if (startsClip) return true
    return elapsedMs >= clipLengthMs && keyFrame
}
