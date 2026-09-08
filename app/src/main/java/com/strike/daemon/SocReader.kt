package com.strike.daemon

private const val EVERY_MS = 60_000L
private const val REPEAT_MS = 30 * 60_000L

/** The statistic device costs more than the parked power read, so it gets its own cadence. */
class SocReader(
    private val read: () -> Int?,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    private var readAtMs: Long? = null
    private var reportedAtMs: Long? = null

    var percent: Int? = null
        private set

    /** True on a change, and on a heartbeat so a still reading proves the device still answers. */
    fun poll(): Boolean {
        val now = nowMs()
        val last = readAtMs
        if (last != null && now - last < EVERY_MS) return false
        readAtMs = now
        val soc = read()
        val changed = soc != percent
        val reported = reportedAtMs
        percent = soc
        if (!changed && reported != null && now - reported < REPEAT_MS) return false
        reportedAtMs = now
        return true
    }
}

internal fun socLine(percent: Int?, accOn: Boolean?): String {
    val level = if (percent == null) "battery percentage unavailable" else "battery $percent %"
    return when (accOn) {
        true -> "$level, car on"
        false -> "$level, car off"
        else -> "$level, power unknown"
    }
}
