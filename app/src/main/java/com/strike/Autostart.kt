package com.strike

import java.io.File

// BYD blocks autostart again on every install, and a blocked app is not bound after a parked reboot.
class Autostart(private val confirmed: File, private val installedAtMs: () -> Long) {

    @Synchronized
    fun needsCheck(): Boolean =
        !confirmed.isFile || confirmed.readText().toLongOrNull() != installedAtMs()

    @Synchronized
    fun confirm() {
        confirmed.writeText(installedAtMs().toString())
    }
}
