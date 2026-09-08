package com.strike.online

import com.strike.core.Pin
import com.strike.server.BrowserGate
import com.strike.server.api.OnlineApi
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.createTempDirectory

class OnlineTest {
    @Test fun anImmediateExitReportsItsErrorAndTheNextProcessStartsWithoutIt() {
        val fixture = Fixture()
        try {
            fixture.online.enable(true)
            val process = fixture.launched.poll(5, TimeUnit.SECONDS)!!
            process.crash(JSONObject().put("level", "error")
                .put("error", "listen tcp 127.0.0.1:19889: bind: address already in use").toString())
            fixture.online.vehicle(car(true))
            fixture.await("The tunnel's local port is already in use. Retrying shortly")
            fixture.now.addAndGet(60_000L)
            fixture.online.vehicle(car(true))
            assertNotNull(fixture.launched.poll(5, TimeUnit.SECONDS))
            fixture.await("Connecting to Cloudflare")
        } finally { fixture.close() }
    }

    @Test fun repeatedCrashesStopUntilAnExplicitRetry() {
        val fixture = Fixture()
        try {
            fixture.online.enable(true)
            repeat(5) { attempt ->
                val process = fixture.launched.poll(5, TimeUnit.SECONDS)!!
                process.crash()
                fixture.online.vehicle(car(true))
                fixture.await(if (attempt == 4) "Tunnel exited with 7. Press Retry"
                    else "Tunnel exited with 7. Retrying shortly")
                fixture.now.addAndGet(60_000L)
                fixture.online.vehicle(car(true))
            }
            assertEquals("broken", fixture.online.status().getString("state"))
            fixture.online.start()
            assertEquals(0, fixture.launched.size)
            fixture.online.retry()
            assertNotNull(fixture.launched.poll(5, TimeUnit.SECONDS))
        } finally { fixture.close() }
    }

    @Test fun stoppingDuringBackoffCancelsThePendingRestart() {
        val fixture = Fixture()
        fixture.online.enable(true)
        fixture.launched.poll(5, TimeUnit.SECONDS)!!.crash()
        fixture.online.vehicle(car(true))
        fixture.await("Tunnel exited with 7. Retrying shortly")
        fixture.online.enable(false)
        fixture.await("Off")
        fixture.now.addAndGet(60_000L)
        fixture.online.vehicle(car(false, true))
        fixture.online.start()
        assertEquals(0, fixture.launched.size)
    }

    @Test fun disabledStartupCreatesNoTunnelAndEnablingDoesNotNeedTheCarPin() {
        val fixture = Fixture(pinSet = false)
        try {
            fixture.online.start()
            assertEquals(0, fixture.launched.size)
            fixture.online.enable(true)
            assertNotNull(fixture.launched.poll(5, TimeUnit.SECONDS))
        } finally { fixture.close() }
    }

    @Test fun unavailableBrowserCredentialsKeepTheTunnelOff() {
        val fixture = Fixture()
        fixture.accessReady = false
        try { fixture.online.enable(true); fail() }
        catch (e: IllegalStateException) { assertFalse(fixture.online.enabled) }
    }

    @Test fun stopTerminatesTheProcessAndPreventsVehicleUpdatesRestartingIt() {
        val fixture = Fixture()
        fixture.online.enable(true)
        val process = fixture.launched.poll(5, TimeUnit.SECONDS)!!
        fixture.online.enable(false)
        fixture.await("Off")
        assertFalse(process.isAlive)
        fixture.online.vehicle(car(false, true))
        fixture.online.start()
        assertFalse(fixture.online.status().getBoolean("running"))
        assertEquals(0, fixture.launched.size)
    }

    @Test fun aRunningProcessIsNotReportedConnectedUntilReady() {
        val fixture = Fixture()
        try {
            fixture.online.enable(true)
            fixture.await("Connecting to Cloudflare")
            assertEquals("starting", fixture.online.status().getString("state"))
            fixture.ready = true
            fixture.online.vehicle(car(true))
            fixture.await("Connected")
            fixture.ready = false
            fixture.online.vehicle(car(true))
            fixture.await("Connecting to Cloudflare")
        } finally { fixture.close() }
    }

