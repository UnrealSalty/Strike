package com.strike.server.api

import android.content.Context
import com.strike.core.Config
import com.strike.daemon.DaemonClient
import com.strike.daemon.Shell
import com.strike.recording.ClipThumbs
import com.strike.recording.RecordingSettings
import com.strike.recording.Storage
import com.strike.surveillance.EventStorage
import com.strike.surveillance.SurveillanceSettings
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
import java.io.IOException
import java.util.Locale

private const val MP4 = "video/mp4"
private const val JPEG = "image/jpeg"

class SurveillanceApi(context: Context, private val shell: Shell) {

    private val events = EventStorage(context, shell)
    private val clips = Storage(context, shell)
    private val thumbs = ClipThumbs(File(context.cacheDir, "heroes"))
    private val daemon = DaemonClient()

    // Storage publication belongs to Triggers; read requests must not remount or reprobe volumes.
    fun events(): Response {
        val rows = JSONArray()
        for (event in events.store().list()) {
            val row = JSONObject()
            row.put("id", event.id)
            row.put("kind", event.kind.name.lowercase(Locale.US))
            row.put("seen", event.seen ?: JSONObject.NULL)
            row.put("score", event.score)
            row.put("date", event.date)
            row.put("time", event.time)
            row.put("bytes", event.bytes)
            val bands = JSONArray()
            for (band in event.bands) {
                val span = JSONObject()
                span.put("startMs", band.startMs)
                span.put("endMs", band.endMs)
                span.put("seen", band.seen)
                bands.put(span)
            }
            row.put("events", bands)
            rows.put(row)
        }
        val payload = JSONObject()
        payload.put("mounted", events.selected() != null)
        payload.put("location", events.location())
        payload.put("enabled", Config.getBool(SurveillanceSettings.ENABLED, false))
        payload.put("mode", setting(SurveillanceSettings.MODE))
        payload.put("events", rows)
        return Response(200, JSON, payload.toString().toByteArray())
    }

    fun clip(id: String, range: String?): Response {
        val file = events.store().file(id) ?: return notFound()
        val totalBytes = file.length()
        val wanted = rangeOf(range, totalBytes)
            ?: return Response(200, MP4, slice = FileSlice(file, 0, totalBytes, totalBytes))
        val length = wanted.last - wanted.first + 1
        return Response(206, MP4, slice = FileSlice(file, wanted.first, length, totalBytes))
    }

    // Smart clips use the detection still; other clips use a decoded thumbnail.
    fun hero(id: String): Response {
        val store = events.store()
        val boxed = store.hero(id)
        val clip = store.file(id) ?: return notFound()
        if (boxed != null) {
            val jpeg = try {
                boxed.readBytes()
            } catch (e: IOException) {
                return notFound()
            }
            val durationMs = thumbs.of(clip, id)?.durationMs ?: 0L
            return Response(
                200, JPEG, jpeg, headers = mapOf(DURATION_HEADER to durationMs.toString())
            )
        }
        val thumb = thumbs.of(clip, id) ?: return notFound()
        return Response(
            200, JPEG, thumb.jpeg, headers = mapOf(DURATION_HEADER to thumb.durationMs.toString())
        )
    }

    fun delete(id: String): Response {
        if (!events.store().delete(id)) return notFound()
        thumbs.forget(id)
        return Response(200, JSON, "{}".toByteArray())
    }

    fun settings(): Response {
        events.publish(shell)
        val payload = JSONObject()
        payload.put("values", values())
        payload.put("volumes", volumesPayload())
        return Response(200, JSON, payload.toString().toByteArray())
    }

    fun save(body: String): Response {
        val key = formValue(body, "key") ?: return Response(400, TEXT, "No setting named".toByteArray())
        val value = formValue(body, "value") ?: return Response(400, TEXT, "No value given".toByteArray())
        if (!SurveillanceSettings.accepts(key, value)) {
            return Response(400, TEXT, "That setting does not take that value".toByteArray())
        }
        val stored = when (key) {
            SurveillanceSettings.ENABLED, SurveillanceSettings.SCREEN ->
                Config.put(shell, key, value == "true")
            SurveillanceSettings.BUDGET_MB -> Config.put(shell, key, value.toInt())
            else -> Config.put(shell, key, value)
        }
        if (!stored) {
            return Response(503, TEXT, "Strike has no shell access, so it cannot save that".toByteArray())
        }
        if (key == SurveillanceSettings.BUDGET_MB) events.reap()
        if (key == SurveillanceSettings.LOCATION || key == SurveillanceSettings.ENABLED) {
            events.publish(shell)
        }
        return Response(200, JSON, "{}".toByteArray())
    }

    fun preview(): Response {
        val message = Config.getString(
            SurveillanceSettings.MESSAGE, SurveillanceSettings.MESSAGE_FALLBACK
        )
        val seconds = setting(SurveillanceSettings.SCREEN_SECONDS).toInt()
        if (!daemon.previewScreen(message, seconds)) {
            return Response(503, TEXT, "The camera daemon is not running".toByteArray())
        }
        return Response(200, JSON, "{}".toByteArray())
    }

    private fun values(): JSONObject {
        val values = JSONObject()
        for ((key, choice) in SurveillanceSettings.choices) {
            values.put(key, Config.getString(key, choice.fallback))
        }
        values.put(SurveillanceSettings.ENABLED, Config.getBool(SurveillanceSettings.ENABLED, false))
        values.put(SurveillanceSettings.SCREEN, Config.getBool(SurveillanceSettings.SCREEN, false))
        values.put(
            SurveillanceSettings.MESSAGE,
            Config.getString(SurveillanceSettings.MESSAGE, SurveillanceSettings.MESSAGE_FALLBACK)
        )
        values.put(
            SurveillanceSettings.BUDGET_MB,
            Config.getInt(SurveillanceSettings.BUDGET_MB, SurveillanceSettings.BUDGET_FALLBACK_MB)
        )
        return values
    }

    private fun volumesPayload(): JSONArray {
        val mounted = events.mounted()
        val recordingLocation = clips.location()
        val recordingBudgetMb = clips.budgetMb()
        val rows = JSONArray()
        for (location in SurveillanceSettings.choices
            .getValue(SurveillanceSettings.LOCATION).options) {
            val row = JSONObject()
            row.put("location", location)
            val volume = mounted[location]
            row.put("mounted", volume != null)
            if (volume != null) {
                val usedMb = events.usedMb(volume)
                val reservedMb = reservedOn(location, recordingLocation, recordingBudgetMb)
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

    private fun setting(key: String): String =
        Config.getString(key, SurveillanceSettings.fallback(key))
}
