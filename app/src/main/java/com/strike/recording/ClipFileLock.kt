package com.strike.recording

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

private val clipFileLock = Any()

// Keep this inode: unlinking a lock file lets processes lock different files for the same directory.
internal fun <T> withClipFileLock(file: File, action: () -> T): T = synchronized(clipFileLock) {
    val lock = File(file.absoluteFile.parentFile, ".strike-clips.lock")
    if (lock.createNewFile()) {
        lock.setReadable(true, false)
        lock.setWritable(true, false)
    }
    FileChannel.open(lock.toPath(), StandardOpenOption.WRITE).use { channel ->
        channel.lock().use { action() }
    }
}
