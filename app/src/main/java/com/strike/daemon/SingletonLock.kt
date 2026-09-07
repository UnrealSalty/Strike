package com.strike.daemon

import android.os.Process
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileLock

private const val TAG = "Lock"

/**
 * Two daemons would fight over the camera, and the watchdog can race its own
 * restart. A SIGKILL leaves the file behind with a pid that no longer exists,
 * which is the stale case worth recovering from.
 */
class SingletonLock {

    private var file: RandomAccessFile? = null
    private var lock: FileLock? = null

    fun take(): Boolean {
        val path = File(CAM_LOCK_PATH)
        try {
            var handle = RandomAccessFile(path, "rw")
            var held = handle.channel.tryLock()
            if (held == null) {
                val owner = pidIn(handle)
                if (owner != null && owner != Process.myPid() && File("/proc/$owner").exists()) {
                    DaemonLog.w(TAG, "another daemon holds the camera, pid $owner")
                    handle.close()
                    return false
                }
                handle.close()
                path.delete()
                handle = RandomAccessFile(path, "rw")
                held = handle.channel.tryLock()
                if (held == null) {
                    DaemonLog.w(TAG, "cannot take the camera lock")
                    handle.close()
                    return false
                }
            }
            handle.setLength(0)
            handle.writeBytes(Process.myPid().toString())
            path.setReadable(true, false)
            file = handle
            lock = held
            return true
        } catch (e: IOException) {
            DaemonLog.e(TAG, "camera lock failed: ${e.message}")
            return false
        }
    }

    fun release() {
        try {
            lock?.release()
            file?.close()
        } catch (e: IOException) {
            DaemonLog.w(TAG, "camera lock release failed")
        }
        lock = null
        file = null
        File(CAM_LOCK_PATH).delete()
    }

    private fun pidIn(handle: RandomAccessFile): Int? = try {
        handle.seek(0)
        handle.readLine()?.trim()?.toIntOrNull()
    } catch (e: IOException) {
        null
    }
}
