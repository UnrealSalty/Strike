package com.strike.daemon

import com.strike.vehicle.VehicleSnapshot

private const val FRESH_MS = 20_000L
private const val OFF_CONFIRM_MS = 2_000L
private const val BOOT_HOLD_MS = 10_000L

/** Direct BYD readings survive the app sleeping; app signals cover unavailable SDK reads. */
class AccMonitor(
    private val read: () -> VehicleSnapshot?,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    private var generation = 0L
    private var directAtMs: Long? = null
    private var appAtMs = 0L
    private var app: VehicleSnapshot? = null
    private var accepted: VehicleSnapshot? = null
    private var acceptedAtMs = 0L
    private var offSinceMs: Long? = null
    private var onUntilMs = 0L
    private var parkedSinceMs: Long? = null

    @Synchronized
    fun snapshot(): VehicleSnapshot? =
        if (nowMs() - acceptedAtMs < FRESH_MS) accepted else null

    @Synchronized
    fun parkedForMs(): Long = parkedSinceMs?.let { nowMs() - it } ?: 0L

    @Synchronized
    fun observedAtMs(): Long = acceptedAtMs

    @Synchronized
    fun isConfirmingOff(): Boolean = offSinceMs != null && accepted?.accOn != false

    fun poll() {
        val before = synchronized(this) { generation }
        val found = read()
        synchronized(this) {
            if (before != generation) return
            val now = nowMs()
            if (found?.accOn != null) {
                directAtMs = now
                observe(found, now)
            } else {
                val fallback = if (now - appAtMs < FRESH_MS && appAtMs > (directAtMs ?: 0L)) app else null
                observe(fallback ?: found, if (fallback != null) appAtMs else now)
            }
        }
    }

    @Synchronized
    fun fromApp(snapshot: VehicleSnapshot?) {
        val now = nowMs()
        app = snapshot
        appAtMs = now
        if (directAtMs?.let { now - it < FRESH_MS } == true) return
        generation++
        observe(snapshot, now)
    }

    @Synchronized
    fun edge(on: Boolean) {
        val now = nowMs()
        if (!on && now < onUntilMs) return
        generation++
        onUntilMs = if (on) now + BOOT_HOLD_MS else 0L
        offSinceMs = null
        app = null
        accepted = VehicleSnapshot(null, null, null, null, null, null, on, null)
        acceptedAtMs = now
        parkedSinceMs = if (on) null else now
    }

    private fun observe(snapshot: VehicleSnapshot?, observedAtMs: Long) {
        val now = nowMs()
        val on = snapshot?.accOn
        if (on == false && accepted?.accOn != false) {
            if (offSinceMs == null) offSinceMs = observedAtMs
            // Re-reading one old app sample cannot confirm a transition.
            if (now < onUntilMs || observedAtMs - offSinceMs!! < OFF_CONFIRM_MS) return
        } else if (on != false) {
            offSinceMs = null
        }
        accepted = snapshot
        acceptedAtMs = observedAtMs
        if (on == true || (snapshot?.gear != null && snapshot.gear != "P")) {
            parkedSinceMs = null
        } else if (on == false && parkedSinceMs == null) {
            parkedSinceMs = now
        }
    }
}
