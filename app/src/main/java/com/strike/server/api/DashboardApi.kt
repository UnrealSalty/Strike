package com.strike.server.api

import android.content.Context
import com.strike.core.Pin
import com.strike.daemon.Shell
import com.strike.recording.MB
import com.strike.recording.Storage
import com.strike.recording.totalBytes
import com.strike.surveillance.EventStorage
import com.strike.server.JSON
import com.strike.server.Response
import com.strike.vehicle.VehicleTelemetry
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DashboardApi(context: Context, shell: Shell, private val daemons: DaemonsApi, private val pin: Pin) {

    private val storage = Storage(context, shell)
    private val vehicle = VehicleTelemetry(context)
    private val events = EventStorage(context, shell)

    fun status(inCar: Boolean): Response {
        val payload = JSONObject()
        payload.put("inCar", inCar)
        if (inCar) payload.put("pinSet", pin.isSet())
        val volume = storage.selected()
        if (volume != null) {
            val clips = storage.clipsOn(volume).list()
            val today = today()
            val room = JSONObject()
            room.put("location", storage.location())
            room.put("usedMb", (totalBytes(clips) / MB).toInt())
            room.put("budgetMb", storage.budgetMb())
            room.put("freeMb", volume.freeMb)
            room.put("clips", clips.size)
            room.put("clipsToday", clips.count { it.date == today })
            payload.put("storage", room)
        }
        payload.put("daemons", daemons.count())
        payload.put("recording", daemons.recording())
        val latest = events.store().latest()
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
            payload.put("vehicle", car)
        }
        return Response(200, JSON, payload.toString().toByteArray())
    }

    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
}
