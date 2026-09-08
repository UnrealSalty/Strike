package com.strike

import com.strike.core.Logs
import java.io.File
import java.io.IOException

internal class SetupRestart(
    private val pending: File,
    firstRun: Boolean,
    private val retryShell: () -> Unit,
    private val restart: () -> Unit
) {
    private val needed = firstRun || pending.isFile
    private var authorised = false
    private var foreground = false
    private var asking = false
    private var attempted = false

    init {
        if (firstRun) {
            try {
                pending.writeText("")
            } catch (e: IOException) {
                Logs.w("Shell", "Could not save the pending setup restart")
            }
        }
    }

    fun permissionsRequested() { asking = true }

    fun permissionsFinished() {
        asking = false
        checkReady()
    }

    fun shellAuthorised() {
        authorised = true
        checkReady()
    }

    fun foreground(visible: Boolean) {
        val returned = visible && !foreground
        foreground = visible
        if (returned && needed && !authorised && !attempted) retryShell()
        checkReady()
    }

    private fun checkReady() {
        if (!needed || !authorised || !foreground || asking || attempted) return
        attempted = true
        if (pending.exists() && !pending.delete()) {
            Logs.w("Shell", "Could not finish setup. Close and reopen Strike")
            return
        }
        restart()
    }
}
