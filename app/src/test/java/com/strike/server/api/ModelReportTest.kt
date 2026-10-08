package com.strike.server.api

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

class ModelReportTest {

    private val getprop = """
        [ro.product.model]: [Sealion 7]
        [ro.build.fingerprint]: [BYD/di5/sealion7:12/SQ1A/1:user/release-keys]
        [ro.boot.serialno]: [HU12345678]
        [persist.byd.vin]: [LGXCE4CB0P0000001]
        [persist.sys.byd.wifi.mac]: [02:00:00:aa:bb:cc]
        [persist.sys.timezone]: [Europe/Amsterdam]
    """.trimIndent()

    private val cameraReply = JSONObject()
        .put("status", "ok").put("profile", "Automatic")
        .put("cameras", JSONArray().put(JSONObject().put("tag", "pano_h").put("id", 1).put("width", 5120).put("height", 960)))
        .put("probed", true).put("road", "pano_h id=1 5120x960").put("stack", "BmmCameraInfo=true")

    @Test
    fun identifyingPropertiesNeverReachTheReport() {
        val report = entries(built()).getValue("report.txt")

        assertTrue(report.contains("ro.product.model: Sealion 7"))
        assertTrue(report.contains("ro.build.fingerprint"))
        for (secret in listOf("HU12345678", "LGXCE4CB0P0000001", "02:00:00:aa:bb:cc", "Europe/Amsterdam")) {
            assertFalse(secret, report.contains(secret))
        }
    }

    @Test
    fun reportIsBuiltWithoutShellAccess() {
        val report = entries(built(shell = { null }, camera = { null })).getValue("report.txt")

        assertTrue(report.contains("Labels: Shell access not authorised"))
        assertTrue(report.contains("Packages: Shell access not authorised"))
        assertTrue(report.contains("Recorder: Not running"))
        assertTrue(report.contains("ro.product.model: Sealion 7"))
    }

    @Test
    fun zipHoldsTheReportAndTheLog() {
        val report = built()
        val file = report.file()

        assertEquals(200, file.status)
        assertEquals("application/zip", file.contentType)
        assertTrue(file.headers.getValue("Content-Disposition").contains("strike-model-report-sealion-7-"))
        val entries = entries(report)
        assertEquals(setOf("report.txt", "strike-log.txt"), entries.keys)
        assertEquals("the whole strike log", entries.getValue("strike-log.txt"))
        assertTrue(entries.getValue("report.txt").contains("pano_h: id=1 5120x960"))
    }

    @Test
    fun progressOnlyMovesForward() {
        val seen = ArrayList<Int>()
        lateinit var report: ModelReport
        fun record() { seen.add(status(report).getInt("step")) }
        report = ModelReport("0.7", { record(); "out" }, { record(); cameraReply }, { record(); "log" },
            { _, _ -> null }, props = { record(); getprop }, worker = { it.run() })

        report.start()

        assertEquals(seen.sorted(), seen)
        assertEquals(0, seen.first())
        assertEquals(6, status(report).getInt("step"))
        assertFalse(status(report).getBoolean("running"))
    }

    @Test
    fun aSecondCreateJoinsTheRunningReport() {
        val queued = ArrayList<Runnable>()
        val report = ModelReport("0.7", { "out" }, { cameraReply }, { "log" }, { _, _ -> null },
            props = { getprop }, worker = { queued.add(it) })

        report.start()
        report.start()

        assertEquals(1, queued.size)
        assertTrue(status(report).getBoolean("running"))
        assertEquals(404, report.file().status)
        queued.single().run()
        assertEquals(200, report.file().status)
    }

    private fun built(
        shell: (String) -> String? = { "out" },
        camera: () -> JSONObject? = { cameraReply }
    ): ModelReport = ModelReport("0.7", shell, camera, { "the whole strike log" }, { _, _ -> null },
        props = { getprop }, worker = { it.run() }).also { it.start() }

    private fun status(report: ModelReport) = JSONObject(String(report.status().body))

    private fun entries(report: ModelReport): Map<String, String> {
        val found = HashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(report.file().body)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                found[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        return found
    }
}
