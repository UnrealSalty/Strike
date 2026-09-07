package com.strike.surveillance

import com.strike.vehicle.VehicleSnapshot

internal fun surveillanceReason(
    enabled: Boolean,
    snapshot: VehicleSnapshot?,
    arm: String,
    mode: String?,
    confirmingOff: Boolean
): String = when {
    !enabled -> "Off"
    confirmingOff -> "Confirming that the car switched off"
    snapshot?.accOn == null -> "Waiting for the car's power state"
    snapshot.accOn || (snapshot.gear != null && snapshot.gear != "P") ->
        "Standing by until the car switches off"
    arm == "lock" && mode == "off" && snapshot.locked == null ->
        "Lock state unavailable; arming one minute after switch-off"
    arm == "lock" && mode == "off" -> "Standing by until the doors lock"
    else -> "Starting surveillance"
}