    @Test fun unlockingOrIgnitionOnStopsAnAutomaticTunnel() {
        val fixture = Fixture(mode = "lock")
        try {
            fixture.online.enable(true)
            fixture.await("Waiting for ignition status")
            fixture.online.vehicle(car(false, true))
            fixture.now.addAndGet(5_000L)
            fixture.online.vehicle(car(false, true))
            val process = fixture.launched.poll(5, TimeUnit.SECONDS)!!
            fixture.online.vehicle(car(false, false))
            fixture.await("Standing by until the doors lock")
            assertFalse(process.isAlive)
            fixture.online.vehicle(car(false, true))
            val next = fixture.launched.poll(5, TimeUnit.SECONDS)!!
            fixture.online.acc(true)
            fixture.await("Standing by until the car switches off")
            assertFalse(next.isAlive)
        } finally { fixture.close() }
    }

    @Test fun staleIgnitionStopsTheParkedTunnel() {
        val fixture = Fixture(mode = "off")
        try {
            fixture.online.enable(true)
            fixture.online.acc(false)
            val process = fixture.launched.poll(5, TimeUnit.SECONDS)!!
            fixture.now.addAndGet(21_000L)
            fixture.online.retry()
            fixture.await("Waiting for ignition status")
            assertFalse(process.isAlive)
        } finally { fixture.close() }
    }

    @Test fun losingInternetStopsTheProcessAndRecoveryStartsOneReplacement() {
        val fixture = Fixture()
        try {
            fixture.online.enable(true)
            val process = fixture.launched.poll(5, TimeUnit.SECONDS)!!
            fixture.internet = false
            fixture.online.vehicle(car(true))
            fixture.await("Waiting for internet")
            assertFalse(process.isAlive)
            fixture.internet = true
            fixture.online.vehicle(car(true))
            assertNotNull(fixture.launched.poll(5, TimeUnit.SECONDS))
            fixture.online.start()
            assertEquals(0, fixture.launched.size)
        } finally { fixture.close() }
    }

    @Test fun changingNetworksReplacesTheTunnelAndItsDnsSettings() {
        val fixture = Fixture()
        try {
            fixture.online.enable(true)
            val first = fixture.launched.poll(5, TimeUnit.SECONDS)!!
            fixture.network = "mobile"
            fixture.online.vehicle(car(true))
            val next = fixture.launched.poll(5, TimeUnit.SECONDS)!!
            assertFalse(first.isAlive)
            assertTrue(next.isAlive)
            fixture.ready = true
            fixture.online.vehicle(car(true))
            fixture.await("Connected")
            assertTrue(next.isAlive)
            assertEquals(0, fixture.launched.size)
        } finally { fixture.close() }
    }

    @Test fun pinRecoveryDoesNotStopTheTunnelOrBrowserSession() {
        val fixture = Fixture()
        try {
            val token = fixture.browsers.access.login(fixture.browsers.access.code()!!).token
            fixture.online.enable(true)
            val process = fixture.launched.poll(5, TimeUnit.SECONDS)!!
            fixture.pin.clear()
            fixture.ready = true
            fixture.online.vehicle(car(true))
            fixture.await("Connected")
            assertTrue(process.isAlive)
            assertTrue(fixture.browsers.access.allows(token))
        } finally { fixture.close() }
    }

    @Test fun statusAndErrorsNeverReturnTheTunnelToken() {
        val fixture = Fixture()
        val api = OnlineApi(fixture.online, fixture.browsers) { listOf("192.168.1.10") }
        val reply = api.status()
        val text = String(reply.body)
        assertFalse(text.contains(sampleTunnelToken()))
        assertFalse(text.contains(fixture.browsers.access.code()!!))
        val payload = JSONObject(text)
        assertTrue(payload.getBoolean("hasToken"))
        assertEquals("http://192.168.1.10:8090/", payload.getJSONArray("addresses").getString(0))
        assertEquals("Cloudflare rejected the token. Update the setup",
            tunnelError("Unauthorized: " + sampleTunnelToken()))
        assertTrue(String(api.status(true).body).contains(fixture.browsers.access.code()!!))
        assertEquals(403, api.update("action=regenerate").status)
    }

