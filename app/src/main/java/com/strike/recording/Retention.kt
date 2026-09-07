package com.strike.recording

open class Kept(val id: String, val bytes: Long)

// Both stores return finalized clips newest first.
interface Reapable {
    fun list(): List<Kept>
    fun delete(id: String): Boolean
}

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
