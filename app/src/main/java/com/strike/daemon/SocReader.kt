package com.strike.daemon

import android.os.SystemClock

private const val EVERY_MS = 60_000L

class SocReader(
    private val read: () -> Int?,
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() }
) {
    private var readAtMs: Long? = null
    private var seeded = false

    var percent: Int? = null
        private set

    fun poll(): Boolean {
        val now = nowMs()
        val last = readAtMs
        if (last != null && now - last < EVERY_MS) return false
        readAtMs = now
        val soc = read()
        if (seeded && soc == percent) return false
        seeded = true
        percent = soc
        return true
    }
}

internal fun socLine(percent: Int?): String =
    if (percent == null) "battery percentage unavailable" else "battery $percent %"
