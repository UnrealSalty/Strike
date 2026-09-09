package com.strike.daemon

import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption

internal class PanelLease(private val file: File, private val camera: Boolean) {
    private var channel: FileChannel? = null
    private var reservation: FileLock? = null
    private var owner: FileLock? = null

    val isHeld: Boolean get() = synchronized(this) { owner?.isValid == true }

    val cameraWaiting: Boolean get() = synchronized(this) {
        if (reservation?.isValid == true) return@synchronized true
        try {
            val probe = lock(open(), 1) ?: return@synchronized true
            probe.use { false }
        } finally { closeUnused() }
    }

    @Synchronized
    fun acquire(): Boolean {
        if (isHeld) return true
        return try {
            val opened = open()
            val gate = lock(opened, 0) ?: return false
            gate.use {
                if (camera) {
                    if (reservation?.isValid != true) {
                        reservation = lock(opened, 1) ?: return false
                    }
                    owner = lock(opened, 2)
                } else {
                    val probe = lock(opened, 1) ?: return false
                    probe.use { owner = lock(opened, 2) }
                }
                isHeld
            }
        } finally { closeUnused() }
    }

    // The owner releases its vendor Off token before allowing the next process to take the panel.
    @Synchronized
    fun release() {
        try {
            owner?.release()
        } finally {
            if (owner?.isValid != true) owner = null
            closeUnused()
        }
    }

    @Synchronized
    fun close() {
        val opened = channel
        channel = null
        owner = null
        reservation = null
        opened?.close()
    }

    private fun open(): FileChannel = channel ?: FileChannel.open(file.toPath(),
        StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)
        .also { channel = it }

    private fun closeUnused() {
        if (isHeld || reservation?.isValid == true) return
        close()
    }

    private fun lock(opened: FileChannel, byte: Long): FileLock? = try {
        opened.tryLock(byte, 1L, false)
    } catch (busy: OverlappingFileLockException) {
        null
    }
}
