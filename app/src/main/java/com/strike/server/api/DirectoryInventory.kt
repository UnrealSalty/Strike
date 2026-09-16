package com.strike.server.api

import java.io.File
import java.util.concurrent.Executor

private const val INVENTORY_TTL_MS = 30_000L
private const val INVENTORY_ROOTS = 8

internal class DirectorySnapshot<T : Any>(val value: T?, val pending: Boolean)

internal class DirectoryInventory<T : Any>(
    private val read: (File) -> T,
    private val executor: Executor,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    private class Entry<T : Any>(val root: File, var revision: Long, var stamp: Long) {
        var value: T? = null
        var readAtMs: Long? = null
        var generation = 0L
        var pending = false
    }

    private val lock = Any()
    private val held = LinkedHashMap<String, Entry<T>>()

    fun snapshot(root: File, revision: Long): DirectorySnapshot<T> {
        val directory = root.absoluteFile.normalize()
        val stamp = directory.lastModified()
        val now = nowMs()
        var queued: Entry<T>? = null
        val snapshot = synchronized(lock) {
            var entry = held.remove(directory.path)
            if (entry == null) {
                if (held.size >= INVENTORY_ROOTS) {
                    val idle = held.entries.firstOrNull { !it.value.pending }
                        ?: return DirectorySnapshot(null, true)
                    held.remove(idle.key)
                }
                entry = Entry(directory, revision, stamp)
            }
            held[directory.path] = entry
            if (entry.revision != revision) {
                entry.revision = revision
                entry.value = null
                entry.readAtMs = null
                entry.generation++
            }
            if (entry.stamp != stamp) {
                entry.stamp = stamp
                entry.readAtMs = null
                entry.generation++
            }
            val readAt = entry.readAtMs
            val expired = readAt == null || now - readAt >= INVENTORY_TTL_MS
            if (!entry.pending && expired) {
                entry.pending = true
                queued = entry
            }
            DirectorySnapshot(entry.value, entry.pending)
        }
        queued?.let { submit(it) }
        return snapshot
    }

    fun forget() = synchronized(lock) {
        for (entry in held.values) {
            entry.value = null
            entry.readAtMs = null
            entry.generation++
        }
    }

    private fun submit(entry: Entry<T>) {
        try {
            executor.execute { refresh(entry) }
        } catch (e: RuntimeException) {
            synchronized(lock) { entry.pending = false }
            throw e
        }
    }

    private fun refresh(entry: Entry<T>) {
        try {
            val before = entry.root.lastModified()
            val generation = synchronized(lock) {
                if (entry.stamp != before) {
                    entry.stamp = before
                    entry.readAtMs = null
                    entry.generation++
                }
                entry.generation
            }
            val value = read(entry.root)
            val after = entry.root.lastModified()
            val completedAt = nowMs()
            synchronized(lock) {
                if (entry.generation == generation && before == after && entry.stamp == after) {
                    entry.value = value
                    entry.readAtMs = completedAt
                }
            }
        } finally {
            synchronized(lock) { entry.pending = false }
        }
    }
}
