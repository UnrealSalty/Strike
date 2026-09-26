package com.strike.daemon

import com.strike.recording.sentryMode
import com.strike.vehicle.VehicleSnapshot

internal data class ParkedPowerSettings(val enabled: Boolean, val mode: String, val arm: String)

internal class ParkedPowerSession(
    private val settings: () -> ParkedPowerSettings,
    private val wanted: () -> Boolean,
    private val seed: (ParkedPowerSettings) -> Boolean,
    private val journal: ParkedRecovery,
    readVehicle: () -> VehicleSnapshot?,
    private val nowMs: () -> Long
) {
    private val ignition = AccMonitor(readVehicle, nowMs)
    @Volatile private var watching = false
    private var previous: ParkedPowerSettings? = null
    @Volatile private var mayRestore = true
    private var polledAtMs: Long? = null

    fun edge(on: Boolean) {
        ignition.edge(on)
        if (on) {
            watching = false
            mayRestore = false
        }
    }

    fun refresh(): Long? {
        val current = settings()
        if (!wanted() || !current.enabled || current.mode !in listOf("smart", "continuous") ||
            current.arm !in listOf("off", "lock")) {
            watching = false
            mayRestore = true
            previous = null
            journal.checkpoint(null, null)
            return null
        }
        if (previous != null && previous != current) {
            watching = false
            mayRestore = false
        }
        previous = current
        if (mayRestore && seed(current)) {
            mayRestore = false
            watching = true
        } else {
            val now = nowMs()
            if (polledAtMs?.let { now - it >= 15_000L } != false) {
                polledAtMs = now
                ignition.poll()
            }
        }
        val next = sentryMode(current.enabled, current.mode, ignition.snapshot(), current.arm,
            ignition.parkedForMs(), watching)
        if (next != null) {
            watching = next == "smart" || next == "continuous"
            mayRestore = false
        }
        // A Stop or settings change can arrive while a hardware getter is blocked.
        if (!wanted() || settings() != current) watching = false
        if (!watching) {
            journal.checkpoint(null, null)
            return null
        }
        if (!journal.checkpoint(current.mode, current.arm)) return null
        return journal.validUntilMs(current.enabled, current.mode, current.arm)
    }
}
