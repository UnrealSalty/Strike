package com.strike

internal class RevivalAlarm(
    private val readEnabled: () -> Boolean,
    private val writeEnabled: (Boolean) -> Unit,
    private val schedule: () -> Unit,
    private val cancel: () -> Unit
) {
    private var scheduled = false
    private var stateRevision = 0L

    @Synchronized
    fun setEnabled(enabled: Boolean) {
        stateRevision++
        if (readEnabled() != enabled) writeEnabled(enabled)
        if (enabled) arm() else {
            scheduled = false
            cancel()
        }
    }

    @Synchronized
    fun revision(): Long = stateRevision

    @Synchronized
    fun reconcile(enabled: Boolean, expectedRevision: Long): Boolean {
        if (stateRevision != expectedRevision) return false
        setEnabled(enabled)
        return true
    }

    @Synchronized
    fun restore() {
        if (readEnabled()) arm()
    }

    @Synchronized
    fun fired(): Boolean {
        scheduled = false
        if (!readEnabled()) return false
        arm()
        return true
    }

    private fun arm() {
        if (scheduled) return
        schedule()
        scheduled = true
    }
}
