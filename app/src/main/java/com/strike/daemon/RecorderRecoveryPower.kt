package com.strike.daemon

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import com.strike.core.Config
import com.strike.surveillance.SurveillanceSettings

internal class RecorderRecoveryPower(
    private val validUntilMs: () -> Long?,
    private val acquire: (Long) -> Unit,
    private val release: () -> Unit,
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val maintain: () -> Unit = {}
) {
    private var heldUntilMs: Long? = null
    private var acquired = false
    private var closed = false

    val isHeld: Boolean get() = synchronized(this) { acquired }

    @Synchronized
    fun refresh(): Boolean {
        if (closed) return true
        val deadline = validUntilMs()
        val remainingMs = deadline?.minus(nowMs()) ?: 0L
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
                val current = validUntilMs()
                if (current == null || current <= nowMs()) releaseHeld() else maintain()
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
            return RecorderRecoveryPower(
                validUntilMs = {
                    recovery.validUntilMs(
                        Config.getBool(SurveillanceSettings.ENABLED, false),
                        Config.getString(SurveillanceSettings.MODE, SurveillanceSettings.fallback(SurveillanceSettings.MODE)),
                        Config.getString(SurveillanceSettings.ARM, SurveillanceSettings.fallback(SurveillanceSettings.ARM))
                    )
                },
                acquire = { remainingMs ->
                    lock.acquire(remainingMs)
                    check(ParkedRails.holdRecovery()) { "Could not claim parked power for recorder recovery" }
                },
                release = {
                    check(ParkedRails.releaseRecovery()) { "Could not release recorder recovery power" }
                    if (lock.isHeld) lock.release()
                },
                maintain = ParkedRails::tickRecovery
            )
        }
    }
}
