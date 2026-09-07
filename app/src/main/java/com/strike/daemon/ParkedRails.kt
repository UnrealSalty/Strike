package com.strike.daemon

import android.content.Context
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import com.strike.vehicle.BydPermissions
import com.strike.vehicle.BydSdk

private const val TAG = "Rails"

private const val POWER = "android.hardware.bydauto.power.BYDAutoPowerDevice"
private const val EVENT_VALUE = "android.hardware.bydauto.BYDAutoEventValue"

private val SPECIAL = arrayOf(
    "android.hardware.bydauto.special.BYDAutoSpecialDevice",
    "android.hardware.special.BYDAutoSpecialDevice"
)

private const val SENTRY_ENTER = 782237711
private const val SENTRY_STATE = 782237728
private const val OEM_KEY_1 = 1901
private const val OEM_KEY_2 = 1902
private const val MCU_HOLD = -1442840502
private const val ISP_NEED = 0x4090103E
private const val ISP_WORK = 0x4090103C

private const val SETTLE_MS = 1_000L
private const val ACTIVITY_MS = 10_000L
private const val VOTE_MS = 5 * 60_000L
private const val WAKE_MS = 8 * 60_000L

// Hold the MCU/ISP rails and AP awake for parked capture, matching Overdrive's AccSentry.
object ParkedRails {

    @Volatile
    var isHeld = false
        private set

    private var wakeLock: PowerManager.WakeLock? = null
    private var power: Any? = null
    private var special: Any? = null
    private var looked = false
    private var activityAtMs = 0L
    private var voteAtMs = 0L
    private var wakeAtMs = 0L

