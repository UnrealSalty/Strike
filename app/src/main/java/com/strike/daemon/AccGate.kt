package com.strike.daemon

private const val STALE_MS = 20_000L

/** Only a recent, confirmed parked state permits the deterrent. */
object AccGate {
    private var on: Boolean? = null
    private var saidAtMs = 0L

    @Synchronized
    fun say(accOn: Boolean?, observedAtMs: Long) {
        on = accOn
        saidAtMs = observedAtMs
    }

    val isUnsafe: Boolean
        @Synchronized get() = accUnsafe(on, saidAtMs, System.currentTimeMillis())
}

internal fun accUnsafe(accOn: Boolean?, saidAtMs: Long, now: Long): Boolean =
    accOn != false || now - saidAtMs >= STALE_MS
