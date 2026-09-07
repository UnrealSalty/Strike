package com.strike.server

import com.strike.core.Logs
import com.strike.daemon.DaemonClient
import java.util.concurrent.CopyOnWriteArrayList

private const val TAG = "LiveStream"
private const val LEAVE_MS = 400L

/**
 * Every browser watching the live camera. The encoder in the daemon is paid
 * for in battery and heat, so it only runs while this is not empty.
 */
class LiveStream(private val daemon: DaemonClient) {

    private val viewers = CopyOnWriteArrayList<WebSocket>()
    private val packets = LivePackets(this)
    private val gate = Any()

    @Volatile
    private var epoch = 0

    val isWatched: Boolean
        get() = viewers.isNotEmpty()

    /**
     * Blocks the request thread for as long as the viewer stays connected.
     * The mosaic is one stream; the page crops an angle so a tap does not
     * tear the encoder down.
     */
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
