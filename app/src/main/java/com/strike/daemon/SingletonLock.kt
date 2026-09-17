package com.strike.daemon

import android.os.Process
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

private const val TAG = "Lock"

// The kernel lock owns the camera; PID contents only identify its process.
class SingletonLock(private val path: File = File(CAM_LOCK_PATH)) {

    private var file: RandomAccessFile? = null
    private var lock: FileLock? = null

    @Synchronized
    fun take(): Boolean {
        if (lock?.isValid == true) return true
        var handle: RandomAccessFile? = null
        var acquired = false
        try {
            handle = RandomAccessFile(path, "rw")
            val held = try {
                handle.channel.tryLock()
            } catch (e: OverlappingFileLockException) {
                null
            }
            if (held == null) {
                DaemonLog.w(TAG, "another daemon holds the camera")
                return false
            }
            handle.setLength(0)
            handle.writeBytes(Process.myPid().toString())
            path.setReadable(true, false)
            file = handle
            lock = held
            acquired = true
            return true
        } catch (e: IOException) {
            DaemonLog.e(TAG, "camera lock failed: " + e.message)
            return false
        } finally {
            if (!acquired) close(handle)
        }
    }

    @Synchronized
    fun release() {
        val handle = file
        lock = null
        file = null
        close(handle)
    }

    private fun close(handle: RandomAccessFile?) {
        try {
            handle?.close()
        } catch (e: IOException) {
            DaemonLog.w(TAG, "camera lock release failed")
        }
    }
}
