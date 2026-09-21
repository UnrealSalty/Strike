package com.strike.daemon

import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption

internal class ParkedLease(file: File, private val slot: Int) {
    init { require(slot in 1..3) }

    private val path = file.absoluteFile.normalize().toPath()
    private var shared: SharedChannel? = null
    private var claim: FileLock? = null

    val isHeld: Boolean get() = synchronized(channels) { claim?.isValid == true }

    val otherHeld: Boolean get() = synchronized(channels) {
        try {
            val opened = open()
            (1..3).any { it != slot && occupied(opened, it) }
        } finally { closeUnclaimed() }
    }

    fun heldBy(owner: Int): Boolean = synchronized(channels) {
        require(owner in 1..3)
        if (owner == slot) return isHeld
        try { occupied(open(), owner) } finally { closeUnclaimed() }
    }

    fun acquire(): Boolean = synchronized(channels) {
        if (isHeld) return true
        closeUnclaimed()
        val opened = open()
        try {
            val gate = lock(opened, 0) ?: return false
            gate.use {
                claim = lock(opened, slot.toLong())
                claim != null
            }
        } finally { closeUnclaimed() }
    }

    fun release(onLast: () -> Unit): Boolean = synchronized(channels) {
        val held = shared ?: return true
        try {
            val gate = try {
                held.channel.lock(0, 1, false)
            } catch (busy: OverlappingFileLockException) {
                return false
            }
            gate.use {
                claim?.let {
                    it.release()
                    claim = null
                }
                if ((1..3).none { it != slot && occupied(held.channel, it) }) onLast()
                true
            }
        } finally { closeUnclaimed() }
    }

    private fun closeUnclaimed() {
        if (isHeld) return
        claim = null
        val unused = shared ?: return
        shared = null
        unused.references--
        if (unused.references == 0) {
            channels.remove(path)
            unused.channel.close()
        }
    }

    private fun open(): FileChannel {
        val opened = shared ?: channels.getOrPut(path) {
            SharedChannel(FileChannel.open(path,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE))
        }.also { shared = it; it.references++ }
        return opened.channel
    }

    private fun occupied(opened: FileChannel, owner: Int): Boolean =
        lock(opened, owner.toLong())?.use { false } ?: true

    private fun lock(opened: FileChannel, byte: Long): FileLock? = try {
        opened.tryLock(byte, 1L, false)
    } catch (busy: OverlappingFileLockException) {
        null
    }

    private class SharedChannel(val channel: FileChannel, var references: Int = 0)

    companion object {
        // Closing another descriptor to this file can drop every POSIX lock held by this process.
        private val channels = HashMap<java.nio.file.Path, SharedChannel>()
    }
}