    @Test fun browsersCannotChangeTunnelSettingsOrTheirAccessCode() {
        val fixture = Fixture()
        val api = OnlineApi(fixture.online, fixture.browsers) { emptyList() }
        val before = String(api.status(true).body)
        for (action in listOf("toggle&enabled=true", "save&hostname=other.example.com",
            "forget", "retry", "regenerate")) {
            assertEquals(403, api.update("action=$action").status)
            assertEquals(before, String(api.status(true).body))
        }
    }

    @Test fun parkingAndWakingKeepTheAccessCodeAndRememberedBrowser() {
        val fixture = Fixture(mode = "off")
        val code = fixture.browsers.access.code()!!
        val token = fixture.browsers.access.login(code).token
        try {
            fixture.online.enable(true)
            fixture.online.acc(true)
            fixture.now.addAndGet(11_000L)
            fixture.online.acc(false)
            assertNotNull(fixture.launched.poll(5, TimeUnit.SECONDS))
            assertEquals(code, fixture.browsers.access.code())
            assertTrue(fixture.browsers.access.allows(token))
            assertNotNull(fixture.browsers.access.login(code).token)
            fixture.online.acc(true)
            assertEquals(code, fixture.browsers.access.code())
            assertTrue(fixture.browsers.access.allows(token))
        } finally { fixture.close() }
    }

    @Test fun codeRegenerationIsAvailableInTheCarAndRevokesBrowserSessions() {
        val fixture = Fixture()
        val code = fixture.browsers.access.code()!!
        val token = fixture.browsers.access.login(code).token
        val api = OnlineApi(fixture.online, fixture.browsers) { emptyList() }
        val reply = api.update("action=regenerate", true)
        assertEquals(200, reply.status)
        val access = JSONObject(String(reply.body)).getJSONObject("browserAccess")
        assertTrue(access.getBoolean("canManage"))
        assertNotEquals(code, access.getString("code"))
        assertFalse(fixture.browsers.access.allows(token))
    }

    private class Fixture(pinSet: Boolean = true, mode: String = "always") {
        private val directory = createTempDirectory().toFile()
        val pin = Pin(File(directory, "pin.json"), File(directory, "reset"))
        val browsers = BrowserGate(BrowserAccess(File(directory, "browser-access.json")))
        val now = AtomicLong(100_000L)
        val launched = LinkedBlockingQueue<Child>()
        private val states = LinkedBlockingQueue<String>()
        @Volatile var ready = false
        @Volatile var internet = true
        @Volatile var network = "wifi"
        @Volatile var accessReady = true
        val online: Online

        init {
            if (pinSet) pin.set("2580")
            val saved = TunnelSettings(File(directory, "online.json"))
            saved.configure("car.example.com", sampleTunnelToken(), mode)
            online = Online(saved, { accessReady }, {
                Child().also { child -> launched.offer(child) }
            }, { ready }, { if (internet) network else null }, { now.get() }, { _, text -> states.offer(text) })
        }

        fun await(wanted: String) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
            while (true) {
                val remaining = deadline - System.nanoTime()
                val found = states.poll(maxOf(0L, remaining), TimeUnit.NANOSECONDS)
                assertNotNull("Expected $wanted; got ${online.status()}", found)
                if (found == wanted) return
            }
        }

        fun close() {
            online.enable(false)
            await("Off")
        }
    }

    private class Child : Process() {
        private val output = PipedOutputStream()
        private val input = PipedInputStream(output)
        @Volatile private var alive = true
        @Volatile private var code = 0
        fun crash(reason: String = "") {
            if (reason.isNotEmpty()) output.write((reason + "\n").toByteArray())
            output.close()
            code = 7
            alive = false
        }
        override fun getInputStream() = input
        override fun getErrorStream() = ByteArrayInputStream(ByteArray(0))
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit) = !alive
        override fun exitValue(): Int {
            if (alive) throw IllegalThreadStateException()
            return code
        }
        override fun destroy() { output.close(); alive = false }
        override fun destroyForcibly(): Process { destroy(); return this }
        override fun isAlive() = alive
    }
}
