package com.strike.server.api

import com.strike.daemon.BMM_JAR_PATH
import com.strike.daemon.DILINK5_MARKER
import com.strike.daemon.STRIKE_DIR
import com.strike.server.JSON
import com.strike.server.Response
import com.strike.server.TEXT
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val DASH = "\u2014"
private const val NO_SHELL = "Shell access not authorised"
private val STEPS = listOf("Head unit", "Firmware", "BYD apps", "Cameras", "Strike log", "Zip")
private val KEPT = listOf("ro.product.", "ro.build.", "ro.board.", "ro.hardware", "ro.soc.", "ro.vendor.build.")
private val IDENTIFYING = listOf(
    "serial", "vin", "imei", "mac", "wifi", "bt", "bluetooth", "ssid", "iccid", "phone", "gsm"
)
private val PROPERTY = Regex("""^\[([^\]]+)]: \[(.*)]$""")

/** Facts for adding a model; nothing that identifies the car or its owner. */
class ModelReport(
    private val version: String,
    private val shell: (String) -> String?,
    private val camera: () -> JSONObject?,
    private val log: () -> String,
    private val save: (String, ByteArray) -> String?,
    private val props: () -> String? = ::systemProps,
    private val worker: Executor = Executor { Thread(it, "model-report").start() },
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    private val lock = Any()
    private var step = 0
    private var running = false
    private var zip: ByteArray? = null
    private var name = ""

    fun start(): Response {
        synchronized(lock) {
            if (!running) {
                running = true
                step = 0
                zip = null
                worker.execute(::build)
            }
        }
        return status()
    }

    fun status(): Response {
        val payload = JSONObject()
        synchronized(lock) {
            payload.put("running", running)
            payload.put("step", step)
            payload.put("of", STEPS.size)
            if (running) payload.put("label", STEPS[step])
            if (zip != null) payload.put("name", name)
        }
        return Response(200, JSON, payload.toString().toByteArray())
    }

    fun file(): Response {
        val (held, named) = synchronized(lock) { zip to name }
        if (held == null) return Response(404, TEXT, "Create a model report first".toByteArray())
        return Response(200, "application/zip", held,
            headers = mapOf("Content-Disposition" to "attachment; filename=\"$named\""))
    }

    fun save(): Response {
        val (held, named) = synchronized(lock) { zip to name }
        if (held == null) return Response(404, TEXT, "Create a model report first".toByteArray())
        val location = save(named, held)
            ?: return Response(507, TEXT, "No place to save the report. Check where recording writes".toByteArray())
        return Response(200, JSON, JSONObject().put("name", named).put("location", location).toString().toByteArray())
    }

    private fun build() {
        try {
            val report = StringBuilder()
            val properties = keptProperties(props())
            headUnit(report, properties)
            advance()
            firmware(report)
            advance()
            bydApps(report)
            advance()
            cameras(report)
            advance()
            val strikeLog = log()
            advance()
            val bytes = zipOf(report.toString(), strikeLog)
            val named = fileName(properties["ro.product.model"])
            synchronized(lock) {
                zip = bytes
                name = named
                step = STEPS.size
            }
        } finally {
            synchronized(lock) { running = false }
        }
    }

    private fun advance() = synchronized(lock) { step++ }

    private fun headUnit(report: StringBuilder, properties: Map<String, String>) {
        report.append("Head unit\n")
        line(report, "Strike", version)
        line(report, "Strike files", STRIKE_DIR)
        line(report, "Model", properties["ro.product.model"] ?: DASH)
        if (properties.isEmpty()) line(report, "Properties", DASH)
        for ((key, value) in properties) line(report, key, value)
    }

    private fun firmware(report: StringBuilder) {
        report.append("\nFirmware\n")
        line(report, DILINK5_MARKER, if (File(DILINK5_MARKER).exists()) "present" else "absent")
        line(report, BMM_JAR_PATH, if (File(BMM_JAR_PATH).exists()) "present" else "absent")
        line(report, "Labels", shell("ls -Zd /data/local/tmp /data/user_de/0/com.android.shell 2>&1") ?: NO_SHELL)
        line(report, "Shell writes /data/local/tmp", shell(
            "P=/data/local/tmp/.strike_probe_\$\$; if touch \$P 2>/dev/null; then rm -f \$P; echo yes; else echo no; fi"
        ) ?: NO_SHELL)
    }

    private fun bydApps(report: StringBuilder) {
        report.append("\nBYD apps\n")
        line(report, "Packages", shell("pm list packages 2>/dev/null | grep -i byd | sort") ?: NO_SHELL)
        line(report, "Autostart list", shell(
            "if dumpsys package com.byd.appstartmanagement | grep -q frame.AppStartManagement; " +
                "then echo present; else echo absent; fi"
        ) ?: NO_SHELL)
    }

    private fun cameras(report: StringBuilder) {
        report.append("\nCameras\n")
        val reply = camera()
        if (reply == null || reply.optString("status") != "ok") {
            line(report, "Recorder", "Not running, so the camera library was not asked")
            return
        }
        line(report, "Profile", reply.optString("profile"))
        val list = reply.optJSONArray("cameras")
        if (list == null || list.length() == 0) line(report, "Cameras", DASH)
        else for (i in 0 until list.length()) {
            val camera = list.getJSONObject(i)
            line(report, camera.optString("tag"),
                "id=${camera.optInt("id")} ${camera.optInt("width")}x${camera.optInt("height")}")
        }
        line(report, "Named by the camera library", when {
            !reply.optBoolean("probed") -> "Not asked, a profile is set"
            reply.has("probeReason") -> "No, " + reply.optString("probeReason")
            else -> "Yes"
        })
        line(report, "Road camera", reply.optString("road"))
        line(report, "Stack", reply.optString("stack"))
    }

    private fun fileName(model: String?): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(nowMs()))
        val slug = model.orEmpty().lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "-").trim('-')
        return if (slug.isEmpty()) "strike-model-report-$stamp.zip" else "strike-model-report-$slug-$stamp.zip"
    }
}

internal fun keptProperties(getprop: String?): Map<String, String> {
    val kept = sortedMapOf<String, String>()
    for (raw in getprop.orEmpty().lineSequence()) {
        val match = PROPERTY.matchEntire(raw.trim()) ?: continue
        val key = match.groupValues[1]
        val lower = key.lowercase(Locale.US)
        val wanted = KEPT.any { lower.startsWith(it) } || lower.contains("byd") || lower.contains("dilink")
        if (wanted && IDENTIFYING.none { lower.contains(it) }) kept[key] = match.groupValues[2]
    }
    return kept
}

private fun line(report: StringBuilder, label: String, value: String) {
    val lines = value.trim().ifEmpty { DASH }.lines()
    report.append(label).append(": ").append(lines.first()).append('\n')
    for (more in lines.drop(1)) report.append("    ").append(more).append('\n')
}

private fun zipOf(report: String, strikeLog: String): ByteArray {
    val bytes = ByteArrayOutputStream()
    ZipOutputStream(bytes).use { zip ->
        zip.putNextEntry(ZipEntry("report.txt"))
        zip.write(report.toByteArray())
        zip.closeEntry()
        zip.putNextEntry(ZipEntry("strike-log.txt"))
        zip.write(strikeLog.toByteArray())
        zip.closeEntry()
    }
    return bytes.toByteArray()
}

// System properties are world-readable, so this works before shell access is approved.
private fun systemProps(): String? = try {
    val process = ProcessBuilder("getprop").redirectErrorStream(true).start()
    process.inputStream.bufferedReader().use { it.readText() }.also { process.waitFor() }
} catch (e: IOException) {
    null
}
