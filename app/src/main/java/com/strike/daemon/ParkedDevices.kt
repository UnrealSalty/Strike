package com.strike.daemon

private const val LOOKUP_RETRY_MS = 30_000L

internal class ParkedDevices(
    private val resolvePower: () -> Any?,
    private val resolveSpecial: () -> Any?,
    private val nowMs: () -> Long
) {
    var power: Any? = null
        private set
    var special: Any? = null
        private set
    private var retryAtMs: Long? = null

    @Synchronized
    fun refresh() {
        if (power != null && special != null) return
        val now = nowMs()
        if (retryAtMs?.let { now < it } == true) return
        retryAtMs = now + LOOKUP_RETRY_MS
        if (power == null) power = resolvePower()
        if (special == null) special = resolveSpecial()
    }
}
