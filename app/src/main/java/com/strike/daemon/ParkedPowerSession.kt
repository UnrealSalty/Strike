package com.strike.daemon

import com.strike.recording.sentryMode
import com.strike.vehicle.VehicleSnapshot

internal data class ParkedPowerSettings(val enabled: Boolean, val mode: String, val arm: String)

internal class ParkedPowerSession(
    private val settings: () -> ParkedPowerSettings,
    private val wanted: () -> Boolean,
    private val seed: (ParkedPowerSettings) -> ParkedIntent?,
    private val journal: ParkedRecovery,
    readVehicle: () -> VehicleSnapshot?,
    private val nowMs: () -> Long
) {
    private val ignition = AccMonitor(readVehicle, nowMs)
    private var watching = false
    private var previous: ParkedPowerSettings? = null
    private var settingsObservedAtMs = 0L
    private var restoreAfterMs: Long? = null
    private var polledAtMs: Long? = null

    fun accOn(): Boolean? = ignition.snapshot()?.accOn

    fun edge(on: Boolean) = synchronized(ignition) {
        ignition.edge(on)
        if (on) {
            watching = false
            restoreAfterMs = nowMs()
        }
    }

    fun refresh(): Long? {
        val current = settings()
        synchronized(ignition) {
            if (!wanted() || !current.enabled || current.mode !in listOf("smart", "continuous") ||
                current.arm !in listOf("off", "lock")) {
                watching = false
                restoreAfterMs = null
                previous = null
                journal.checkpoint(null, null)
                return null
            }
            if (previous != null && previous != current) {
                watching = false
                restoreAfterMs = maxOf(restoreAfterMs ?: Long.MIN_VALUE, settingsObservedAtMs)
            }
            previous = current
            settingsObservedAtMs = nowMs()
        }
        val intent = seed(current)
        val restore = synchronized(ignition) { !watching && restoreAfterMs == null && canRestore(intent) }
        if (!restore) {
            val now = nowMs()
            if (polledAtMs?.let { now - it >= 15_000L } != false) {
                polledAtMs = now
                ignition.poll()
            }
        }
        return synchronized(ignition) {
            if (!watching && canRestore(intent)) watching = true
            val next = sentryMode(current.enabled, current.mode, ignition.snapshot(), current.arm,
                ignition.parkedForMs(), watching)
            if (next != null) {
                watching = next == "smart" || next == "continuous"
                if (!watching) restoreAfterMs = maxOf(restoreAfterMs ?: Long.MIN_VALUE, ignition.observedAtMs())
            }
            // A Stop or settings change can arrive while a hardware getter is blocked.
            if (!wanted() || settings() != current) watching = false
            if (!watching) {
                journal.checkpoint(null, null)
                return null
            }
            if (!journal.checkpoint(current.mode, current.arm)) return null
            journal.validUntilMs(current.enabled, current.mode, current.arm)
        }
    }

    private fun canRestore(intent: ParkedIntent?): Boolean {
        if (intent == null || intent.validUntilMs < nowMs()) return false
        val after = restoreAfterMs ?: return true
        return intent.startedAtMs?.let { it > after } == true
    }
}
