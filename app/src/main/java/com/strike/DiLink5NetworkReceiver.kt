package com.strike

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.strike.core.Config
import com.strike.core.DiLink5
import com.strike.core.DiLink5VoltageGuard
import com.strike.surveillance.SurveillanceSettings
import com.strike.vehicle.VehicleTelemetry
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class DiLink5NetworkReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION || !DiLink5.isSupported) return
        val command = intent.getStringExtra("command")
        if (command !in listOf("start", "stop", "detach")) return
        val pending = goAsync()
        val finished = AtomicBoolean()
        val main = Handler(Looper.getMainLooper())
        val finish: (Int) -> Unit = { result ->
            if (finished.compareAndSet(false, true)) {
                pending.resultCode = result
                pending.finish()
            }
        }
        val timeout = Runnable { finish(UNCONFIRMED) }
        main.postDelayed(timeout, 9_000L)
        val sentAtMs = intent.getLongExtra("sentAtMs", -1L)
        if (sentAtMs < 0L || SystemClock.elapsedRealtime() - sentAtMs !in 0L..30_000L) {
            main.removeCallbacks(timeout)
            finish(UNAVAILABLE)
            return
        }
        owner(context).request(command!!, sentAtMs) { result ->
            main.removeCallbacks(timeout)
            finish(result)
        }
    }

    companion object {
        const val ACTION = "com.strike.DILINK5_NETWORK"
        const val CONFIRMED = 1
        const val UNCONFIRMED = 2
        const val UNAVAILABLE = 3
        const val LOW_VOLTAGE = 4
        const val WAITING_VOLTAGE = 5
        private var instance: NetworkOwner? = null

        fun accOn(context: Context) {
            if (DiLink5.isSupported) owner(context).request("detach", SystemClock.elapsedRealtime()) {}
        }

        @Synchronized private fun owner(context: Context): NetworkOwner =
            instance ?: NetworkOwner(context.applicationContext).also { instance = it }
    }
}

private class NetworkOwner(private val context: Context) {
    private val prefs = context.createDeviceProtectedStorageContext()
        .getSharedPreferences("dilink5-network", Context.MODE_PRIVATE)
    private val voltage = DiLink5VoltageGuard(prefs.getBoolean("low", false), SystemClock::elapsedRealtime)
    private val vehicle = VehicleTelemetry(context)
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "dilink5-network").apply { isDaemon = true } }
    private val gate = Any()
    private var client: DiLink5NetworkClient? = null
    private var running = false
    private var desired = "detach"
    private var generation = 0L
    private var observedAtMs = -1L

    fun request(command: String, sentAtMs: Long, finish: (Int) -> Unit) {
        var detached: DiLink5NetworkClient? = null
        val token = synchronized(gate) {
            if (sentAtMs < observedAtMs ||
                (sentAtMs == observedAtMs && desired == "detach" && command != "detach")) {
                finish(DiLink5NetworkReceiver.UNCONFIRMED)
                return
            }
            observedAtMs = sentAtMs
            desired = command
            if (command == "detach") {
                generation++
                voltage.reset()
                prefs.edit().putBoolean("owned", false).putBoolean("low", false).apply()
                detached = client
                client = null
                null
            } else if (running) null else {
                running = true
                ++generation
            }
        }
        if (command == "detach") {
            detached?.close()
            finish(DiLink5NetworkReceiver.CONFIRMED)
            return
        }
        if (token == null) {
            finish(DiLink5NetworkReceiver.UNCONFIRMED)
            return
        }
        worker.execute {
            try {
                finish(try {
                    execute(command, token)
                } catch (e: RuntimeException) {
                    DiLink5NetworkReceiver.UNAVAILABLE
                })
            } finally {
                val stopAtMs = synchronized(gate) {
                    running = false
                    if (command == "start" && desired == "stop") observedAtMs else null
                }
                if (stopAtMs != null) request("stop", stopAtMs) {}
            }
        }
    }

    private fun execute(command: String, token: Long): Int {
        var start = command == "start" && enabled()
        var blocked: Int? = null
        if (start) {
            val reading = vehicle.batteryVoltage()
            synchronized(gate) {
                if (token != generation || desired != command) return DiLink5NetworkReceiver.UNCONFIRMED
                blocked = when (voltage.sample(reading)) {
                    DiLink5VoltageGuard.State.READY -> null
                    DiLink5VoltageGuard.State.LOW -> DiLink5NetworkReceiver.LOW_VOLTAGE
                    DiLink5VoltageGuard.State.WAITING -> DiLink5NetworkReceiver.WAITING_VOLTAGE
                }
                if (voltage.blocked) prefs.edit().putBoolean("low", true).apply()
            }
            start = blocked == null && enabled()
        }
        if (command == "start" && !start && blocked == null) blocked = DiLink5NetworkReceiver.UNAVAILABLE
        val held = synchronized(gate) {
            if (token != generation || desired != command) return DiLink5NetworkReceiver.UNCONFIRMED
            if (!start && !prefs.getBoolean("owned", false)) {
                return blocked ?: DiLink5NetworkReceiver.CONFIRMED
            }
            // A timed-out call can still reach QNX. Keep ownership until STOP is confirmed.
            if (start && !prefs.getBoolean("owned", false) &&
                !prefs.edit().putBoolean("owned", true).commit()) {
                return DiLink5NetworkReceiver.UNAVAILABLE
            }
            client ?: DiLink5NetworkClient(context).also { client = it }
        }
        val result = held.request(start)
        val released = synchronized(gate) {
            if (token != generation || desired != command) return DiLink5NetworkReceiver.UNCONFIRMED
            (!start && result == DiLink5NetworkClient.Result.CONFIRMED).also { released ->
                if (released) {
                    prefs.edit().putBoolean("owned", false).apply()
                    if (client === held) client = null
                }
            }
        }
        if (released) held.close()
        return when (result) {
            DiLink5NetworkClient.Result.CONFIRMED -> blocked ?: DiLink5NetworkReceiver.CONFIRMED
            DiLink5NetworkClient.Result.UNCONFIRMED -> DiLink5NetworkReceiver.UNCONFIRMED
            else -> DiLink5NetworkReceiver.UNAVAILABLE
        }
    }

    private fun enabled(): Boolean = RecorderRevival.isEnabled(context) &&
        Config.getBool(SurveillanceSettings.ENABLED, false) &&
        Config.getBool(SurveillanceSettings.DILINK5_KEEP_ALIVE, false)
}
