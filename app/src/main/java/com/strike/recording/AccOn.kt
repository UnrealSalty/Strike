package com.strike.recording

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The red layer is still up from a parked event when the key comes back.
 * Waiting for the next Triggers poll leaves it on the boot screen.
 */
class AccOn : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
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
