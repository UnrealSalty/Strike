package com.strike.camera

import com.strike.daemon.DaemonLog
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

const val CAMERA_FIRST_FRAME_MS = 25_000L

class CameraStartup(
    initial: CameraChoice,
    private val automatic: Boolean,
    private val savedRaw: File,
    private val firmware: String
) {
    @Volatile
    var choice = if (automatic && remembered()) RAW_STRIP else initial
        private set

    private var receivedFrames = false
    private var switched = false

    fun observe(frameCount: Long, waitingMs: Long): Boolean {
        if (frameCount > 0L) {
            if (!receivedFrames) {
                receivedFrames = true
                if (switched) remember()
            }
            return false
        }
        if (!automatic || receivedFrames || choice.id == RAW_STRIP.id ||
            waitingMs < CAMERA_FIRST_FRAME_MS
        ) return false

        // Overdrive's PanoCameraFallbackOrder tries raw camera 0 after the panoramic hint.
        choice = RAW_STRIP
        switched = true
        return true
    }

    private fun remembered(): Boolean = try {
        firmware.isNotEmpty() && savedRaw.isFile && savedRaw.readText() == firmware
    } catch (e: IOException) {
        DaemonLog.w("Camera", "could not read the saved camera choice")
        false
    }

    private fun remember() {
        if (firmware.isEmpty()) return
        try {
            val pending = File(savedRaw.path + ".tmp")
            pending.writeText(firmware)
            Files.move(pending.toPath(), savedRaw.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            DaemonLog.d("Camera", "raw camera 0 delivered frames; saved for automatic startup")
        } catch (e: IOException) {
            DaemonLog.w("Camera", "camera is working, but its choice could not be saved")
        }
    }
}
