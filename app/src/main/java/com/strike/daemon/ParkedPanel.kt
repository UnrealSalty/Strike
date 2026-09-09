package com.strike.daemon

import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.SystemClock
import java.lang.reflect.InvocationTargetException

class ParkedPanel(
    private val tag: String = "RedScreen",
    private val powerManager: () -> Any? = ::panelPowerManager
) {
    private var power: Any? = null
    private var offLockHeld = false
    private var offTokenWasNull = false
    private val offToken = Binder()

    @Synchronized
    fun wake() {
        val manager = powerManager()
        if (manager != null) {
            turnBacklightOn(manager)
            turnBacklightOnWithLock(manager)
            val status = screenStatus(manager)
            if (status == 0) {
                turnBacklightOnWithLock(manager)
                DaemonLog.w(tag, "panel still dark after WithLock, status=$status")
            }
        } else {
            powerService()?.let { turnBacklightOn(it) }
        }
    }

    @Synchronized
    fun darken() {
        val manager = powerManager() ?: return
        turnBacklightOff(manager)
        if (!offLockHeld) turnBacklightOffWithLock(manager)
    }

    @Synchronized
    fun release(): Boolean {
        if (!offLockHeld) return true
        val manager = powerManager() ?: return false
        return turnBacklightOnWithLock(manager)
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

    private fun turnBacklightOn(power: Any): Boolean {
        for (name in arrayOf("TurnBacklightOn", "turnBacklightOn")) {
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

    private fun turnBacklightOnWithLock(manager: Any): Boolean {
        val service = managerService(manager) ?: return false
        val method = try {
            service.javaClass.getMethod(
                "TurnBacklightOnWithLock",
                IBinder::class.java,
                String::class.java
            )
        } catch (e: NoSuchMethodException) {
            return false
        }
        // Only the token that acquired an Off lock can acknowledge its release.
        val tokens = if (offLockHeld) arrayOf<IBinder?>(if (offTokenWasNull) null else offToken)
            else arrayOf<IBinder?>(offToken, null)
        for (token in tokens) {
            try {
                method.invoke(service, token, "StrikeDeterrent")
                offLockHeld = false
                return true
            } catch (e: ReflectiveOperationException) {
                continue
            }
        }
        return false
    }

    private fun turnBacklightOff(power: Any): Boolean {
        for (name in arrayOf("TurnBacklightOff", "turnBacklightOff")) {
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

    private fun turnBacklightOffWithLock(manager: Any): Boolean {
        val service = managerService(manager) ?: return false
        val method = service.javaClass.methods.firstOrNull { it.name == "TurnBacklightOffWithLock" }
            ?: return false
        val types = method.parameterTypes
        for (token in arrayOf<IBinder?>(offToken, null)) {
            try {
                when (types.size) {
                    1 -> method.invoke(service, token)
                    2 -> method.invoke(service, token, "StrikeDeterrent")
                    else -> return false
                }
                offTokenWasNull = token == null
                offLockHeld = true
                return true
            } catch (e: InvocationTargetException) {
                if (e.cause is IllegalArgumentException || e.cause is NullPointerException) continue
                // The vendor may have taken the lock before its reply failed.
                offTokenWasNull = token == null
                offLockHeld = true
                DaemonLog.w(tag, "panel Off lock was not confirmed; retaining its release token")
                return false
            } catch (e: IllegalArgumentException) {
                continue
            } catch (e: ReflectiveOperationException) {
                return false
            }
        }
        return false
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
