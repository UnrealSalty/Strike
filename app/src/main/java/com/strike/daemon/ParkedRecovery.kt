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
private const val CHECKPOINT_EVERY_MS = 60_000L

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
                it.writeInt(2)
                it.writeUTF(boot)
                it.writeLong(nowMs())
                it.writeLong(wallNowMs())
                it.writeUTF(mode)
                it.writeUTF(arm)
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
    fun resume(enabled: Boolean, mode: String, arm: String): String? {
        if (resumeAttempted) return null
        resumeAttempted = true
        if (validUntilMs(enabled, mode, arm) == null) {
            if (file.exists()) DaemonLog.w("Boot", "Parked restoration skipped: $rejection")
            file.delete()
            return null
        }
        val resumedAtMs = nowMs()
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
    fun validUntilMs(enabled: Boolean, mode: String, arm: String): Long? {
        return try {
            if (stopped.exists()) return reject("recorder was switched off")
            if (!enabled || !supported(mode, arm)) return reject("surveillance settings no longer match")
            if (file.length() > 256L) return reject("checkpoint is too large")
            val boot = bootId?.takeIf { it.isNotEmpty() } ?: return reject("boot identity unavailable")
            DataInputStream(ByteArrayInputStream(file.readBytes())).use {
                val version = it.readInt()
                if (version != 1 && version != 2) return reject("unknown checkpoint format")
                val sameBoot = it.readUTF() == boot
                val recordedAtMs = it.readLong()
                val recordedWallMs = if (version == 2) it.readLong() else null
                val now = nowMs()
                val ageMs = if (sameBoot) now - recordedAtMs
                    else wallNowMs() - (recordedWallMs ?: return reject("old checkpoint has no reboot timestamp"))
                val recordedMode = it.readUTF()
                val recordedArm = it.readUTF()
                when {
                    ageMs !in 0..RESUME_WITHIN_MS -> reject("checkpoint ageMs=$ageMs sameBoot=$sameBoot")
                    recordedMode != mode || recordedArm != arm -> reject("surveillance settings changed")
                    stopped.exists() -> reject("recorder was switched off")
                    else -> now + (RESUME_WITHIN_MS - ageMs)
                }
            }
        } catch (e: IOException) {
            reject("checkpoint unreadable: ${e.javaClass.simpleName}")
        }
    }

    private fun reject(reason: String): Long? {
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
