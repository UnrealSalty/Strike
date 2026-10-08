package com.strike.daemon

import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.SystemClock

// BYD's OnWithLock takes a keep-on hold and lights the backlight; OffWithLock only drops
// that hold. Only the plain TurnBacklightOff darkens the panel.
class ParkedPanel(
    private val tag: String = "RedScreen",
    private val powerManager: () -> Any? = ::panelPowerManager
) {
    private var power: Any? = null
    private var keepOnHeld = false
    private var isDark = false
    private val token = Binder()

    @Synchronized
    fun wake() {
        isDark = false
        val manager = powerManager()
        if (manager != null) {
            turnBacklightOn(manager)
            if (!keepOnHeld) keepOnHeld = withLock(manager, "TurnBacklightOnWithLock")
            val status = screenStatus(manager)
            if (status == 0) DaemonLog.w(tag, "panel still dark after waking it, status=$status")
        } else {
            powerService()?.let { turnBacklightOn(it) }
        }
    }

    @Synchronized
    fun darken() {
        val manager = powerManager() ?: return
        if (!turnBacklightOff(manager)) return
        keepOnHeld = false
        isDark = true
    }

    /** Hands the panel back. Only lights a panel Strike darkened when the car is in use. */
    @Synchronized
    fun release(carInUse: Boolean): Boolean {
        if (!keepOnHeld && !(isDark && carInUse)) return true
        val manager = powerManager() ?: return false
        if (isDark && carInUse) {
            if (!turnBacklightOn(manager)) return false
            isDark = false
        }
        if (keepOnHeld) {
            if (!withLock(manager, "TurnBacklightOffWithLock")) return false
            keepOnHeld = false
        }
        return true
    }

    private fun powerService(): Any? {
        power?.let { return it }
        return try {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, "power") as? IBinder ?: return null
            Class.forName("android.os.IPowerManager\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
                .also { power = it }
        } catch (e: ReflectiveOperationException) {
            DaemonLog.e(tag, "this firmware has no power service: ${e.message}")
            null
        }
    }

    private fun screenStatus(power: Any): Int = try {
        val method = power.javaClass.methods.firstOrNull {
            it.name == "getPowerScreenStatus" && it.parameterTypes.isEmpty()
        } ?: return -1
        method.invoke(power) as? Int ?: -1
    } catch (e: ReflectiveOperationException) {
        -1
    }

    private fun turnBacklightOn(power: Any): Boolean = backlight(power, "TurnBacklightOn", "turnBacklightOn")

    private fun turnBacklightOff(power: Any): Boolean = backlight(power, "TurnBacklightOff", "turnBacklightOff")

    private fun backlight(power: Any, vararg names: String): Boolean {
        for (name in names) {
            val method = power.javaClass.methods.firstOrNull { it.name == name } ?: continue
            try {
                when (method.parameterTypes.size) {
                    0 -> method.invoke(power)
                    1 -> method.invoke(power, SystemClock.uptimeMillis())
                    else -> continue
                }
            } catch (e: ReflectiveOperationException) {
                continue
            }
            return true
        }
        return false
    }

    private fun withLock(manager: Any, name: String): Boolean {
        val service = managerService(manager) ?: return false
        val method = service.javaClass.methods.firstOrNull { it.name == name } ?: return false
        return try {
            when (method.parameterTypes.size) {
                1 -> method.invoke(service, token)
                2 -> method.invoke(service, token, "StrikeDeterrent")
                else -> return false
            }
            true
        } catch (e: ReflectiveOperationException) {
            false
        }
    }

    private fun managerService(manager: Any): Any? = try {
        val field = manager.javaClass.getDeclaredField("mService")
        field.isAccessible = true
        field.get(manager)
    } catch (e: ReflectiveOperationException) {
        null
    }
}

private fun panelPowerManager(): Any? {
    val ctx = DaemonContext.get() ?: return null
    return try {
        ctx.getSystemService(Context.POWER_SERVICE)
    } catch (e: RuntimeException) {
        null
    }
}
