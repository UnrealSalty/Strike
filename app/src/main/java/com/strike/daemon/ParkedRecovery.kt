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
private const val RESUME_WITHIN_MS = 180_000L
private const val CHECKPOINT_EVERY_MS = 60_000L

// Carries recording intent across a daemon restart, never vehicle state.
internal class ParkedRecovery(
    private val file: File = File(CAM_RECOVERY_PATH),
    private val stopped: File = File(CAM_SENTINEL_PATH),
    private val bootId: String? = readBootId(),
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() }
) {
    private var checkpointAtMs: Long? = null
    private var checkpointMode: String? = null
    private var checkpointArm: String? = null
    private var checkpointSaved = true

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
                it.writeInt(1)
                it.writeUTF(boot)
                it.writeLong(nowMs())
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
    fun consume(enabled: Boolean, mode: String, arm: String): String? {
        return try {
            if (file.length() > 256L) {
                file.delete()
                return null
            }
            val bytes = file.readBytes()
            if (!file.delete() || stopped.exists() || !enabled || !supported(mode, arm)) return null
            val boot = bootId?.takeIf { it.isNotEmpty() } ?: return null
            DataInputStream(ByteArrayInputStream(bytes)).use {
                if (it.readInt() != 1 || it.readUTF() != boot) return null
                val ageMs = nowMs() - it.readLong()
                val recordedMode = it.readUTF()
                val recordedArm = it.readUTF()
                if (ageMs !in 0..RESUME_WITHIN_MS || recordedMode != mode || recordedArm != arm) null
                else recordedMode
            }
        } catch (e: IOException) {
            file.delete()
            null
        }
    }

    private fun supported(mode: String, arm: String): Boolean =
        (mode == "smart" || mode == "continuous") && (arm == "off" || arm == "lock")
}

private fun readBootId(): String? = try {
    File("/proc/sys/kernel/random/boot_id").readText().trim().takeIf { it.isNotEmpty() }
} catch (e: IOException) {
    null
}
