package com.strike.recording

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.strike.StrikeApp
import com.strike.core.Config
import com.strike.core.DiLink5
import com.strike.core.Logs
import com.strike.core.PinSession
import com.strike.surveillance.SurveillanceSettings
import com.strike.vehicle.VehicleTelemetry
import com.strike.web.WebUi

private const val TAG = "AccOff"

// Forward ACC off before the next poll so the daemon can finalize or hand over capture.
class AccOff : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!DiLink5.isSupported) (context.applicationContext as StrikeApp).dashboard.acc(false)
        PinSession.lock()
        WebUi.cover()
        if (DiLink5.isSupported) return
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
