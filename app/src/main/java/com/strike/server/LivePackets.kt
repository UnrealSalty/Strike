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
    private var socket: Socket? = null

    @Synchronized
    fun start() {
        if (socket != null) return
        val opened = Socket()
        socket = opened
        Thread({ pump(opened) }, "strike-packets").start()
    }

    @Synchronized
    fun stop() {
        val closing = socket
        socket = null
        try {
            closing?.close()
        } catch (e: IOException) {
            Logs.d(TAG, "live packet connection did not close cleanly")
        }
    }

    private fun pump(opened: Socket) {
        try {
            opened.use {
                it.connect(InetSocketAddress(HOST, PACKET_PORT), CONNECT_MS)
                it.soTimeout = IDLE_MS
                relay(DataInputStream(it.getInputStream()), it)
            }
        } catch (e: IOException) {
            if (socket === opened) Logs.d(TAG, "live frames stopped arriving")
        } finally {
            synchronized(this) { if (socket === opened) socket = null }
        }
    }

    private fun relay(input: DataInputStream, opened: Socket) {
        var forwarded = 0L
        while (socket === opened) {
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
