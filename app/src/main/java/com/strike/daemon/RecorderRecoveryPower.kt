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
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() }
) {
    private var heldUntilMs: Long? = null
    private var closed = false

    @Synchronized
    fun refresh(): Boolean {
        if (closed) return true
        val deadline = validUntilMs()
        val remainingMs = deadline?.minus(nowMs()) ?: 0L
        return try {
            if (remainingMs <= 0L) {
                releaseHeld()
            } else if (deadline != heldUntilMs) {
                acquire(remainingMs)
                heldUntilMs = deadline
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
                acquire = lock::acquire,
                release = { if (lock.isHeld) lock.release() }
            )
        }
    }
}
