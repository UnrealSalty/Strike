package com.strike.server

import com.strike.core.Logs
import com.strike.camera.LiveQuality
import com.strike.daemon.DaemonClient
import java.util.concurrent.CopyOnWriteArrayList

private const val TAG = "LiveStream"
private const val LEAVE_MS = 400L

// Start the live encoder for the first viewer and stop it when the last viewer leaves.
class LiveStream(private val daemon: DaemonClient) {

    private class Viewer(val socket: WebSocket, val quality: LiveQuality)

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
        try {
            socket.awaitClose()
        } finally {
            val mine = synchronized(gate) {
                viewers.remove(viewer)
                if (viewers.isNotEmpty()) configure(view)
                ++epoch
            }
            Logs.d(TAG, "live viewer left, ${viewers.size} watching")
            Thread.sleep(LEAVE_MS)
            synchronized(gate) {
                if (viewers.isEmpty() && epoch == mine) {
                    packets.stop()
                    daemon.liveStop()
                    bitrateBps = 0
                }
            }
        }
    }

    fun send(packet: ByteArray, offset: Int, length: Int) {
        for (viewer in viewers) {
            viewer.socket.send(packet, offset, length)
        }
    }

    // One live encoder serves every viewer; the lowest requested bitrate limits bandwidth.
    private fun configure(view: String, joining: Boolean = false) {
        val wanted = viewers.minOf { it.quality.bitrateBps }
        if ((joining || wanted != bitrateBps) && daemon.liveStart(view, wanted)) bitrateBps = wanted
    }
}
