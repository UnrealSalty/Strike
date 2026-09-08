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
private const val CAR_ADAPTER = "com.ts.lib.caradapter.CarAdapterManager"

private const val RETRY_MS = 30_000L

private val GEARS = arrayOf("P", "R", "N", "D", "M", "S")

// Read-only BYD vehicle signals.
class VehicleTelemetry(
    private val context: Context,
    private val warn: (String) -> Unit = { Logs.w(TAG, it) }
) {

    private val lock = Any()
    private val devices = HashMap<String, Any>()
    private val triedAtMs = HashMap<String, Long>()
    private val gears = GearReader(adapter = { device(CAR_ADAPTER) })
    private val adapterType by lazy {
        try {
            Class.forName(CAR_ADAPTER)
        } catch (e: ClassNotFoundException) {
            null
        } catch (e: LinkageError) {
            warn("The alternate gear reader could not load")
            null
        }
    }

    fun snapshot(): VehicleSnapshot? {
        val statistic = device(STATISTIC)
        val bodywork = device(BODYWORK)
        val gearbox = device(GEARBOX)
        val power = device(POWER)
        val ota = device(OTA)
        val gear = gear(gearbox)
        if (statistic == null && bodywork == null && gearbox == null && power == null && ota == null && gear == null) {
            return null
        }
        val soc = socOf(read(statistic, "getElecPercentageValue")?.toDouble())
        return VehicleSnapshot(
            soc = soc,
            rangeKm = rangeOf(read(statistic, "getElecDrivingRangeValue")?.toInt()),
            batteryKwh = batteryKwhOf(read(power, "getBatteryRemainPowerEV")?.toDouble(), soc) {
                read(statistic, "getRemainingBatteryPower")?.toInt()
            },
            gear = gear,
            accOn = accOnOf(read(bodywork, "getPowerLevel")?.toInt()),
            locked = lockOf(read(ota, "getLFDoorLockState")?.toInt())
        )
    }

    fun accOn(): Boolean? = accOnOf(read(device(BODYWORK), "getPowerLevel")?.toInt())

    fun soc(): Int? = socOf(read(device(STATISTIC), "getElecPercentageValue")?.toDouble())

    fun parkingSnapshot(): VehicleSnapshot = VehicleSnapshot(
        soc = null,
        rangeKm = null,
        batteryKwh = null,
        gear = gear(device(GEARBOX)),
        accOn = polledAccOnOf(read(device(BODYWORK), "getPowerLevel")?.toInt()),
        locked = lockOf(read(device(OTA), "getLFDoorLockState")?.toInt())
    )

    private fun gear(gearbox: Any?): String? =
        gears.read(read(gearbox, "getGearboxAutoModeType")?.toInt())

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
        val device = (if (className == CAR_ADAPTER) adapterType else BydSdk.deviceClass(className, context))
            ?: return null
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
    if (percent == null || !percent.isFinite() || percent <= 0.0 || percent > 100.0) return null
    return Math.round(percent).toInt()
}

internal fun rangeOf(km: Int?): Int? = if (km != null && km in 1..999) km else null

internal inline fun batteryKwhOf(directKwh: Double?, soc: Int?, fallbackTenthsKwh: () -> Int?): Double? {
    if (plausibleKwh(directKwh, soc)) return directKwh
    val derived = fallbackTenthsKwh()?.div(10.0)
    return if (plausibleKwh(derived, soc)) derived else null
}

internal fun plausibleKwh(kwh: Double?, soc: Int?): Boolean {
    if (kwh == null || !kwh.isFinite() || kwh <= 1.0 || kwh >= 120.0) return false
    if (soc == null || soc <= 5) return kwh < 90.0
    val capacity = kwh / (soc / 100.0)
    if (capacity < 20.0 || capacity > 130.0) return false
    // SOC echoes imply a pack near 100 kWh. Smaller packs can have similar numbers at low SOC.
    return capacity <= 90.0 || kotlin.math.abs(kwh - soc) >= 5.0
}

internal fun gearOf(mode: Int?): String? =
    if (mode != null && mode in 1..GEARS.size) GEARS[mode - 1] else null

// Power levels 4 and 255 are not usable ACC readings.
internal fun accOnOf(powerLevel: Int?): Boolean? =
    if (powerLevel != null && powerLevel in 0..3) powerLevel >= 2 else null

// Overdrive's heartbeat skips accessory level 1 while power is transitioning.
internal fun polledAccOnOf(powerLevel: Int?): Boolean? =
    if (powerLevel == 1) null else accOnOf(powerLevel)

// Lock state: 1 unlocked, 2 locked, 0 unavailable.
internal fun lockOf(state: Int?): Boolean? = when (state) {
    1 -> false
    2 -> true
    else -> null
}
