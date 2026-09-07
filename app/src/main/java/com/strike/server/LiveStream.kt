package com.strike.server

import com.strike.core.Logs
import com.strike.daemon.DaemonClient
import java.util.concurrent.CopyOnWriteArrayList

private const val TAG = "LiveStream"
private const val LEAVE_MS = 400L

// Start the live encoder for the first viewer and stop it when the last viewer leaves.
class LiveStream(private val daemon: DaemonClient) {

    private val viewers = CopyOnWriteArrayList<WebSocket>()
    private val packets = LivePackets(this)
    private val gate = Any()

    @Volatile
    private var epoch = 0

    val isWatched: Boolean
        get() = viewers.isNotEmpty()

    // Stream one mosaic; browsers crop angles without restarting the encoder.
    fun serve(socket: WebSocket, view: String) {
        val mine: Int
        synchronized(gate) {
            viewers.add(socket)
            epoch++
            mine = epoch
        }
        Logs.d(TAG, "live viewer joined for $view, ${viewers.size} watching")
        daemon.liveStart(view)
        packets.start()
        try {
            socket.awaitClose()
        } finally {
            viewers.remove(socket)
            Logs.d(TAG, "live viewer left, ${viewers.size} watching")
            Thread.sleep(LEAVE_MS)
            synchronized(gate) {
                if (viewers.isEmpty() && epoch == mine) {
                    packets.stop()
                    daemon.liveStop()
                }
            }
        }
    }

    fun send(packet: ByteArray, offset: Int, length: Int) {
        for (viewer in viewers) {
            if (viewer.isClosed) viewers.remove(viewer) else viewer.send(packet, offset, length)
        }
    }
}
