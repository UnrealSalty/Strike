package com.strike.daemon

import android.content.Context
import android.content.pm.PackageManager

/**
 * The car's own apps. Reading their state is a normal API call; changing it is
 * `pm disable-user`, which only the shell can run.
 */
class BydApps(context: Context, private val shell: Shell) {

    private val packages = context.packageManager

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

    companion object {
        const val DASHCAM = "com.byd.cdr"
        const val TRAFFIC_MONITOR = "com.byd.trafficmonitor"

        const val ENABLED = "enabled"
        const val DISABLED = "disabled"
        const val NOT_INSTALLED = "notInstalled"
    }
}
