package com.strike.server

import com.strike.core.Logs
import com.strike.camera.LiveQuality
import com.strike.daemon.DaemonClient
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList

private const val TAG = "LiveStream"
private const val LEAVE_MS = 400L
private const val PENDING_PACKETS = 16

// Start the live encoder for the first viewer and stop it when the last viewer leaves.
class LiveStream(private val daemon: DaemonClient) {

    private class Packet(val bytes: ByteArray, val offset: Int, val length: Int)

    private class Viewer(val socket: WebSocket, val quality: LiveQuality) {
        private val pending = ArrayBlockingQueue<Packet>(PENDING_PACKETS)
        private val end = Packet(ByteArray(0), 0, 0)

        fun offer(packet: Packet) {
            if (socket.isClosed) return
            if (!pending.offer(packet)) {
                Logs.d(TAG, "live viewer fell behind, closing its connection")
                close()
            }
        }

        fun write() {
            while (!socket.isClosed) {
                val packet = pending.take()
                if (packet === end) return
                socket.send(packet.bytes, packet.offset, packet.length)
            }
        }

        fun close() {
            socket.close()
            pending.clear()
            pending.offer(end)
        }
    }

    private val viewers = CopyOnWriteArrayList<Viewer>()
    private val packets = LivePackets(this)
    private val gate = Any()

    @Volatile
    private var epoch = 0
    private var bitrateBps = 0

    val isWatched: Boolean
        get() = viewers.isNotEmpty()

    // Stream one mosaic; browsers crop angles without restarting the encoder.
    fun serve(socket: WebSocket, view: String, quality: LiveQuality) {
        val viewer = Viewer(socket, quality)
        synchronized(gate) {
            viewers.add(viewer)
            epoch++
            configure(view, joining = true)
            packets.start()
        }
        Logs.d(TAG, "live viewer joined for $view, ${viewers.size} watching")
        val control = Thread({
            try {
                socket.awaitClose()
            } finally {
                viewer.close()
            }
        }, "live-control").also { it.isDaemon = true }
        var interrupted = false
        try {
            control.start()
            viewer.write()
        } catch (e: InterruptedException) {
            interrupted = true
        } finally {
            viewer.close()
            val mine = synchronized(gate) {
                viewers.remove(viewer)
                if (viewers.isNotEmpty()) configure(view)
                ++epoch
            }
            Logs.d(TAG, "live viewer left, ${viewers.size} watching")
            try {
                control.join(1_000)
                Thread.sleep(LEAVE_MS)
            } catch (e: InterruptedException) {
                interrupted = true
            } finally {
                synchronized(gate) {
                    if (viewers.isEmpty() && epoch == mine) {
                        packets.stop()
                        daemon.liveStop()
                        bitrateBps = 0
                    }
                }
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
    }

    fun send(packet: ByteArray, offset: Int, length: Int) {
        val frame = Packet(packet, offset, length)
        for (viewer in viewers) viewer.offer(frame)
    }

    // One live encoder serves every viewer; the lowest requested bitrate limits bandwidth.
    private fun configure(view: String, joining: Boolean = false) {
        val wanted = viewers.minOf { it.quality.bitrateBps }
        if ((joining || wanted != bitrateBps) && daemon.liveStart(view, wanted)) bitrateBps = wanted
    }
}
