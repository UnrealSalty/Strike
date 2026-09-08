package com.strike.server.api

import android.content.Context
import com.strike.core.Config
import com.strike.core.LogLine
import com.strike.core.Logs
import com.strike.online.Online
import com.strike.daemon.BydApps
import com.strike.daemon.CAM_LOG_PATH
import com.strike.daemon.CAM_SCRIPT_PATH
import com.strike.daemon.Daemon
import com.strike.daemon.DaemonClient
import com.strike.daemon.Phase
import com.strike.daemon.Processes
import com.strike.daemon.RecorderDaemon
import com.strike.daemon.Shell
import com.strike.daemon.parseDaemonLog
import com.strike.recording.RecordingSettings
import com.strike.recording.Storage
import com.strike.surveillance.EventStorage
import com.strike.surveillance.SurveillanceSettings
import com.strike.server.JSON
import com.strike.server.Response
import com.strike.server.TEXT
import com.strike.server.formValue
import com.strike.server.notFound
import org.json.JSONArray
import org.json.JSONObject

private const val RUNNING = "running"
private const val STARTING = "starting"
private const val BROKEN = "broken"
private const val OFF = "off"
private const val LOG_TAIL_LINES = 60
private const val CARDS_CACHE_MS = 2_000L
private const val DASH = "\u2014"

internal const val RECORDER_API = "/api/daemons/recorder"

private val MODES = mapOf(
    "off" to "Off",
    "continuous" to "Whenever the car is on",
    "driving" to "Only while driving"
)
private val QUALITIES = mapOf(
    "economy" to "Economy",
    "standard" to "Standard",
    "high" to "High",
    "premium" to "Premium",
    "max" to "Maximum"
)
private val PLACES = mapOf(
    "internal" to "Internal storage",
    "sd" to "SD card",
    "usb" to "USB storage"
)
private val SENTRY_MODES = mapOf(
    "smart" to "Smart, person and vehicle",
    "continuous" to "Continuous"
)

class DaemonsApi(context: Context, private val shell: Shell, private val online: Online) {

    private val bydApps = BydApps(context, shell)
    private val processes = Processes(shell)
    private val storage = Storage(context, shell)
    private val events = EventStorage(context, shell)
    private val recorder = RecorderDaemon(shell, Daemon(context, shell), DaemonClient()) {
        storage.publish(shell)
        events.publish(shell)
    }
    private val cardsLock = Any()
    private var cards: JSONObject? = null
    private var cardsAtMs = 0L

    fun daemons(): Response = Response(200, JSON, payload().toString().toByteArray())

    fun pauseForUpdate(beforeStop: (Boolean) -> Unit) = recorder.pauseForUpdate(beforeStop)

    fun resumeAfterUpdate() {
        recorder.resumeAfterUpdate()
        forget()
    }

    fun count(): JSONObject {
        val payload = payload()
        val count = JSONObject()
        count.put("running", payload.getInt("running"))
        count.put("total", payload.getInt("total"))
        count.put("health", payload.getString("health"))
        return count
    }

    // Share shell-backed card reads across dashboard requests.
    private fun payload(): JSONObject = synchronized(cardsLock) {
        val now = System.currentTimeMillis()
        val held = cards
        if (held != null && now - cardsAtMs < CARDS_CACHE_MS) return held
        val built = buildCards()
        cards = built
        cardsAtMs = now
        built
    }

    private fun buildCards(): JSONObject {
        val cards = JSONArray()
        cards.put(shellCard())
        cards.put(recorderCard())
        cards.put(surveillanceCard())
        val tunnel = online.status()
        cards.put(tunnelCard(tunnel))

        var running = 0
        for (i in 0 until cards.length()) {
            if (cards.getJSONObject(i).getString("state") == RUNNING) running++
        }
        val payload = JSONObject()
        payload.put("running", running)
        payload.put("total", cards.length())
        payload.put("health", health(running, cards.length()))
        payload.put("cards", cards)
        return payload
    }

    fun connect(): Response {
        val payload = JSONObject()
        payload.put("authorised", shell.retry())
        forget()
        return Response(200, JSON, payload.toString().toByteArray())
    }

    fun logs(): Response {
        val merged = ArrayList<LogLine>(Logs.recent())
        val tail = shell.read("tail -n $LOG_TAIL_LINES $CAM_LOG_PATH")
        if (tail != null) merged.addAll(parseDaemonLog(tail))
        merged.sortBy { it.atMs }

        val lines = JSONArray()
        for (line in merged) {
            val row = JSONObject()
            row.put("atMs", line.atMs)
            row.put("level", line.level)
            row.put("tag", line.tag)
            row.put("message", line.message)
            lines.put(row)
        }
        val payload = JSONObject()
        payload.put("lines", lines)
        return Response(200, JSON, payload.toString().toByteArray())
    }

    fun setByd(app: String, body: String): Response {
        val name = when (app) {
            "dashcam" -> BydApps.DASHCAM
            "trafficMonitor" -> BydApps.TRAFFIC_MONITOR
            else -> return notFound()
        }
        val wanted = formValue(body, "enabled") ?: return Response(400, TEXT, "No state given".toByteArray())
        if (!bydApps.setEnabled(name, wanted == "true")) {
            return Response(503, TEXT, "Strike has no shell access, so it cannot change that".toByteArray())
        }
        forget()
        return Response(200, JSON, "{}".toByteArray())
    }

