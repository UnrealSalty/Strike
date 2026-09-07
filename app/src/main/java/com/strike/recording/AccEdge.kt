package com.strike.recording

import com.strike.core.Logs
import com.strike.daemon.DaemonClient

private const val TAG = "Acc"

// A slow ACC-off query must not overtake a newer ACC-on broadcast.
object AccEdge {

    private val lock = Any()
    private var generation = 0

    fun driving() {
        Logs.d(TAG, "the car switched on, hiding the deterrent")
        synchronized(lock) {
            generation++
            DaemonClient().acc(true)
        }
    }

    fun parked(accOn: () -> Boolean?) {
        val started = synchronized(lock) { generation }
        val on = accOn()
        synchronized(lock) {
            if (!shouldAcceptParked(on, started, generation)) {
                Logs.d(TAG, "ACC-OFF ignored, the car is on or came back on")
                return
            }
            DaemonClient().acc(false)
        }
    }
}

internal fun shouldAcceptParked(
    accOn: Boolean?,
    startedGeneration: Int,
    currentGeneration: Int
): Boolean {
    if (accOn == true) return false
    return startedGeneration == currentGeneration
}
