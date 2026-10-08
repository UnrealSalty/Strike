package com.strike.daemon

import android.os.SystemClock
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

internal const val CAM_RECOVERY_PATH = "$STRIKE_DIR/cam.recovery"
internal const val CAM_POWER_RECOVERY_PATH = "$STRIKE_DIR/cam.parked-owner"
private const val RESUME_WITHIN_MS = 180_000L
// Android 10 may restore the 15-minute job up to 30 minutes after boot, plus startup time.
private const val BOOT_RESUME_WITHIN_MS = 33 * 60_000L
private const val CHECKPOINT_EVERY_MS = 60_000L

internal data class ParkedIntent(val startedAtMs: Long?, val validUntilMs: Long)

// Carries recent recording intent across restarts, never vehicle state.
internal class ParkedRecovery(
    private val file: File = File(CAM_RECOVERY_PATH),
    private val stopped: File = File(CAM_SENTINEL_PATH),
    private val bootId: String? = readBootId(),
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val wallNowMs: () -> Long = System::currentTimeMillis
) {
    private var checkpointAtMs: Long? = null
    private var checkpointMode: String? = null
    private var checkpointArm: String? = null
    private var checkpointSaved = true
    private var sessionStartedAtMs: Long? = null
    private var resumeAttempted = false
    private var rejection = "no checkpoint"

    @Synchronized
    fun checkpoint(mode: String?, arm: String?): Boolean {
        val now = nowMs()
        val previousAtMs = checkpointAtMs
        val parked = mode != null && arm != null && supported(mode, arm)
        if (mode == checkpointMode && arm == checkpointArm && previousAtMs != null) {
            if (!parked && checkpointSaved) return true
            if (now - previousAtMs in 0 until CHECKPOINT_EVERY_MS) return checkpointSaved
        }
        if (mode != checkpointMode || arm != checkpointArm || !parked) sessionStartedAtMs = null
        checkpointAtMs = now
        checkpointMode = mode
        checkpointArm = arm
        checkpointSaved = if (parked) {
            save(checkNotNull(mode), checkNotNull(arm))
        } else {
            val temporary = File(file.path + ".tmp")
            val cleared = !file.exists() || file.delete()
            val temporaryCleared = !temporary.exists() || temporary.delete()
            cleared && temporaryCleared
        }
        return checkpointSaved
    }

    @Synchronized
    fun save(mode: String, arm: String): Boolean {
        val boot = bootId?.takeIf { it.isNotEmpty() } ?: return false
        if (stopped.exists() || !supported(mode, arm)) return false
        val temporary = File(file.path + ".tmp")
        return try {
            DataOutputStream(temporary.outputStream()).use {
                val now = nowMs()
                it.writeInt(3)
                it.writeUTF(boot)
                it.writeLong(now)
                it.writeLong(wallNowMs())
                it.writeUTF(mode)
                it.writeUTF(arm)
                it.writeLong(sessionStartedAtMs ?: now)
                sessionStartedAtMs = sessionStartedAtMs ?: now
            }
            Files.move(temporary.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            if (stopped.exists()) {
                file.delete()
                false
            } else true
        } catch (e: IOException) {
            false
        } finally {
            temporary.delete()
        }
    }

    @Synchronized
    fun resume(enabled: Boolean, mode: String, arm: String, fallback: ParkedRecovery? = null): String? {
        if (resumeAttempted) return null
        resumeAttempted = true
        val saved = intent(enabled, mode, arm) ?: fallback?.intent(enabled, mode, arm)
        if (saved == null) {
            if (file.exists()) DaemonLog.w("Boot", "Parked restoration skipped: $rejection")
            file.delete()
            return null
        }
        val resumedAtMs = nowMs()
        sessionStartedAtMs = saved.startedAtMs ?: Long.MIN_VALUE
        if (!save(mode, arm)) {
            if (stopped.exists()) file.delete()
            return null
        }
        checkpointAtMs = resumedAtMs
        checkpointMode = mode
        checkpointArm = arm
        checkpointSaved = true
        return mode
    }

    @Synchronized
    fun validUntilMs(enabled: Boolean, mode: String, arm: String): Long? =
        intent(enabled, mode, arm)?.validUntilMs

    @Synchronized
    fun intent(enabled: Boolean, mode: String, arm: String): ParkedIntent? {
        return try {
            if (stopped.exists()) return reject("recorder was switched off")
            if (!enabled || !supported(mode, arm)) return reject("surveillance settings no longer match")
            if (file.length() > 256L) return reject("checkpoint is too large")
            val boot = bootId?.takeIf { it.isNotEmpty() } ?: return reject("boot identity unavailable")
            DataInputStream(ByteArrayInputStream(file.readBytes())).use {
                val version = it.readInt()
                if (version !in 1..3) return reject("unknown checkpoint format")
                val sameBoot = it.readUTF() == boot
                val recordedAtMs = it.readLong()
                val recordedWallMs = if (version >= 2) it.readLong() else null
                val now = nowMs()
                val ageMs = if (sameBoot) now - recordedAtMs
                    else wallNowMs() - (recordedWallMs ?: return reject("old checkpoint has no reboot timestamp"))
                val validUntilMs = if (sameBoot) {
                    if (ageMs !in 0..RESUME_WITHIN_MS) return reject("checkpoint ageMs=$ageMs sameBoot=true")
                    now + (RESUME_WITHIN_MS - ageMs)
                } else {
                    val ageAtBootMs = ageMs - now
                    if (now !in 0..BOOT_RESUME_WITHIN_MS || ageAtBootMs !in 0..RESUME_WITHIN_MS) {
                        return reject("checkpoint ageAtBootMs=$ageAtBootMs bootUptimeMs=$now")
                    }
                    BOOT_RESUME_WITHIN_MS
                }
                val recordedMode = it.readUTF()
                val recordedArm = it.readUTF()
                val started = if (version >= 3) it.readLong().takeUnless { value -> value == Long.MIN_VALUE } else null
                when {
                    started != null && started > recordedAtMs -> reject("session starts after its checkpoint")
                    recordedMode != mode || recordedArm != arm -> reject("surveillance settings changed")
                    stopped.exists() -> reject("recorder was switched off")
                    else -> ParkedIntent(started?.let { start ->
                        if (sameBoot) start else now - ageMs - (recordedAtMs - start)
                    }, validUntilMs)
                }
            }
        } catch (e: IOException) {
            reject("checkpoint unreadable: ${e.javaClass.simpleName}")
        }
    }

    private fun reject(reason: String): Nothing? {
        rejection = reason
        return null
    }

    private fun supported(mode: String, arm: String): Boolean =
        (mode == "smart" || mode == "continuous") && (arm == "off" || arm == "lock")
}

private fun readBootId(): String? = try {
    File("/proc/sys/kernel/random/boot_id").readText().trim().takeIf { it.isNotEmpty() }
} catch (e: IOException) {
    null
}
