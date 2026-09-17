package com.strike.recording

import com.strike.daemon.DaemonLog
import java.io.File
import java.io.IOException

internal fun <T> openClipOutput(
    dir: File,
    name: String,
    mayUseInternal: Boolean,
    internal: File = File("/storage/emulated/0/Strike/clips"),
    recover: (File) -> Boolean = { storageUuid(it.path) != null && remountUntil(it) },
    open: (File) -> T
): Pair<File, T> {
    fun attempt(dest: File): Pair<File, T> {
        if (!ensureDir(dest)) throw IOException("cannot create $dest")
        val file = File(dest, name)
        return file to open(file)
    }

    try {
        val missing = !dir.exists()
        if (missing) recover(dir)
        return try {
            attempt(dir)
        } catch (e: IOException) {
            if (missing || !recover(dir)) throw e
            attempt(dir)
        }
    } catch (e: IOException) {
        if (!mayUseInternal || dir == internal) throw e
        val opened = attempt(internal)
        DaemonLog.w("Clip", "cannot write $dir, writing to $internal")
        return opened
    }
}
