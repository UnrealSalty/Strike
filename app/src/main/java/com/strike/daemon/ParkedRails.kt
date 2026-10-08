package com.strike.daemon

import android.content.Context
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import com.strike.vehicle.BydPermissions
import com.strike.vehicle.BydSdk
import java.io.File
import java.io.IOException

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
private const val MCU_WAKE = -1442840502
private const val ISP_NEED = 0x4090103E
private const val ISP_WORK = 0x4090103C

private const val SETTLE_MS = 1_000L

// Wake the MCU, request camera power and keep the AP awake for parked capture.
object ParkedRails {

    @Volatile
    var isHeld = false
        private set

    @Volatile private var wakeLock: PowerManager.WakeLock? = null

    internal val isAwake: Boolean get() = wakeLock?.isHeld == true
    private val devices = ParkedDevices(::findPower, ::findSpecial, SystemClock::elapsedRealtime)
    private val maintenance = ParkedMaintenance(SystemClock::elapsedRealtime)
    private var powerUnavailable = false
    private var specialUnavailable = false
    private var lease: ParkedLease? = null
    private val recoveryLease = ParkedLease(File("$STRIKE_DIR/parked-power.lock"), 3)
    private var cameraOwner = true
    private var leaseFailed = false

    @Synchronized
    fun hold(camera: Boolean = true, stillWanted: () -> Boolean = { true }) {
        if (isHeld) return
        val claim = claim(camera)
        try {
            if (!claim.acquire()) return
            leaseFailed = false
        } catch (e: IOException) {
            if (!leaseFailed) DaemonLog.w(TAG, "Could not claim parked power")
            leaseFailed = true
            return
        }
        cameraOwner = camera
        isHeld = true
        try {
            wakeMcu()
            try {
                Thread.sleep(SETTLE_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (!stillWanted()) { release(); return }
            val landed = vote()
            if (cameraOwner) wakeAp()
            userActivity()
            if (cameraOwner) takeWakeLock()
            DaemonLog.d(TAG, "holding parked power for ${if (cameraOwner) "surveillance" else "Online"}, $landed rail writes landed")
        } catch (e: Exception) {
            release()
            throw e
        }
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
        if (cameraOwner && !isAwake) takeWakeLock()
        return maintain(cameraOwner)
    }

    @Synchronized
    internal fun holdRecovery(): Boolean {
        if (recoveryLease.isHeld) return true
        return try {
            if (!recoveryLease.acquire()) return false
            if (!isHeld) {
                wakeMcu()
                vote()
                userActivity()
            }
            true
        } catch (e: IOException) {
            false
        }
    }

    @Synchronized
    internal fun tickRecovery() {
        if (recoveryLease.isHeld && !isHeld) maintain(wakeDisplay = false)
    }

    @Synchronized
    internal fun releaseRecovery(): Boolean = try {
        recoveryLease.release { releaseVotes() }
    } catch (e: IOException) {
        false
    }

    private fun maintain(wakeDisplay: Boolean): Boolean {
        val power = devices.power
        val special = devices.special
        devices.refresh()
        if (devices.power !== power || devices.special !== special) {
            wakeMcu()
            vote()
            userActivity()
        }
        if (maintenance.activityDue) {
            userActivity()
        }
        if (maintenance.voteDue) {
            vote()
        }
        if (maintenance.wakeDue) {
            wakeMcu()
            if (wakeDisplay) wakeAp()
            return true
        }
        return false
    }

    @Synchronized
    fun release() {
        if (!isHeld) return
        val claim = checkNotNull(lease)
        try {
            claim.release { releaseVotes() }
        } catch (e: IOException) {
            if (!leaseFailed) DaemonLog.w(TAG, "Could not release the parked power claim")
            leaseFailed = true
        } finally {
            if (!claim.isHeld) {
                isHeld = false
                wakeLock?.let { if (it.isHeld) it.release() }
                wakeLock = null
                DaemonLog.d(TAG, "parked power released for ${if (cameraOwner) "surveillance" else "Online"}")
            }
        }
    }

    @Synchronized
    internal fun onlineHeld(): Boolean = try {
        claim(camera = true).heldBy(2)
    } catch (e: IOException) {
        if (!leaseFailed) DaemonLog.w(TAG, "Could not read the Online parked power claim")
        leaseFailed = true
        false
    }

    private fun claim(camera: Boolean): ParkedLease = lease ?:
        ParkedLease(File("$STRIKE_DIR/parked-power.lock"), if (camera) 1 else 2).also { lease = it }

    private fun releaseVotes() {
        writeSpecial(SENTRY_ENTER, 0)
        writeSpecial(SENTRY_STATE, 2)
        writeSpecial(OEM_KEY_1, 0)
        writeSpecial(OEM_KEY_2, 2)
        writeSpecial(ISP_NEED, 0)
        writeSpecial(ISP_WORK, 0)
        writePower(MCU_WAKE, 0)
    }

    private fun vote(): Int {
        maintenance.didVote()
        var landed = 0
        if (writePower(MCU_WAKE, 1)) landed++
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

    internal fun wakeAp(): Boolean {
        val pm = powerManager() ?: return false
        val whenMs = SystemClock.uptimeMillis()
        val reason = accOffReason()
        if (invoke(pm, "wakeUp", arrayOf(Long::class.java, Int::class.java, String::class.java), whenMs, reason, "ACC_ON")) {
            return true
        }
        return invoke(pm, "wakeUp", arrayOf(Long::class.java), whenMs)
    }

    private fun userActivity() {
        maintenance.didActivity()
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
        maintenance.didWake()
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
        devices.refresh()
        return devices.power
    }

    private fun specialDevice(): Any? {
        devices.refresh()
        return devices.special
    }

    private fun findPower(): Any? {
        val ctx = hardwareContext() ?: return null
        val found = instance(POWER, ctx)
        if (found == null && !powerUnavailable) DaemonLog.w(TAG, "no power device, MCU wake is unavailable")
        if (found != null && powerUnavailable) DaemonLog.d(TAG, "power device is available")
        powerUnavailable = found == null
        return found
    }

    private fun findSpecial(): Any? {
        val ctx = hardwareContext() ?: return null
        var found: Any? = null
        for (name in SPECIAL) {
            found = instance(name, ctx)
            if (found != null) break
        }
        if (found == null && !specialUnavailable) DaemonLog.w(TAG, "no special device, camera rail votes will not land")
        if (found != null && specialUnavailable) DaemonLog.d(TAG, "camera power device is available")
        specialUnavailable = found == null
        return found
    }

    private fun hardwareContext(): Context? {
        if (Looper.myLooper() == null) Looper.prepare()
        return context()
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
