package com.strike.recording

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import com.strike.core.Logs
import com.strike.daemon.AUDIO_KIND_CONFIG
import com.strike.daemon.AUDIO_KIND_FRAME
import com.strike.daemon.AUDIO_PORT
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

private const val TAG = "CabinAudio"
private const val HOST = "127.0.0.1"
private const val CONNECT_MS = 1_500
private const val SAMPLE_RATE = 48_000
private const val CHANNELS = 1
private const val BITRATE_BPS = 64_000
private const val DEQUEUE_TIMEOUT_US = 10_000L

/**
 * The microphone belongs to the app and the muxer belongs to the daemon, so
 * audio is encoded here and sent across. Presentation times come from
 * [System.nanoTime] because the camera's frames are stamped from the same
 * clock, which is what keeps the two tracks in step.
 */
class CabinAudio {

    private var record: AudioRecord? = null
    private var codec: MediaCodec? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    val isCapturing: Boolean get() = running

    fun start(): Boolean {
        if (running) return true
        val minBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBytes <= 0) {
            Logs.w(TAG, "this car reports no usable microphone")
            return false
        }
        val input = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBytes * 2
        )
        if (input.state != AudioRecord.STATE_INITIALIZED) {
            input.release()
            Logs.w(TAG, "the microphone is held by something else")
            return false
        }
        val encoder = try {
            aacEncoder()
        } catch (e: IOException) {
            input.release()
            Logs.w(TAG, "this car has no aac encoder: ${e.message}")
            return false
        }
        record = input
        codec = encoder
        running = true
        input.startRecording()
        thread = Thread({ pump(input, encoder, minBytes) }, "cabin-audio").also {
            it.isDaemon = true
            it.start()
        }
        Logs.d(TAG, "cabin audio started")
        return true
    }

    fun stop() {
        if (!running) return
        running = false
        thread?.join(2_000)
        thread = null
        release()
        Logs.d(TAG, "cabin audio stopped")
    }

    private fun aacEncoder(): MediaCodec {
        val wanted = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS)
        wanted.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        wanted.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE_BPS)
        val fresh = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        fresh.configure(wanted, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        fresh.start()
        return fresh
    }

    private fun pump(input: AudioRecord, encoder: MediaCodec, chunkBytes: Int) {
        val link = connect()
        if (link == null) {
            running = false
            return
        }
        val pcm = ByteArray(chunkBytes)
        val info = MediaCodec.BufferInfo()
        val startedAtUs = System.nanoTime() / 1_000
        var sentSamples = 0L
        var announced = false
        try {
            while (running) {
                val read = input.read(pcm, 0, pcm.size)
                if (read > 0) {
                    // Counted from the samples handed over, not the clock at
                    // read time, so a late buffer does not shift the track.
                    val timeUs = startedAtUs + (sentSamples * 1_000_000L / SAMPLE_RATE)
                    sentSamples += feed(encoder, pcm, read, timeUs)
                }
                announced = drain(encoder, info, link, announced)
            }
        } catch (e: IOException) {
            if (running) Logs.d(TAG, "the daemon stopped taking cabin audio")
        } catch (e: IllegalStateException) {
            Logs.w(TAG, "the audio encoder stopped: ${e.message}")
        } finally {
            running = false
            try {
                link.close()
            } catch (e: IOException) {
                // Nothing to do with a socket the daemon already dropped.
            }
        }
    }

    /** AAC input buffers are a frame, often 2 KB. The microphone read is larger. */
    private fun feed(encoder: MediaCodec, pcm: ByteArray, length: Int, firstTimeUs: Long): Long {
        var offset = 0
        var samples = 0L
        while (offset < length) {
            val index = encoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            if (index < 0) return samples
            val buffer = encoder.getInputBuffer(index) ?: return samples
            buffer.clear()
            val n = minOf(buffer.remaining(), length - offset)
            if (n <= 0) return samples
            buffer.put(pcm, offset, n)
            val timeUs = firstTimeUs + (samples * 1_000_000L / SAMPLE_RATE)
            encoder.queueInputBuffer(index, 0, n, timeUs, 0)
            offset += n
            samples += n / 2
        }
        return samples
    }

    private fun drain(
        encoder: MediaCodec,
        info: MediaCodec.BufferInfo,
        link: DataOutputStream,
        announced: Boolean
    ): Boolean {
        var sentConfig = announced
        while (true) {
            val index = encoder.dequeueOutputBuffer(info, 0)
            if (index < 0) return sentConfig
            val buffer = encoder.getOutputBuffer(index)
            if (buffer != null && info.size > 0) {
                val bytes = ByteArray(info.size)
                buffer.position(info.offset)
                buffer.get(bytes)
                val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                if (isConfig) {
                    // MediaMuxer will not take an aac track without this, and
                    // it can only be added before the muxer is started.
                    sendConfig(link, bytes)
                    sentConfig = true
                } else if (sentConfig) {
                    send(link, AUDIO_KIND_FRAME, info.presentationTimeUs, bytes)
                }
            }
            encoder.releaseOutputBuffer(index, false)
        }
    }

    private fun sendConfig(link: DataOutputStream, csd: ByteArray) {
        synchronized(link) {
            link.writeByte(AUDIO_KIND_CONFIG)
            link.writeLong(0)
            link.writeInt(csd.size)
            link.write(csd)
            link.writeInt(SAMPLE_RATE)
            link.writeInt(CHANNELS)
            link.writeInt(BITRATE_BPS)
            link.flush()
        }
    }

    private fun send(link: DataOutputStream, kind: Int, timeUs: Long, bytes: ByteArray) {
        synchronized(link) {
            link.writeByte(kind)
            link.writeLong(timeUs)
            link.writeInt(bytes.size)
            link.write(bytes)
            link.flush()
        }
    }

    private fun connect(): DataOutputStream? {
        return try {
            val socket = Socket()
            socket.connect(InetSocketAddress(HOST, AUDIO_PORT), CONNECT_MS)
            socket.tcpNoDelay = true
            DataOutputStream(socket.getOutputStream())
        } catch (e: IOException) {
            Logs.w(TAG, "the camera daemon is not taking cabin audio")
            null
        }
    }

    private fun release() {
        try {
            record?.stop()
        } catch (e: IllegalStateException) {
            // Already stopped.
        }
        record?.release()
        record = null
        try {
            codec?.stop()
        } catch (e: IllegalStateException) {
            // Already stopped.
        }
        codec?.release()
        codec = null
    }
}