    @Synchronized
    fun hold(stillWanted: () -> Boolean = { true }) {
        if (isHeld) return
        wakeMcu()
        try {
            Thread.sleep(SETTLE_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (!stillWanted()) return
        val landed = vote()
        wakeAp()
        userActivity()
        takeWakeLock()
        isHeld = true
        DaemonLog.d(TAG, "holding the parked rails, $landed rail writes landed")
    }

    @Synchronized
    fun reassert() {
        if (!isHeld) return
        vote()
        userActivity()
    }

    @Synchronized
    fun tick(): Boolean {
        if (!isHeld) return false
        val now = System.currentTimeMillis()
        if (now - activityAtMs >= ACTIVITY_MS) {
            activityAtMs = now
            userActivity()
        }
        if (now - voteAtMs >= VOTE_MS) {
            voteAtMs = now
            vote()
        }
        if (now - wakeAtMs >= WAKE_MS) {
            wakeAtMs = now
            wakeMcu()
            wakeAp()
            return true
        }
        return false
    }

    @Synchronized
    fun release() {
        if (!isHeld) return
        isHeld = false
        writeSpecial(SENTRY_ENTER, 0)
        writeSpecial(SENTRY_STATE, 2)
        writeSpecial(OEM_KEY_1, 0)
        writeSpecial(OEM_KEY_2, 2)
        writeSpecial(ISP_NEED, 0)
        writeSpecial(ISP_WORK, 0)
        writePower(MCU_HOLD, 0)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        DaemonLog.d(TAG, "parked rails released")
    }

    private fun vote(): Int {
        voteAtMs = System.currentTimeMillis()
        var landed = 0
        if (writePower(MCU_HOLD, 1)) landed++
        if (writeSpecial(SENTRY_ENTER, 1)) landed++
        if (writeSpecial(SENTRY_STATE, 1)) landed++
        if (writeSpecial(OEM_KEY_1, 1)) landed++
        if (writeSpecial(OEM_KEY_2, 1)) landed++
        if (writeSpecial(ISP_NEED, 1)) landed++
        if (writeSpecial(ISP_WORK, 1)) landed++
        return landed
    }

    private fun takeWakeLock() {
        if (wakeLock?.isHeld == true) return
        val ctx = context() ?: return
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "strike:parked")
        lock.setReferenceCounted(false)
        lock.acquire()
        wakeLock = lock
    }

    private fun wakeAp() {
        val pm = powerManager() ?: return
        val whenMs = SystemClock.uptimeMillis()
        val reason = accOffReason()
        if (invoke(pm, "wakeUp", arrayOf(Long::class.java, Int::class.java, String::class.java), whenMs, reason, "ACC_ON")) {
            return
        }
        invoke(pm, "wakeUp", arrayOf(Long::class.java), whenMs)
    }

    private fun userActivity() {
        activityAtMs = System.currentTimeMillis()
        val pm = powerManager() ?: return
        val whenMs = SystemClock.uptimeMillis()
        if (invoke(pm, "userActivity", arrayOf(Long::class.java, Int::class.java, Int::class.java), whenMs, 0, 1)) {
            return
        }
        invoke(pm, "userActivity", arrayOf(Long::class.java, Boolean::class.java), whenMs, true)
    }

    private fun accOffReason(): Int = try {
        PowerManager::class.java.getField("GO_TO_SLEEP_REASON_ACCOFF").getInt(null)
    } catch (e: ReflectiveOperationException) {
        9
    }

    private fun powerManager(): PowerManager? {
        val ctx = context() ?: return null
        return ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
    }

    private fun wakeMcu() {
        wakeAtMs = System.currentTimeMillis()
        val device = powerDevice() ?: return
        try {
            val method = device.javaClass.getMethod("wakeUpMcu")
            val rc = method.invoke(device)
            if (rc is Number && rc.toInt() != 0) {
                DaemonLog.w(TAG, "MCU wake returned ${rc.toInt()}")
            }
        } catch (e: ReflectiveOperationException) {
            DaemonLog.w(TAG, "MCU wake failed: ${e.javaClass.simpleName}")
        }
        val status = mcuStatus()
        if (status != null && status != 1 && status != 10) {
            DaemonLog.w(TAG, "MCU status $status after wake")
        }
    }

    private fun mcuStatus(): Int? {
        val device = powerDevice() ?: return null
        return try {
            device.javaClass.getMethod("getMcuStatus").invoke(device) as? Int
        } catch (e: ReflectiveOperationException) {
            null
        }
    }

    private fun writeSpecial(id: Int, value: Int): Boolean {
        val device = specialDevice() ?: return false
        return write(device, id, value)
    }

    private fun writePower(id: Int, value: Int): Boolean {
        val device = powerDevice() ?: return false
        return write(device, id, value)
    }

    private fun write(device: Any, id: Int, value: Int): Boolean {
        val valueClass = eventValueClass(device) ?: return false
        val packed = eventValue(valueClass, value) ?: return false
        return try {
            val method = device.javaClass.getMethod("set", IntArray::class.java, valueClass)
            val rc = method.invoke(device, intArrayOf(id), packed)
            rc == null || (rc is Number && rc.toInt() == 0)
        } catch (e: ReflectiveOperationException) {
            false
        }
    }

    private fun eventValueClass(device: Any): Class<*>? = try {
        Class.forName(EVENT_VALUE, true, device.javaClass.classLoader)
    } catch (e: ClassNotFoundException) {
        try {
            Class.forName(EVENT_VALUE)
        } catch (missing: ClassNotFoundException) {
            null
        }
    }

    private fun eventValue(cls: Class<*>, value: Int): Any? {
        return try {
            val made = cls.getDeclaredConstructor().newInstance()
            cls.getField("intValue").setInt(made, value)
            try {
                cls.getField("valueType").setInt(made, 1)
            } catch (e: NoSuchFieldException) {
                // Older SDKs only have intValue.
            }
            made
        } catch (e: ReflectiveOperationException) {
            null
        }
    }

    private fun powerDevice(): Any? {
        findDevices()
        return power
    }

    private fun specialDevice(): Any? {
        findDevices()
        return special
    }

    @Synchronized
    private fun findDevices() {
        if (looked) return
        if (Looper.myLooper() == null) Looper.prepare()
        val ctx = context() ?: return
        looked = true
        power = instance(POWER, ctx)
        if (power == null) DaemonLog.w(TAG, "no power device, MCU will not be held")
        for (name in SPECIAL) {
            special = instance(name, ctx)
            if (special != null) break
        }
        if (special == null) DaemonLog.w(TAG, "no special device, camera rail votes will not land")
    }

    private fun instance(className: String, ctx: Context): Any? {
        val wrapped = BydPermissions(ctx)
        val cls = try {
            Class.forName(className)
        } catch (e: ClassNotFoundException) {
            BydSdk.deviceClass(className, wrapped) ?: return null
        }
        return try {
            cls.getMethod("getInstance", Context::class.java).invoke(null, wrapped)
        } catch (e: ReflectiveOperationException) {
            DaemonLog.w(TAG, "$className would not start: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun context(): Context? = DaemonContext.get()

    private fun invoke(target: Any, name: String, types: Array<Class<*>>, vararg args: Any?): Boolean {
        return try {
            target.javaClass.getMethod(name, *types).invoke(target, *args)
            true
        } catch (e: ReflectiveOperationException) {
            false
        }
    }
}
