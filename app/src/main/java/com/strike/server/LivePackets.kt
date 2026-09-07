package com.strike.server

import com.strike.core.Logs
import com.strike.daemon.PACKET_FLAG_CONFIG
import com.strike.daemon.PACKET_PORT
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

private const val TAG = "LivePackets"
private const val HOST = "127.0.0.1"
private const val CONNECT_MS = 1_500
private const val IDLE_MS = 1_000

// Bound packet size before allocating memory.
private const val PACKET_MAX_BYTES = 1 shl 20

// Forward daemon packets unchanged; the browser parses and muxes the video.
class LivePackets(private val viewers: LiveStream) {

    @Volatile
    private var reading = false

    fun start() {
        if (reading) return
        reading = true
        Thread({ pump() }, "strike-packets").start()
    }

    fun stop() {
        reading = false
    }

    private fun pump() {
        val socket = try {
            Socket().also { it.connect(InetSocketAddress(HOST, PACKET_PORT), CONNECT_MS) }
        } catch (e: IOException) {
            Logs.w(TAG, "the camera daemon is not offering live frames")
            reading = false
            return
        }
        try {
            socket.soTimeout = IDLE_MS
            socket.use { relay(DataInputStream(it.getInputStream())) }
        } catch (e: IOException) {
            if (reading) Logs.d(TAG, "live frames stopped arriving")
        } finally {
            reading = false
        }
    }

    // The read has to time out, or a camera that never produces a frame would
    // leave this thread blocked past the last viewer leaving.
    private fun relay(input: DataInputStream) {
        var forwarded = 0L
        while (reading && viewers.isWatched) {
            val length = try {
                input.readInt()
            } catch (e: SocketTimeoutException) {
                continue
            }
            if (length <= 0) {
                throw IOException("live packet claimed $length bytes")
            }
            if (length > PACKET_MAX_BYTES) {
                input.skipBytes(length)
                Logs.w(TAG, "dropped a live frame of $length bytes")
                continue
            }
            val packet = ByteArray(length)
            input.readFully(packet)
            viewers.send(packet, 0, length)
            forwarded++
            if (forwarded == 1L) {
                val kind = if (packet.isNotEmpty() && packet[0].toInt() and PACKET_FLAG_CONFIG != 0) {
                    "config"
                } else {
                    "frame"
                }
                Logs.d(TAG, "first live $kind reached the browser, $length bytes")
            }
            if (forwarded % 240 == 0L) Logs.d(TAG, "$forwarded live frames sent to the browser")
        }
    }
}
