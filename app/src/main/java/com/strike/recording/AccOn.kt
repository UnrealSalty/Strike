package com.strike.recording

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.strike.StrikeApp

// Forward ACC on immediately so a parked deterrent does not remain over the boot screen.
class AccOn : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        (context.applicationContext as StrikeApp).online.acc(true)
        val pending = goAsync()
        Thread({
            try {
                AccEdge.driving()
            } finally {
                pending.finish()
            }
        }, "acc-on").start()
    }
}
