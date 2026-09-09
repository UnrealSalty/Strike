package com.strike.online

import com.strike.vehicle.VehicleSnapshot

internal fun tunnelWaiting(mode: String, vehicle: VehicleSnapshot?, wasAllowed: Boolean = false): String? {
    if (mode == "always") return null
    if (vehicle?.accOn == true || (vehicle?.gear != null && vehicle.gear != "P")) {
        return "Standing by until the car switches off"
    }
    if (mode == "lock" && vehicle?.locked == false) return "Standing by until the doors lock"
    // A sleeping SDK must not end an already confirmed parked session.
    if (vehicle?.accOn != false && !wasAllowed) return "Waiting for ignition status"
    return when (mode) {
        "off" -> null
        "lock" -> when (vehicle?.locked) {
            true -> null
            false -> "Standing by until the doors lock"
            null -> if (wasAllowed) null else "Waiting for lock status"
        }
        else -> "Choose when the tunnel should run"
    }
}

internal class TunnelRetry {
    var failures = 0
        private set
    var atMs = 0L
        private set
    val exhausted: Boolean get() = failures >= 5

    fun failed(nowMs: Long, ranForMs: Long) {
        if (ranForMs >= 300_000L) failures = 0
        failures++
        atMs = nowMs + minOf(5_000L shl (failures - 1).coerceAtMost(4), 60_000L)
    }

    fun reset() {
        failures = 0
        atMs = 0L
    }
}
