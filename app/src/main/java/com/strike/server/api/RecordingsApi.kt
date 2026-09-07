package com.strike.server.api

import android.content.Context
import com.strike.core.Config
import com.strike.daemon.BydApps
import com.strike.daemon.Shell
import com.strike.recording.ClipThumbs
import com.strike.recording.RecordingSettings
import com.strike.recording.Storage
import com.strike.surveillance.EventStorage
import com.strike.surveillance.reservedOn
import com.strike.server.FileSlice
import com.strike.server.JSON
import com.strike.server.Response
import com.strike.server.TEXT
import com.strike.server.formValue
import com.strike.server.notFound
import com.strike.server.rangeOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

private const val MP4 = "video/mp4"
private const val JPEG = "image/jpeg"

internal const val DURATION_HEADER = "X-Clip-Duration-Ms"

class RecordingsApi(context: Context, private val shell: Shell) {

    private val bydApps = BydApps(context, shell)
    private val storage = Storage(context, shell)
    private val events = EventStorage(context, shell)
    private val thumbs = ClipThumbs(File(context.cacheDir, "thumbs"))

    fun clips(): Response {
        val volume = storage.selected()
        val rows = JSONArray()
        if (volume != null) {
            for (clip in storage.clipsOn(volume).list()) {
                val row = JSONObject()
                row.put("id", clip.id)
                row.put("kind", clip.mode.name.lowercase(Locale.US))
                row.put("date", clip.date)
                row.put("time", clip.time)
                row.put("bytes", clip.bytes)
                rows.put(row)
            }
        }
        val payload = JSONObject()
        payload.put("mounted", volume != null)
        payload.put("location", storage.location())
        payload.put("mode", Config.getString(RecordingSettings.MODE, RecordingSettings.fallback(RecordingSettings.MODE)))
        payload.put("clips", rows)
        return Response(200, JSON, payload.toString().toByteArray())
    }

    fun clip(id: String, range: String?): Response {
        val volume = storage.selected() ?: return notFound()
        val file = storage.clipsOn(volume).file(id) ?: return notFound()
        val totalBytes = file.length()
        val wanted = rangeOf(range, totalBytes)
            ?: return Response(200, MP4, slice = FileSlice(file, 0, totalBytes, totalBytes))
        val length = wanted.last - wanted.first + 1
        return Response(206, MP4, slice = FileSlice(file, wanted.first, length, totalBytes))
    }

    /**
     * The clip length is only known once the header is read, and the list wants
     * it on the same request as the picture rather than one of its own.
     */
    fun thumb(id: String): Response {
        val volume = storage.selected() ?: return notFound()
        val file = storage.clipsOn(volume).file(id) ?: return notFound()
        val thumb = thumbs.of(file, id) ?: return notFound()
        return Response(
            200,
            JPEG,
            thumb.jpeg,
            headers = mapOf(DURATION_HEADER to thumb.durationMs.toString())
        )
    }

    fun delete(id: String): Response {
        val volume = storage.selected() ?: return notFound()
        if (!storage.clipsOn(volume).delete(id)) return notFound()
        thumbs.forget(id)
        return Response(200, JSON, "{}".toByteArray())
    }

    fun settings(): Response {
        storage.publish(shell)
        val payload = JSONObject()
        payload.put("values", values())
        payload.put("volumes", volumesPayload())
        payload.put("bydApps", bydAppStates())
        payload.put("bydAppsWritable", shell.isAuthorised())
        return Response(200, JSON, payload.toString().toByteArray())
    }

    fun save(body: String): Response {
        val key = formValue(body, "key") ?: return Response(400, TEXT, "No setting named".toByteArray())
        val value = formValue(body, "value") ?: return Response(400, TEXT, "No value given".toByteArray())
        if (!RecordingSettings.accepts(key, value)) {
            return Response(400, TEXT, "That setting does not take that value".toByteArray())
        }
        val stored = when (key) {
            RecordingSettings.AUDIO -> Config.put(shell, key, value == "true")
            RecordingSettings.BUDGET_MB -> Config.put(shell, key, value.toInt())
            else -> Config.put(shell, key, value)
        }
        if (!stored) {
            return Response(503, TEXT, "Strike has no shell access, so it cannot save that".toByteArray())
        }
        if (key == RecordingSettings.BUDGET_MB) storage.reap()
        if (key == RecordingSettings.LOCATION) storage.publish(shell)
        return Response(200, JSON, "{}".toByteArray())
    }

    private fun values(): JSONObject {
        val values = JSONObject()
        for ((key, choice) in RecordingSettings.choices) {
            values.put(key, Config.getString(key, choice.fallback))
        }
        values.put(RecordingSettings.AUDIO, Config.getBool(RecordingSettings.AUDIO, false))
        values.put(
            RecordingSettings.BUDGET_MB,
            Config.getInt(RecordingSettings.BUDGET_MB, RecordingSettings.BUDGET_FALLBACK_MB)
        )
        return values
    }

    private fun volumesPayload(): JSONArray {
        val mounted = storage.mounted()
        val eventsLocation = events.location()
        val eventsBudgetMb = events.budgetMb()
        val rows = JSONArray()
        for (location in RecordingSettings.choices.getValue(RecordingSettings.LOCATION).options) {
            val row = JSONObject()
            row.put("location", location)
            val volume = mounted[location]
            row.put("mounted", volume != null)
            if (volume != null) {
                val usedMb = storage.usedMb(volume)
                val reservedMb = reservedOn(location, eventsLocation, eventsBudgetMb)
                row.put("freeMb", volume.freeMb)
                row.put("totalMb", volume.totalMb)
                row.put("usedMb", usedMb)
                row.put("reservedMb", reservedMb)
                row.put(
                    "usable",
                    RecordingSettings.hasRoom(volume.freeMb, volume.totalMb, usedMb, reservedMb)
                )
                row.put(
                    "ceilingMb",
                    RecordingSettings.budgetCeilingMb(volume.freeMb, volume.totalMb, usedMb, reservedMb)
                )
            }
            rows.put(row)
        }
        return rows
    }

    private fun bydAppStates(): JSONObject {
        val apps = JSONObject()
        apps.put("dashcam", bydApps.state(BydApps.DASHCAM))
        apps.put("trafficMonitor", bydApps.state(BydApps.TRAFFIC_MONITOR))
        return apps
    }
}