    // Process state and recording mode are separate controls.
    fun setRecorder(body: String): Response {
        val wanted = formValue(body, "enabled") ?: return Response(400, TEXT, "No state given".toByteArray())
        if (wanted == "true") {
            recorder.start()
        } else {
            recorder.stop()
        }
        forget()
        return Response(200, JSON, "{}".toByteArray())
    }

    private fun forget() = synchronized(cardsLock) {
        cards = null
        processes.forget()
    }

    private fun tunnelCard(status: JSONObject): JSONObject {
        val state = status.getString("state")
        val configured = status.getBoolean("hasToken")
        val card = card("cloudflare", "Cloudflare tunnel",
            if (state in setOf(RUNNING, STARTING, BROKEN)) state else OFF)
        card.put("status", if (configured) status.getString("status") else "Set up in Online")
        card.put("action", "switch")
        card.put("path", "/api/online")
        card.put("on", status.getBoolean("enabled"))
        card.put("can", state != "stopping" && (status.getBoolean("enabled") ||
            (configured && status.getBoolean("accessReady"))))
        val facts = JSONArray()
        fact(facts, "Hostname", status.getString("hostname").ifEmpty { DASH })
        fact(facts, "Runs", when (status.getString("mode")) {
            "always" -> "Whenever Strike is running"
            "lock" -> "When the car is off and locked"
            else -> "When the car is off"
        })
        card.put("facts", facts)
        return card
    }

    private fun shellCard(): JSONObject {
        val authorised = shell.isAuthorised()
        val pending = !authorised && shell.isPending
        val card = card("shell", "Shell access", when {
            authorised -> RUNNING
            pending -> STARTING
            else -> BROKEN
        })
        card.put("status", when {
            authorised -> "Authorised"
            pending -> "Waiting for debugging approval"
            else -> "Not authorised"
        })
        card.put("action", if (authorised) "none" else "connect")
        val facts = JSONArray()
        fact(facts, "Endpoint", "127.0.0.1:5555")
        fact(facts, "Runs as", if (authorised) "shell, uid 2000" else DASH)
        if (!authorised) fact(facts, "Fix", "Accept the debugging prompt on the head unit")
        card.put("facts", facts)
        return card
    }

    private fun recorderCard(): JSONObject {
        val status = recorder.status()
        val phase = recorder.phase
        val recovering = status == null && phase == Phase.OFF && processes.isRunning(CAM_SCRIPT_PATH) == true
        val uptimeMs = status?.optLong("uptimeMs")
        val card = card(
            "recorder", "Recorder",
            when (phase) {
                Phase.RUNNING -> RUNNING
                Phase.STARTING, Phase.STOPPING -> STARTING
                Phase.FAILED -> BROKEN
                Phase.OFF -> if (recovering) STARTING else OFF
            }
        )
        card.put(
            "status",
            when (phase) {
                Phase.RUNNING -> "Running"
                Phase.STARTING, Phase.STOPPING -> recorder.step
                Phase.FAILED -> "Failed to start"
                Phase.OFF -> if (recovering) "Waiting for restart" else "Stopped"
            }
        )
        if (uptimeMs != null) card.put("uptimeMs", uptimeMs)
        card.put("action", "switch")
        card.put("path", RECORDER_API)
        card.put("on", recorder.canStop || recovering)
        card.put("can", shell.isAuthorised() && phase != Phase.STOPPING)

        val facts = JSONArray()
        if (phase == Phase.FAILED) fact(facts, "Reason", recorder.failure)
        fact(facts, "Recording", recordingOf(status))
        fact(facts, "Camera", cameraOf(status))
        fact(facts, "Cameras found", camerasOf(status))
        fact(facts, "Mode", MODES[setting(RecordingSettings.MODE)] ?: DASH)
        fact(facts, "Clip length", setting(RecordingSettings.CLIP_LENGTH_MINUTES) + " min")
        fact(facts, "Quality", QUALITIES[setting(RecordingSettings.QUALITY)] ?: DASH)
        fact(facts, "Frame rate", setting(RecordingSettings.FRAME_RATE_FPS) + " fps")
        fact(facts, "Writing to", place())
        fact(facts, "Clips this run", status?.optInt("clips")?.toString() ?: DASH)
        card.put("facts", facts)
        return card
    }

