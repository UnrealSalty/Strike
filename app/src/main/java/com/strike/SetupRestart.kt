package com.strike

internal class SetupRestart(
    private var needed: Boolean,
    private val restart: () -> Unit
) {
    private var authorised = false
    private var foreground = false
    private var asking = false
    private var attempted = false

    fun permissionsRequested() { asking = true }

    fun permissionsFinished(granted: Boolean) {
        asking = false
        needed = needed || granted
        checkReady()
    }

    fun shellAuthorised() {
        authorised = true
        checkReady()
    }

    fun foreground(visible: Boolean) {
        foreground = visible
        checkReady()
    }

    private fun checkReady() {
        if (!needed || !authorised || !foreground || asking || attempted) return
        attempted = true
        restart()
    }
}
