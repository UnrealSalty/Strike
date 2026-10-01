package com.strike.core

class DiLink5VoltageGuard(
    initialBlocked: Boolean = false,
    private val nowMs: () -> Long,
) {
    enum class State { READY, WAITING, LOW }

    var blocked: Boolean = initialBlocked
        private set

    private var hasSafeSample = false
    private var sampledAtMs: Long? = null
    private var lowSamples = 0

    fun sample(volts: Double?): State {
        if (blocked) return State.LOW
        val now = nowMs()
        if (volts != null && volts.isFinite() && volts in 6.0..17.0) {
            sampledAtMs = now
            if (volts <= 11.8) {
                lowSamples++
                if (lowSamples >= 3) blocked = true
            } else {
                lowSamples = 0
                hasSafeSample = true
            }
        }
        if (blocked) return State.LOW
        val sampledAt = sampledAtMs ?: return State.WAITING
        return if (hasSafeSample && now - sampledAt in 0L until 120_000L) {
            State.READY
        } else {
            State.WAITING
        }
    }

    fun reset() {
        blocked = false
        hasSafeSample = false
        sampledAtMs = null
        lowSamples = 0
    }
}
