package com.strike.recording

import android.media.MediaCodec

// Encoded samples held back so an event clip can open with the seconds before the trigger.
// The oldest sample kept is always a keyframe; a clip that starts mid-GOP cannot decode its head.
class PreRoll(private val spanBudgetMs: Long, private val budgetBytes: Long) {

    private val held = ArrayDeque<Sample>()
    private var bytes = 0L

    val spanMs: Long
        get() {
            val first = held.firstOrNull() ?: return 0L
            return (held.last().timeUs - first.timeUs) / 1000L
        }

    val count: Int get() = held.size

    fun add(sample: Sample) {
        held.addLast(sample)
        bytes += sample.bytes.size
        while (bytes > budgetBytes || spanMs > spanBudgetMs) {
            if (!dropOldestGop()) return
        }
    }

    fun snapshot(): List<Sample> = ArrayList(held)

    fun clear() {
        held.clear()
        bytes = 0L
    }

    private fun dropOldestGop(): Boolean {
        if (held.isEmpty()) return false
        drop()
        while (held.isNotEmpty() && !keyFrame(held.first())) drop()
        return held.isNotEmpty()
    }

    private fun drop() {
        bytes -= held.removeFirst().bytes.size
    }
}

internal fun keyFrame(sample: Sample): Boolean =
    sample.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
