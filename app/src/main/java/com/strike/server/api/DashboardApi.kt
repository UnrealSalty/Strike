package com.strike.server.api

import android.content.Context
import com.strike.Autostart
import com.strike.core.Pin
import com.strike.daemon.Shell
import com.strike.recording.Storage
import com.strike.surveillance.EventStorage
import com.strike.server.JSON
import com.strike.server.Response
import com.strike.server.TEXT
import com.strike.vehicle.VehicleTelemetry
import com.strike.update.Updates
import org.json.JSONObject
import java.io.File
import java.util.Locale

private val AUTOSTART_SCREENS = listOf(
    "am start -n com.byd.appstartmanagement/.frame.AppStartManagement",
    "monkey -p com.byd.appstartmanagement -c android.intent.category.LAUNCHER 1"
)

class DashboardApi(context: Context, private val shell: Shell, private val daemons: DaemonsApi, private val pin: Pin,
                   private val updates: Updates, private val vehicle: VehicleTelemetry) {

    private val storage = Storage(context, shell)
    private val events = EventStorage(context, shell)
    private val autostart = Autostart(File(context.filesDir, "autostart.confirmed")) {
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
    }

    init {
        storage.volumeSnapshot()
    }

    fun status(inCar: Boolean): Response {
        val payload = JSONObject()
        payload.put("inCar", inCar)
        payload.put("updates", updates.status(full = false))
        payload.put("autostartCheck", autostart.needsCheck())
        if (inCar) payload.put("pinSet", pin.isSet())
        val stats = LibraryScan.stats(storage, events)
        val volume = stats.volume
        if (volume != null) {
            val room = JSONObject()
            room.put("location", stats.location)
            room.put("usedMb", stats.usedMb)
            room.put("budgetMb", storage.budgetMb())
            room.put("freeMb", volume.freeMb)
            room.put("clips", stats.clips)
            room.put("clipsToday", stats.clipsToday)
            payload.put("storage", room)
        }
        val daemonStatus = daemons.dashboard()
        payload.put("daemons", daemonStatus.getJSONObject("daemons"))
        payload.put("recording", daemonStatus.getJSONObject("recording"))
        val summary = stats.events
        payload.put("eventCount", summary?.clips ?: JSONObject.NULL)
        val latest = summary?.latest
        payload.put("lastEvent", if (latest == null) JSONObject.NULL else JSONObject().apply {
            put("id", latest.id)
            put("date", latest.date)
            put("time", latest.time)
            put("kind", latest.kind.name.lowercase(Locale.US))
            put("seen", latest.seen ?: JSONObject.NULL)
        })

        val snapshot = vehicle.snapshot()
        if (snapshot != null) {
            val car = JSONObject()
            if (snapshot.soc != null) car.put("soc", snapshot.soc)
            if (snapshot.rangeKm != null) car.put("rangeKm", snapshot.rangeKm)
            if (snapshot.batteryKwh != null) car.put("batteryKwh", snapshot.batteryKwh)
            if (snapshot.fuelPercent != null) car.put("fuelPercent", snapshot.fuelPercent)
            if (snapshot.fuelRangeKm != null) car.put("fuelRangeKm", snapshot.fuelRangeKm)
            payload.put("vehicle", car)
        }
        return Response(200, JSON, payload.toString().toByteArray())
    }

    fun openAutostart(): Response {
        val opened = AUTOSTART_SCREENS.any { command ->
            shell.read("$command 2>&1")?.let { !it.contains("Error") } == true
        }
        if (!opened) return Response(503, TEXT, "Could not open the car's autostart list".toByteArray())
        autostart.confirm()
        return Response(204, TEXT)
    }
}
