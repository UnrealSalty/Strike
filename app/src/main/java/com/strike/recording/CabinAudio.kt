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

// Encode microphone audio in the app and send it to the daemon's muxer.
class CabinAudio internal constructor(private val capture: (CabinAudio) -> Unit) {

    constructor() : this({ it.pump() })

    private val lock = Any()
    private var thread: Thread? = null
    private var unblock: (() -> Unit)? = null

    @Volatile
    private var running = false

    val isCapturing: Boolean get() = running

    fun start(): Boolean = synchronized(lock) {
        if (thread != null) return@synchronized running
        running = true
        val worker = Thread({
            try {
                capture(this)
            } catch (e: IOException) {
                if (running) Logs.w(TAG, "cabin audio connection ended: ${e.message}")
            } catch (e: RuntimeException) {
                Logs.w(TAG, "cabin audio stopped: ${e.message}")
            } finally {
                synchronized(lock) {
                    running = false
                    unblock = null
                    thread = null
                }
            }
        }, "cabin-audio").also { it.isDaemon = true }
        thread = worker
        try {
            worker.start()
            true
        } catch (e: RuntimeException) {
            thread = null
            running = false
            Logs.w(TAG, "cabin audio could not start: ${e.message}")
            false
        }
    }

    fun stop() {
        val pending = synchronized(lock) {
            running = false
            Pair(thread, unblock).also { unblock = null }
        }
        pending.second?.invoke()
        val worker = pending.first ?: return
        worker.join(2_000)
        if (worker.isAlive) Logs.w(TAG, "cabin audio is still stopping")
        else Logs.d(TAG, "cabin audio stopped")
    }

    internal fun onStop(action: () -> Unit) {
        val stopped = synchronized(lock) {
            if (running) {
                unblock = action
                false
            } else true
        }
        if (stopped) action()
    }

    private fun aacEncoder(): MediaCodec {
        val wanted = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS)
        wanted.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        wanted.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE_BPS)
        val fresh = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        var started = false
        try {
            fresh.configure(wanted, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            fresh.start()
            started = true
            return fresh
        } finally {
            if (!started) fresh.release()
        }
    }

    private fun pump() {
        val socket = Socket()
        val inputLock = Any()
        var input: AudioRecord? = null
        var encoder: MediaCodec? = null
        var inputStarted = false
        onStop {
            close(socket)
            synchronized(inputLock) {
                if (inputStarted) {
                    inputStarted = false
                    stopInput(input!!)
                }
            }
        }
        try {
            socket.connect(InetSocketAddress(HOST, AUDIO_PORT), CONNECT_MS)
            socket.tcpNoDelay = true
            if (!running) return
            val minBytes = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBytes <= 0) {
                Logs.w(TAG, "this car reports no usable microphone")
                return
            }
            val microphone = synchronized(inputLock) {
                if (!running) return
                AudioRecord(
                    MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, minBytes * 2
                ).also { input = it }
            }
            if (microphone.state != AudioRecord.STATE_INITIALIZED) {
                Logs.w(TAG, "the microphone is held by something else")
                return
            }
            if (!running) return
            val aac = aacEncoder().also { encoder = it }
            synchronized(inputLock) {
                if (!running) return
                microphone.startRecording()
                inputStarted = true
            }
            val link = DataOutputStream(socket.getOutputStream())
            val pcm = ByteArray(minBytes)
            val info = MediaCodec.BufferInfo()
            val clock = AudioClock(System.nanoTime() / 1_000, SAMPLE_RATE)
            var announced = false
            Logs.d(TAG, "cabin audio started")
            while (running) {
                val read = microphone.read(pcm, 0, pcm.size)
                if (read < 0) {
                    if (running) Logs.w(TAG, "the microphone stopped sending audio ($read)")
                    return
                }
                if (read > 0) feed(aac, pcm, read, clock.read(read))
                announced = drain(aac, info, link, announced)
            }
        } finally {
            close(socket)
            try {
                synchronized(inputLock) {
                    val microphone = input
                    input = null
                    if (microphone != null) {
                        try {
                            if (inputStarted) {
                                inputStarted = false
                                stopInput(microphone)
                            }
                        } finally {
                            microphone.release()
                        }
                    }
                }
            } finally {
                val aac = encoder
                if (aac != null) {
                    try {
                        aac.stop()
                    } catch (e: IllegalStateException) {
                        Logs.w(TAG, "the audio encoder could not stop: ${e.message}")
                    } finally {
                        aac.release()
                    }
                }
            }
        }
    }

    /** AAC input buffers are a frame, often 2 KB. The microphone read is larger. */
    private fun feed(encoder: MediaCodec, pcm: ByteArray, length: Int, firstTimeUs: Long) {
        var offset = 0
        while (offset < length && running) {
            val index = encoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            if (index < 0) return
            val buffer = encoder.getInputBuffer(index) ?: return
            buffer.clear()
            val n = minOf(buffer.remaining(), length - offset)
            if (n <= 0) return
            buffer.put(pcm, offset, n)
            val timeUs = firstTimeUs + (offset / 2 * 1_000_000L / SAMPLE_RATE)
            encoder.queueInputBuffer(index, 0, n, timeUs, 0)
            offset += n
        }
    }

    private fun drain(
        encoder: MediaCodec,
        info: MediaCodec.BufferInfo,
        link: DataOutputStream,
        announced: Boolean
    ): Boolean {
        var sentConfig = announced
        while (running) {
            val index = encoder.dequeueOutputBuffer(info, 0)
            if (index < 0) return sentConfig
            try {
                val buffer = encoder.getOutputBuffer(index)
                if (buffer != null && info.size > 0) {
                    val bytes = ByteArray(info.size)
                    buffer.position(info.offset)
                    buffer.get(bytes)
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (isConfig) {
                        sendConfig(link, bytes)
                        sentConfig = true
                    } else if (sentConfig) {
                        send(link, AUDIO_KIND_FRAME, info.presentationTimeUs, bytes)
                    }
                }
            } finally {
                encoder.releaseOutputBuffer(index, false)
            }
        }
        return sentConfig
    }

    private fun sendConfig(link: DataOutputStream, csd: ByteArray) {
        link.writeByte(AUDIO_KIND_CONFIG)
        link.writeLong(0)
        link.writeInt(csd.size)
        link.write(csd)
        link.writeInt(SAMPLE_RATE)
        link.writeInt(CHANNELS)
        link.writeInt(BITRATE_BPS)
        link.flush()
    }

    private fun send(link: DataOutputStream, kind: Int, timeUs: Long, bytes: ByteArray) {
        link.writeByte(kind)
        link.writeLong(timeUs)
        link.writeInt(bytes.size)
        link.write(bytes)
        link.flush()
    }

    private fun stopInput(input: AudioRecord) {
        try {
            input.stop()
        } catch (e: IllegalStateException) {
            Logs.w(TAG, "the microphone could not stop: ${e.message}")
        }
    }

    private fun close(socket: Socket) {
        try {
            socket.close()
        } catch (e: IOException) {
            // The daemon may have disconnected before capture stops.
        }
    }
}
