package com.strike.daemon

import com.strike.recording.Sample
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue

private const val TAG = "Audio"
private const val BACKLOG = 2
private const val QUEUED_FRAMES = 200

/** One frame of AAC is about 21 ms, so this is a second of slack. */
private const val CSD_MAX_BYTES = 64

const val AUDIO_KIND_CONFIG = 1
const val AUDIO_KIND_FRAME = 2

/** What the muxer needs to describe an AAC track before it is started. */
class AudioConfig(
    val sampleRate: Int,
    val channelCount: Int,
    val bitrateBps: Int,
    val csd: ByteArray
)

/**
 * Encoded cabin audio arriving from the app. Timestamps on both sides come
 * from [System.nanoTime], so audio and video share one clock across the two
 * processes and the muxer can interleave them without a correction.
 */
class AudioIngest {

    private val frames = ArrayBlockingQueue<Sample>(QUEUED_FRAMES)

    @Volatile
    var config: AudioConfig? = null
        private set

    @Volatile
    private var running = true

    private var server: ServerSocket? = null

    fun serveForever() {
        while (running) {
            try {
                val listening = ServerSocket(AUDIO_PORT, BACKLOG, InetAddress.getByName("127.0.0.1"))
                server = listening
                DaemonLog.d(TAG, "cabin audio on 127.0.0.1:$AUDIO_PORT")
                while (running && !listening.isClosed) {
                    read(listening.accept())
                }
            } catch (e: IOException) {
                if (running) {
                    DaemonLog.e(TAG, "cabin audio port failed: ${e.message}")
                    Thread.sleep(1_000)
                }
            }
        }
    }

    /** Drops the oldest rather than blocking; a stalled clip must not stall capture. */
    fun take(): Sample? = frames.poll()

    fun clear() = frames.clear()

    fun stop() {
        running = false
        try {
            server?.close()
        } catch (e: IOException) {
            // Already gone.
        }
    }

    private fun read(client: Socket) {
        client.tcpNoDelay = true
        try {
            client.use { accept(DataInputStream(it.getInputStream())) }
        } catch (e: EOFException) {
            DaemonLog.d(TAG, "the app stopped sending cabin audio")
        } catch (e: IOException) {
            if (running) DaemonLog.d(TAG, "cabin audio ended: ${e.message}")
        }
        config = null
        frames.clear()
    }

    private fun accept(input: DataInputStream) {
        var counted = 0L
        while (running) {
            val kind = input.readByte().toInt()
            val timeUs = input.readLong()
            val length = input.readInt()
            if (length < 0 || length > QUEUED_FRAMES * 1024) {
                throw IOException("cabin audio frame claimed $length bytes")
            }
            val bytes = ByteArray(length)
            input.readFully(bytes)
            if (kind == AUDIO_KIND_CONFIG) {
                config = configOf(input, bytes)
                continue
            }
            if (frames.remainingCapacity() == 0) frames.poll()
            frames.offer(Sample(bytes, timeUs, 0, false))
            counted++
            if (counted == 1L) DaemonLog.d(TAG, "first cabin audio frame, $length bytes")
        }
    }

    private fun configOf(input: DataInputStream, csd: ByteArray): AudioConfig {
        val sampleRate = input.readInt()
        val channelCount = input.readInt()
        val bitrateBps = input.readInt()
        if (csd.isEmpty() || csd.size > CSD_MAX_BYTES) {
            throw IOException("cabin audio sent a ${csd.size} byte header")
        }
        DaemonLog.d(TAG, "cabin audio is ${sampleRate}Hz, $channelCount channel")
        return AudioConfig(sampleRate, channelCount, bitrateBps, csd)
    }
}
