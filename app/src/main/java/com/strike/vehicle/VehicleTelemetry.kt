package com.strike.vehicle

import android.content.Context
import android.os.Looper
import com.strike.core.Logs

private const val TAG = "Vehicle"
private const val STATISTIC = "android.hardware.bydauto.statistic.BYDAutoStatisticDevice"
private const val POWER = "android.hardware.bydauto.power.BYDAutoPowerDevice"
private const val BODYWORK = "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice"
private const val GEARBOX = "android.hardware.bydauto.gearbox.BYDAutoGearboxDevice"
private const val OTA = "android.hardware.bydauto.ota.BYDAutoOtaDevice"

private const val RETRY_MS = 30_000L

private val GEARS = arrayOf("P", "R", "N", "D", "M", "S")

/** Read-only BYD signals for the Atto 2. No vehicle commands in Strike. */
class VehicleTelemetry(
    private val context: Context,
    private val warn: (String) -> Unit = { Logs.w(TAG, it) }
) {

    private val lock = Any()
    private val devices = HashMap<String, Any>()
    private val triedAtMs = HashMap<String, Long>()

    /** Null when the car's SDK is not on this device at all. */
    fun snapshot(): VehicleSnapshot? {
        val statistic = device(STATISTIC)
        val bodywork = device(BODYWORK)
        val gearbox = device(GEARBOX)
        val power = device(POWER)
        val ota = device(OTA)
        if (statistic == null && bodywork == null && gearbox == null && power == null && ota == null) {
            return null
        }
        val soc = socOf(read(statistic, "getElecPercentageValue")?.toDouble())
        return VehicleSnapshot(
            soc = soc,
            rangeKm = rangeOf(read(statistic, "getElecDrivingRangeValue")?.toInt()),
            batteryKwh = batteryKwhOf(
                read(power, "getBatteryRemainPowerEV")?.toDouble(),
                read(statistic, "getRemainingBatteryPower")?.toInt(),
                soc
            ),
            gear = gearOf(read(gearbox, "getGearboxAutoModeType")?.toInt()),
            accOn = accOnOf(read(bodywork, "getPowerLevel")?.toInt()),
            locked = lockOf(read(ota, "getLFDoorLockState")?.toInt())
        )
    }

    /** Null while unknown, so a caller waits rather than assuming the car is off. */
    fun accOn(): Boolean? = accOnOf(read(device(BODYWORK), "getPowerLevel")?.toInt())

    /** Poll only the signals needed to hand the camera between driving and parking. */
    fun parkingSnapshot(): VehicleSnapshot = VehicleSnapshot(
        soc = null,
        rangeKm = null,
        batteryKwh = null,
        gear = gearOf(read(device(GEARBOX), "getGearboxAutoModeType")?.toInt()),
        accOn = polledAccOnOf(read(device(BODYWORK), "getPowerLevel")?.toInt()),
        locked = lockOf(read(device(OTA), "getLFDoorLockState")?.toInt())
    )

    private fun read(device: Any?, getter: String): Number? {
        if (device == null) return null
        return try {
            device.javaClass.getMethod(getter).invoke(device) as? Number
        } catch (e: ReflectiveOperationException) {
            null
        }
    }

    private fun device(className: String): Any? = synchronized(lock) {
        devices[className]?.let { return it }
        val now = System.currentTimeMillis()
        val tried = triedAtMs[className]
        if (tried != null && now - tried < RETRY_MS) return null
        triedAtMs[className] = now
        val resolved = resolve(className)
        if (resolved != null) devices[className] = resolved
        resolved
    }

    private fun resolve(className: String): Any? {
        // getInstance builds handlers, so the calling thread needs a looper.
        if (Looper.myLooper() == null) Looper.prepare()
        val device = BydSdk.deviceClass(className, context) ?: return null
        return try {
            device.getMethod("getInstance", Context::class.java)
                .invoke(null, BydPermissions(context))
        } catch (e: ReflectiveOperationException) {
            warn("$className would not start: ${e.cause?.javaClass?.simpleName ?: e.javaClass.simpleName}")
            null
        }
    }
}

// The head unit answers 0 for a signal it has not received yet.
internal fun socOf(percent: Double?): Int? {
    if (percent == null || percent <= 0.0 || percent > 100.0) return null
    return Math.round(percent).toInt()
}

internal fun rangeOf(km: Int?): Int? = if (km != null && km in 1..999) km else null

internal fun batteryKwhOf(directKwh: Double?, tenthsKwh: Int?, soc: Int?): Double? {
    if (plausibleKwh(directKwh, soc)) return directKwh
    val derived = if (tenthsKwh == null) null else tenthsKwh / 10.0
    return if (plausibleKwh(derived, soc)) derived else null
}

// Some firmwares answer with the percentage in the energy field, so the reading
// has to imply a pack the size of the one in this car, which is about 45 kWh.
internal fun plausibleKwh(kwh: Double?, soc: Int?): Boolean {
    if (kwh == null || kwh <= 1.0 || kwh >= 90.0) return false
    if (soc == null || soc <= 5) return true
    val capacity = kwh / (soc / 100.0)
    return capacity >= 20.0 && capacity <= 90.0
}

internal fun gearOf(mode: Int?): String? =
    if (mode != null && mode in 1..GEARS.size) GEARS[mode - 1] else null

// 4 is the HAL bluffing and 255 is it admitting it does not know.
internal fun accOnOf(powerLevel: Int?): Boolean? =
    if (powerLevel != null && powerLevel in 0..3) powerLevel >= 2 else null

// Overdrive's heartbeat skips accessory level 1 while power is transitioning.
internal fun polledAccOnOf(powerLevel: Int?): Boolean? =
    if (powerLevel == 1) null else accOnOf(powerLevel)

/** 1 unlocked, 2 locked. 0 is the HAL saying it does not know. */
internal fun lockOf(state: Int?): Boolean? = when (state) {
    1 -> false
    2 -> true
    else -> null
}
