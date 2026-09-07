package com.strike.recording

/** A file retention is allowed to drop: what it is called and what it costs. */
open class Kept(val id: String, val bytes: Long)

/** Somewhere clips are kept. [list] is newest first, as both stores return. */
interface Reapable {
    fun list(): List<Kept>
    fun delete(id: String): Boolean
}

/**
 * Frees space by dropping the oldest clips once the budget is exceeded. The
 * caller says how many went, because the two processes that call this log in
 * different places.
 */
class Retention(private val store: Reapable, private val budgetBytes: Long) {

    fun enforce(inFlight: String?): Int {
        val clips = store.list()
        var used = 0L
        for (clip in clips) {
            used += clip.bytes
        }
        if (used <= budgetBytes) return 0

        var dropped = 0
        for (i in clips.indices.reversed()) {
            if (used <= budgetBytes) break
            val clip = clips[i]
            if (clip.id == inFlight) continue
            if (!store.delete(clip.id)) continue
            used -= clip.bytes
            dropped++
        }
        return dropped
    }
}
