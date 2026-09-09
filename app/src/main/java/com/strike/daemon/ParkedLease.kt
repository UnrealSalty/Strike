package com.strike.daemon

import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption

internal class ParkedLease(private val file: File, private val slot: Int) {
    init { require(slot in 1..2) }

    private var channel: FileChannel? = null
    private var claim: FileLock? = null

    val isHeld: Boolean get() = synchronized(this) { claim?.isValid == true }

    val otherHeld: Boolean get() = synchronized(this) {
        try {
            val other = lock(open(), (3 - slot).toLong()) ?: return@synchronized true
            other.use { false }
        } finally { closeUnclaimed() }
    }

    @Synchronized
    fun acquire(): Boolean {
        if (isHeld) return true
        closeUnclaimed()
        val opened = open()
        return try {
            val gate = lock(opened, 0) ?: return false
            gate.use {
                claim = lock(opened, slot.toLong())
                claim != null
            }
        } finally { closeUnclaimed() }
    }

    @Synchronized
    fun release(onLast: () -> Unit): Boolean {
        val opened = channel ?: return true
        return try {
            val gate = try {
                opened.lock(0, 1, false)
            } catch (busy: OverlappingFileLockException) {
                return false
            }
            gate.use {
                claim?.release()
                claim = null
                val other = lock(opened, (3 - slot).toLong())
                other?.use { onLast() }
                true
            }
        } finally { closeUnclaimed() }
    }

    private fun closeUnclaimed() {
        if (isHeld) return
        claim = null
        val unused = channel
        channel = null
        unused?.close()
    }

    private fun open(): FileChannel = channel ?: FileChannel.open(file.toPath(),
        StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)
        .also { channel = it }

    // Separate claims outlive hardware work; the gate prevents a new claim overtaking the last release.
    private fun lock(opened: FileChannel, byte: Long): FileLock? = try {
        opened.tryLock(byte, 1L, false)
    } catch (busy: OverlappingFileLockException) {
        null
    }
}
