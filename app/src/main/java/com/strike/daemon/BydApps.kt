package com.strike.daemon

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

// Package and system setting changes require shell UID access.
class BydApps(context: Context, private val shell: Shell) {

    private val packages = context.packageManager
    private val resolver = context.contentResolver

    fun state(name: String): String = try {
        when (packages.getApplicationEnabledSetting(name)) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> DISABLED
            else -> ENABLED
        }
    } catch (e: IllegalArgumentException) {
        NOT_INSTALLED
    }

    fun setEnabled(name: String, enabled: Boolean): Boolean {
        val verb = if (enabled) "pm enable $name" else "pm disable-user --user 0 $name"
        return shell.run("$verb 2>&1") == 0
    }

    fun wakeWordState(): String = when (Settings.System.getString(resolver, WAKE_WORD)) {
        "true" -> ENABLED
        "false" -> DISABLED
        else -> NOT_INSTALLED
    }

    // The voice assistant applies this live; its speech engine idles while it is false.
    fun setWakeWord(enabled: Boolean): Boolean =
        shell.run("settings put system $WAKE_WORD $enabled 2>&1") == 0

    companion object {
        const val DASHCAM = "com.byd.cdr"
        const val TRAFFIC_MONITOR = "com.byd.trafficmonitor"
        const val NAVIGATION = "com.telenav.app.arp"
        private const val WAKE_WORD = "KEY_AUDIO_WAKEUP"

        const val ENABLED = "enabled"
        const val DISABLED = "disabled"
        const val NOT_INSTALLED = "notInstalled"
    }
}