    // Surveillance runs inside CameraDaemon; it has no separate process.
    private fun surveillanceCard(): JSONObject {
        val enabled = Config.getBool(SurveillanceSettings.ENABLED, false)
        val status = recorder.status()
        val card = card(
            "surveillance", "Surveillance",
            when {
                !enabled -> OFF
                status == null -> BROKEN
                status.isNull("accOn") && !status.optBoolean("armed") &&
                    status.optString("writing") != "watch" -> STARTING
                else -> RUNNING
            }
        )
        card.put(
            "status",
            when {
                !enabled -> "Off"
                status == null -> "The camera daemon is not running"
                status.optBoolean("armed") -> "Armed"
                status.optString("writing") == "watch" -> "Recording the parked tape"
                status.optString("sentryReason").isNotEmpty() -> status.optString("sentryReason")
                surveillanceSetting(SurveillanceSettings.ARM) == "lock" ->
                    "Standing by until the doors lock"
                else -> "Standing by until the car switches off"
            }
        )
        card.put("action", "none")

        val facts = JSONArray()
        val mode = surveillanceSetting(SurveillanceSettings.MODE)
        fact(facts, "Mode", SENTRY_MODES[mode] ?: DASH)
        fact(
            facts, "Starts",
            if (surveillanceSetting(SurveillanceSettings.ARM) == "lock") "On lock" else "On turn off"
        )
        if (mode == "smart") {
            fact(facts, "Proximity", surveillanceSetting(SurveillanceSettings.PROXIMITY) + " of 5")
            fact(facts, "Confirms with", confirmsWith(status))
        }
        fact(
            facts, "Screen message",
            if (Config.getBool(SurveillanceSettings.SCREEN, false)) "On" else "Off"
        )
        fact(facts, "Events this run", status?.optInt("events")?.toString() ?: DASH)
        fact(facts, "Writing to", eventsPlace())
        card.put("facts", facts)
        return card
    }

    private fun confirmsWith(status: JSONObject?): String = when {
        status == null -> DASH
        !status.optBoolean("armed") -> DASH
        status.optBoolean("confirming") -> "Person and vehicle boxes"
        else -> "Movement only, the detector did not load"
    }

    private fun eventsPlace(): String {
        val name = PLACES[events.location()] ?: return DASH
        return if (events.selected() != null) name else "$name, not mounted"
    }

    private fun surveillanceSetting(key: String): String =
        Config.getString(key, SurveillanceSettings.fallback(key))

    private fun recordingOf(status: JSONObject?): String {
        if (status == null) return DASH
        val clip = status.optString("clip")
        if (!status.optBoolean("recording") || clip.isEmpty() || clip == "null") return "Idle"
        return "$clip, ${formatUptime(status.optLong("clipMs"))}"
    }

    private fun cameraOf(status: JSONObject?): String {
        val camera = status?.optJSONObject("camera")
        if (camera != null) {
            return "Open, ${camera.optInt("width")} x ${camera.optInt("height")}"
        }
        val reason = status?.optString("camerasReason").orEmpty()
        if (reason.isNotEmpty()) return reason
        val factoryDashcam = bydApps.state(BydApps.DASHCAM) == BydApps.ENABLED ||
            processes.isRunning(BydApps.DASHCAM) == true
        return if (factoryDashcam) "Held by the factory dashcam" else DASH
    }

    fun recording(): JSONObject {
        val status = recorder.status()
        val state = JSONObject()
        state.put("on", status != null && status.optBoolean("recording"))
        state.put("clip", status?.opt("clip") ?: JSONObject.NULL)
        return state
    }

    fun cameras(): Response {
        val status = recorder.status()
        val payload = JSONObject()
        payload.put("cameras", status?.optJSONArray("cameras") ?: JSONArray())
        if (status == null) {
            payload.put("reason", "Start the recorder daemon and the camera becomes available.")
        } else {
            val reason = status.optString("camerasReason")
            if (reason.isNotEmpty()) payload.put("reason", reason)
        }
        return Response(200, JSON, payload.toString().toByteArray())
    }

    private fun camerasOf(status: JSONObject?): String {
        val list = status?.optJSONArray("cameras") ?: return DASH
        if (list.length() == 0) return status.optString("camerasReason", "None")
        val names = ArrayList<String>(list.length())
        for (i in 0 until list.length()) {
            val camera = list.getJSONObject(i)
            names.add("${camera.optString("tag")} ${camera.optInt("width")}x${camera.optInt("height")}")
        }
        return names.joinToString(", ")
    }

    private fun setting(key: String): String = Config.getString(key, RecordingSettings.fallback(key))

    private fun place(): String {
        val name = PLACES[storage.location()] ?: return DASH
        return if (storage.selected() != null) name else "$name, not mounted"
    }

    private fun card(id: String, name: String, state: String): JSONObject {
        val card = JSONObject()
        card.put("id", id)
        card.put("name", name)
        card.put("state", state)
        return card
    }

    private fun fact(facts: JSONArray, label: String, value: String) {
        val row = JSONObject()
        row.put("label", label)
        row.put("value", value)
        facts.put(row)
    }
}

internal fun health(running: Int, total: Int): String = when {
    total > 0 && running == total -> "ok"
    running == 0 -> "bad"
    else -> "warn"
}

internal fun formatUptime(elapsedMs: Long): String {
    val totalSeconds = elapsedMs / 1_000L
    val days = totalSeconds / 86_400L
    val hours = totalSeconds % 86_400L / 3_600L
    val minutes = totalSeconds % 3_600L / 60L
    val seconds = totalSeconds % 60L
    val clock = String.format("%d:%02d:%02d", hours, minutes, seconds)
    return if (days > 0) "${days}d $clock" else clock
}
