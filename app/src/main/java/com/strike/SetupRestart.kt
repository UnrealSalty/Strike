package com.strike

import com.strike.core.Logs
import java.io.File
import java.io.IOException

private const val FAST_POLLS = 20
private const val FAST_POLL_MS = 3_000L
private const val SLOW_POLL_MS = 30_000L

internal class SetupRestart(
    private val pending: File,
    firstRun: Boolean,
    /** True when the shell is already connected. */
    private val retryShell: () -> Boolean,
    private val restart: () -> Unit
) {
    private val needed = firstRun || pending.isFile
    private var authorised = false
    private var foreground = false
    private var asking = false
    private var attempted = false
    private var polls = 0
    private var wasForeground = false
    private var lastWaiting: String? = null

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

    /** Prompts can close without any callback, so live state is re-checked until the restart runs. */
    fun poll(): Long? {
        if (!needed || attempted) return null
        polls++
        if (!authorised && retryShell()) authorised = true
        // Strike resumed across a whole poll means no permission dialog is over it.
        if (foreground && wasForeground) asking = false
        wasForeground = foreground
        checkReady()
        return if (polls < FAST_POLLS) FAST_POLL_MS else SLOW_POLL_MS
    }

    fun foreground(visible: Boolean) {
        val returned = visible && !foreground
        foreground = visible
        if (returned && needed && !authorised && !attempted && retryShell()) authorised = true
        checkReady()
    }

    private fun checkReady() {
        if (!needed || attempted) return
        if (!authorised || !foreground || asking) {
            val waiting = "setup restart waiting approved=$authorised inFront=$foreground permissionsOpen=$asking"
            if (waiting != lastWaiting) BootDiagnostics.record(waiting)
            lastWaiting = waiting
            return
        }
        attempted = true
        if (pending.exists() && !pending.delete()) {
            Logs.w("Shell", "Could not finish setup. Close and reopen Strike")
            return
        }
        restart()
    }
}
