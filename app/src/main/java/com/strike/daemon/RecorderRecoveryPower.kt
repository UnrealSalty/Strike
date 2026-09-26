package com.strike.daemon

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import com.strike.core.Config
import com.strike.surveillance.SurveillanceSettings
import com.strike.vehicle.VehicleTelemetry
import java.io.File

internal class RecorderRecoveryPower(
    private val validUntilMs: () -> Long?,
    private val acquire: (Long) -> Unit,
    private val release: () -> Unit,
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val maintain: () -> Unit = {},
    private val onAcc: (Boolean) -> Unit = {}
) {
    private var heldUntilMs: Long? = null
    @Volatile private var acquired = false
    @Volatile private var closed = false

    val isHeld: Boolean get() = acquired

    fun cancel() { closed = true }

    fun acc(on: Boolean) = onAcc(on)

    @Synchronized
    fun refresh(): Boolean {
        if (closed) return true
        val deadline = validUntilMs()
        val remainingMs = if (closed) 0L else deadline?.minus(nowMs()) ?: 0L
        return try {
            if (remainingMs <= 0L) {
                releaseHeld()
            } else {
                if (!acquired || deadline != heldUntilMs) {
                    heldUntilMs = deadline
                    try {
                        acquire(remainingMs)
                        acquired = true
                    } catch (e: RuntimeException) {
                        acquired = false
                        releaseHeld()
                        throw e
                    }
                }
                val current = if (closed) null else validUntilMs()
                if (closed || current == null || current <= nowMs()) releaseHeld() else maintain()
            }
            true
        } catch (e: RuntimeException) {
            false
        }
    }

    @Synchronized
    fun close(): Boolean {
        closed = true
        return try {
            releaseHeld()
            true
        } catch (e: RuntimeException) {
            false
        }
    }

    private fun releaseHeld() {
        if (heldUntilMs == null) return
        release()
        acquired = false
        heldUntilMs = null
    }

    companion object {
        fun create(context: Context): RecorderRecoveryPower {
            val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "strike:recorder-recovery")
            lock.setReferenceCounted(false)
            val recovery = ParkedRecovery()
            val owner = ParkedRecovery(File(CAM_POWER_RECOVERY_PATH))
            val vehicle = VehicleTelemetry(context)
            val session = ParkedPowerSession(
                settings = {
                    ParkedPowerSettings(
                        Config.getBool(SurveillanceSettings.ENABLED, false),
                        Config.getString(SurveillanceSettings.MODE, SurveillanceSettings.fallback(SurveillanceSettings.MODE)),
                        Config.getString(SurveillanceSettings.ARM, SurveillanceSettings.fallback(SurveillanceSettings.ARM)))
                },
                wanted = { File(CAM_SCRIPT_PATH).isFile && !File(CAM_SENTINEL_PATH).exists() },
                seed = { settings ->
                    recovery.validUntilMs(settings.enabled, settings.mode, settings.arm) != null ||
                        owner.validUntilMs(settings.enabled, settings.mode, settings.arm) != null
                },
                journal = owner,
                readVehicle = vehicle::parkingSnapshot,
                nowMs = SystemClock::elapsedRealtime
            )
            return RecorderRecoveryPower(
                validUntilMs = session::refresh,
                acquire = { remainingMs ->
                    lock.acquire(remainingMs)
                    check(ParkedRails.holdRecovery()) { "Could not claim parked power for recorder recovery" }
                },
                release = {
                    try {
                        check(ParkedRails.releaseRecovery()) { "Could not release recorder recovery power" }
                    } finally {
                        if (lock.isHeld) lock.release()
                    }
                },
                maintain = ParkedRails::tickRecovery,
                onAcc = session::edge
            )
        }
    }
}
