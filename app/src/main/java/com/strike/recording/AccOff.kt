package com.strike.recording

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.strike.core.Config
import com.strike.core.Logs
import com.strike.core.PinSession
import com.strike.surveillance.SurveillanceSettings
import com.strike.vehicle.VehicleTelemetry
import com.strike.web.WebUi

private const val TAG = "AccOff"

/**
 * The car announces the key going out, which reaches Strike sooner than the
 * next telemetry poll. Closing the drive clip early is the difference between
 * a playable file and one with no index, because power follows shortly after.
 *
 * With surveillance on this is a handover, not a shutdown: the daemon keeps
 * the camera and switches to the parked library. targetSdk 25 is what lets
 * that process stay alive.
 */
class AccOff : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        PinSession.lock()
        WebUi.cover()
        val watching = Config.getBool(SurveillanceSettings.ENABLED, false)
        val arm = Config.getString(
            SurveillanceSettings.ARM, SurveillanceSettings.fallback(SurveillanceSettings.ARM)
        )
        Logs.d(
            TAG,
            if (!watching) "the car switched off, closing the clip"
            else if (arm == "lock") "the car switched off, waiting for the doors to lock"
            else "the car switched off, handing the camera to surveillance"
        )
        val pending = goAsync()
        Thread({
            try {
                AccEdge.parked { VehicleTelemetry(context).accOn() }
            } finally {
                pending.finish()
            }
        }, "acc-off").start()
    }
}
