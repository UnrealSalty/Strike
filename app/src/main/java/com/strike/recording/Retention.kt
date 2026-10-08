package com.strike.recording

import java.io.File

open class Kept(val id: String, val bytes: Long)

// Both stores return finalized clips newest first.
interface Reapable {
    fun list(): List<Kept>

    /** Names and sizes only; no metadata parsing. */
    fun kept(): List<Kept>

    fun delete(id: String): Boolean
}

// The timestamp follows the first underscore in every clip name, so text order is time order.
internal fun keptIn(root: File, namePattern: Regex): List<Kept> {
    val files = root.listFiles() ?: return emptyList()
    val kept = ArrayList<Kept>(files.size)
    for (file in files) {
        if (!namePattern.matches(file.name)) continue
        kept.add(Kept(file.name, file.length()))
    }
    kept.sortByDescending { it.id.substringAfter('_') }
    return kept
}

/** [shortBytes] is how far the volume is below the space the next clip needs, whatever filled it. */
class Retention(private val store: Reapable, private val budgetBytes: Long, private val shortBytes: Long = 0) {

    fun enforce(inFlight: String?): Int {
        val clips = store.kept()
        var usedBytes = totalBytes(clips)
        var freedBytes = 0L
        var dropped = 0
        for (i in clips.indices.reversed()) {
            if (usedBytes <= budgetBytes && freedBytes >= shortBytes) break
            val clip = clips[i]
            if (clip.id == inFlight) continue
            if (!store.delete(clip.id)) continue
            usedBytes -= clip.bytes
            freedBytes += clip.bytes
            dropped++
        }
        return dropped
    }
}

/** A volume that reports no size at all is unreadable, not full; it frees nothing. */
fun shortBytes(dir: File, floorBytes: Long): Long {
    if (dir.totalSpace <= 0L) return 0L
    return maxOf(0L, floorBytes - dir.usableSpace)
}
