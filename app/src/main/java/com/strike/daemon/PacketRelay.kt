package com.strike.daemon

import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

private const val TAG = "Relay"
private const val BACKLOG = 2

/** Flags byte, then the microsecond timestamp, then Annex-B NAL bytes. */
const val PACKET_HEADER_BYTES = 9
const val PACKET_FLAG_KEYFRAME = 1
const val PACKET_FLAG_CONFIG = 2

/**
 * Encoded frames from the daemon to the app, which relays them to browsers
 * untouched. The header travels all the way to the JavaScript muxer so the app
 * never has to parse video it does not own.
 *
 * One reader at a time: the app is the only consumer, and a frame dropped
 * because it is slow is better than a queue that grows without limit.
 */
class PacketRelay {

    @Volatile
    private var out: DataOutputStream? = null

    @Volatile
    private var running = true

    private var server: ServerSocket? = null

    val hasReader: Boolean
        get() = out != null

    fun serveForever() {
        while (running) {
            try {
                val listening = ServerSocket(PACKET_PORT, BACKLOG, InetAddress.getByName("127.0.0.1"))
                server = listening
                DaemonLog.d(TAG, "live packets on 127.0.0.1:$PACKET_PORT")
                while (running && !listening.isClosed) {
                    hold(listening.accept())
                }
            } catch (e: IOException) {
                if (running) {
                    DaemonLog.e(TAG, "live packet port failed: ${e.message}")
                    Thread.sleep(1_000)
                }
            }
        }
    }

    private fun hold(client: Socket) {
        client.tcpNoDelay = true
        out?.let { close(it) }
        out = DataOutputStream(client.getOutputStream())
    }

    fun send(bytes: ByteArray, timeUs: Long, flags: Int) {
        val stream = out ?: return
        try {
            synchronized(stream) {
                stream.writeInt(PACKET_HEADER_BYTES + bytes.size)
                stream.writeByte(flags)
                stream.writeLong(timeUs)
                stream.write(bytes)
                stream.flush()
            }
        } catch (e: IOException) {
            DaemonLog.d(TAG, "the app stopped reading live packets")
            close(stream)
            out = null
        }
    }

    fun stop() {
        running = false
        out?.let { close(it) }
        out = null
        try {
            server?.close()
        } catch (e: IOException) {
            // Already gone.
        }
    }

    private fun close(stream: DataOutputStream) {
        try {
            stream.close()
        } catch (e: IOException) {
            // Nothing to do with a socket the reader already dropped.
        }
    }
}
