package com.strike.online

import com.strike.core.Pin
import com.strike.server.BrowserGate
import com.strike.server.api.OnlineApi
import com.strike.vehicle.VehicleSnapshot
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.createTempDirectory

class OnlineTest {
    @Test fun alwaysModeSurvivesParkingAndStillReportsTheNetworkWhenItDrops() {
        val reading = AtomicReference<VehicleSnapshot?>(car(true))
        val fixture = Fixture(readVehicle = { reading.get() })
        try {
            fixture.network = "mobile"
            fixture.online.enable(true)
            val process = fixture.launched.poll(5, TimeUnit.SECONDS)!!
            fixture.ready = true
            fixture.online.vehicle(car(true))
            fixture.await("Connected")
            assertTrue(fixture.awake)
            assertFalse(fixture.parked)

            fixture.powers.clear()
            fixture.now.addAndGet(11_000L)
            reading.set(car(false))
            fixture.online.acc(false)
            fixture.awaitPower(true, true, canAcquire = true)
            assertTrue(fixture.parked)
            assertTrue(fixture.canAcquireParked)
            assertTrue(process.isAlive)
            assertEquals(0, fixture.launched.size)

            fixture.powers.clear()
            reading.set(null)
            fixture.now.addAndGet(21_000L)
            fixture.online.vehicle(null)
            fixture.awaitPower(true, true, canAcquire = false)
            assertTrue(fixture.parked)
            assertFalse(fixture.canAcquireParked)
            assertTrue(process.isAlive)
            assertEquals(0, fixture.launched.size)

            fixture.internet = false
            fixture.online.vehicle(null)
            fixture.await("Waiting for internet")
            assertFalse(process.isAlive)
            assertTrue(fixture.awake)
            assertTrue(fixture.parked)

            fixture.internet = true
            fixture.online.vehicle(null)
            val replacement = fixture.launched.poll(5, TimeUnit.SECONDS)!!
            fixture.await("Connecting to Cloudflare")
            assertNotSame(process, replacement)
            assertTrue(replacement.isAlive)
            assertTrue(fixture.awake)
            assertTrue(fixture.parked)
            assertEquals(0, fixture.launched.size)

            fixture.powers.clear()
            reading.set(VehicleSnapshot(null, null, null, null, null, "D", null, null))
            fixture.online.vehicle(null)
            fixture.awaitPower(true, false)
            assertTrue(replacement.isAlive)

            fixture.powers.clear()
            reading.set(car(false))
            fixture.online.acc(false)
            fixture.awaitPower(true, true)
            fixture.powers.clear()
            reading.set(car(true))
            fixture.online.acc(true)
            fixture.awaitPower(true, false)
        } finally { fixture.close() }
        assertFalse(fixture.awake)
        assertFalse(fixture.parked)
    }

    @Test fun confirmedParkingAcquiresPowerBeforeMobileInternetReturns() {
        val fixture = Fixture()
        try {
            fixture.internet = false
            fixture.online.acc(false)
            fixture.online.enable(true)
            fixture.await("Waiting for internet")
            assertTrue(fixture.awake)
            assertTrue(fixture.parked)
            assertEquals(0, fixture.launched.size)
        } finally { fixture.close() }
        assertFalse(fixture.awake)
        assertFalse(fixture.parked)
    }

    @Test fun disablingOrIgnitionOnCancelsParkedPowerDuringHardwareSetup() {
        for (disable in listOf(true, false)) {
            val entered = CountDownLatch(1)
            val proceed = CountDownLatch(1)
            val wanted = LinkedBlockingQueue<Boolean>()
            val fixture = Fixture(mode = "off", powerRead = { cpu, parked, stillWanted ->
                if (cpu && parked) {
                    entered.countDown()
                    check(proceed.await(5, TimeUnit.SECONDS))
                    wanted.offer(stillWanted())
                }
                true
            })
            try {
                fixture.online.acc(false)
                fixture.online.enable(true)
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                if (disable) fixture.online.enable(false) else fixture.online.acc(true)
                proceed.countDown()
                assertEquals(false, wanted.poll(5, TimeUnit.SECONDS))
                fixture.await(if (disable) "Off" else "Standing by until the car switches off")
                assertEquals(0, fixture.launched.size)
            } finally {
                proceed.countDown()
                fixture.online.close()
            }
        }
    }

    @Test fun aPendingDisplayReleaseFinishesOnWakeBeforeReportingOff() {
        for (close in listOf(true, false)) {
            val releaseAllowed = AtomicBoolean(false)
            val fixture = Fixture(powerRead = { cpu, parked, _ ->
                cpu || parked || releaseAllowed.get()
            })
            try {
                fixture.online.acc(false)
                fixture.online.enable(true)
                val process = fixture.launched.poll(5, TimeUnit.SECONDS)!!
                fixture.online.enable(false)
                fixture.await("Releasing the parked display")
                assertFalse(process.isAlive)
                assertTrue(fixture.awake)
                assertTrue(fixture.parked)
                assertEquals("stopping", fixture.online.status().getString("state"))
                releaseAllowed.set(true)
                if (close) fixture.online.close() else fixture.online.acc(true)
                fixture.await("Off")
                assertFalse(fixture.awake)
                assertFalse(fixture.parked)
            } finally {
                releaseAllowed.set(true)
                fixture.online.close()
            }
        }
    }

    @Test fun parkedModesReadIgnitionAndLockWithoutTheAndroidApp() {
        for (mode in listOf("off", "lock")) {
            val reading = AtomicReference(car(true, false))
            val fixture = Fixture(mode = mode, readVehicle = { reading.get() })
            try {
                fixture.online.enable(true)
                fixture.await("Standing by until the car switches off")
                reading.set(car(false, false))
                fixture.now.addAndGet(3_000L)
                fixture.online.retry()
                // First OFF sample starts confirmation; a later sample must agree.
                assertNotNull(fixture.polls.poll(5, TimeUnit.SECONDS))
                assertNotNull(fixture.polls.poll(5, TimeUnit.SECONDS))
                fixture.now.addAndGet(3_000L)
                fixture.online.retry()
                if (mode == "lock") {
                    fixture.await("Standing by until the doors lock")
                    assertEquals(0, fixture.launched.size)
                    reading.set(car(false, true))
                    fixture.online.retry()
                }
                val process = fixture.launched.poll(5, TimeUnit.SECONDS)!!
                assertTrue(fixture.parked)
                reading.set(car(true, false))
                fixture.online.retry()
                fixture.await("Standing by until the car switches off")
                assertFalse(process.isAlive)
                assertFalse(fixture.parked)
            } finally { fixture.close() }
        }
    }

    @Test fun shuttingDownTheHostStopsItsChildWithoutDisablingSavedRemoteAccess() {
        val fixture = Fixture()
        fixture.online.acc(false)
        fixture.online.enable(true)
        val process = fixture.launched.poll(5, TimeUnit.SECONDS)!!
        assertTrue(fixture.parked)
        fixture.online.close()
        assertFalse(process.isAlive)
        assertFalse(fixture.awake)
        assertFalse(fixture.parked)
        assertTrue(fixture.online.enabled)
        fixture.online.start()
        assertEquals(0, fixture.launched.size)
    }

    @Test fun originErrorsAreLoggedOnceEvenWhileCloudflareRemainsConnected() {
        val fixture = Fixture()
        try {
            fixture.online.enable(true)
            val process = fixture.launched.poll(5, TimeUnit.SECONDS)!!
            fixture.ready = true
            fixture.online.vehicle(car(true))
            fixture.await("Connected")
            repeat(3) { process.error("dial tcp 127.0.0.1:8090: connect: connection refused") }
            process.error("dial tcp 127.0.0.1:8090: i/o timeout")
            fixture.await("Strike's dashboard refused the tunnel connection")
            assertEquals("Strike's dashboard did not answer the tunnel", fixture.states.poll(5, TimeUnit.SECONDS))
            assertTrue(process.isAlive)
            assertEquals("running", fixture.online.status().getString("state"))
        } finally { fixture.close() }
    }

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
                fixture.await(if (attempt == 4) "Remote access exited with 7. Press Retry"
                    else "Remote access exited with 7. Retrying shortly")
                if (attempt == 4) fixture.awaitPower(false, false)
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
        fixture.await("Remote access exited with 7. Retrying shortly")
        fixture.online.enable(false)
        fixture.await("Off")
        fixture.now.addAndGet(60_000L)
        fixture.online.vehicle(car(false, true))
        fixture.online.start()
        assertEquals(0, fixture.launched.size)
    }

    @Test fun aNetworkChangeRecoversAnExhaustedTunnelWithoutManualRetry() {
        val fixture = Fixture()
        try {
            fixture.online.enable(true)
            repeat(5) { attempt ->
                fixture.launched.poll(5, TimeUnit.SECONDS)!!.crash()
                fixture.online.vehicle(car(true))
                fixture.await(if (attempt == 4) "Remote access exited with 7. Press Retry"
                    else "Remote access exited with 7. Retrying shortly")
                fixture.now.addAndGet(60_000L)
                fixture.online.vehicle(car(true))
            }
            fixture.internet = false
            fixture.online.vehicle(car(true))
            fixture.await("Waiting for internet")
            fixture.internet = true
            fixture.online.vehicle(car(true))
            assertNotNull(fixture.launched.poll(5, TimeUnit.SECONDS))
            fixture.online.start()
            assertEquals(0, fixture.launched.size)
        } finally { fixture.close() }
    }

    @Test fun losingInternetKeepsPowerForRecoveryUntilTheCarSwitchesOn() {
        val fixture = Fixture(mode = "off")
        try {
            fixture.online.enable(true)
            fixture.await("Waiting for ignition status")
            assertFalse(fixture.awake)
            fixture.online.acc(false)
            assertNotNull(fixture.launched.poll(5, TimeUnit.SECONDS))
            assertTrue(fixture.awake)
            assertTrue(fixture.parked)
            fixture.internet = false
            fixture.online.vehicle(car(false))
            fixture.await("Waiting for internet")
            assertTrue(fixture.awake)
            assertTrue(fixture.parked)
            fixture.internet = true
            fixture.online.vehicle(car(false))
            assertNotNull(fixture.launched.poll(5, TimeUnit.SECONDS))
            assertTrue(fixture.awake)
            fixture.online.acc(true)
            fixture.await("Standing by until the car switches off")
            assertFalse(fixture.awake)
            assertFalse(fixture.parked)
        } finally { fixture.close() }
        assertFalse(fixture.awake)
    }

    @Test fun anOffBroadcastIsNotLostIfTheNextReadingIsMissingDuringANetworkCheck() {
        val preparing = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val fixture = Fixture(mode = "off", networkRead = {
            preparing.countDown()
            check(proceed.await(5, TimeUnit.SECONDS))
        })
        try {
            fixture.online.enable(true)
            assertTrue(preparing.await(5, TimeUnit.SECONDS))
            fixture.online.acc(false)
            fixture.online.vehicle(null)
            proceed.countDown()
            val process = fixture.launched.poll(5, TimeUnit.SECONDS)!!
            assertTrue(process.isAlive)
            fixture.online.acc(true)
            fixture.await("Standing by until the car switches off")
            assertFalse(process.isAlive)
        } finally { proceed.countDown(); fixture.close() }
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

    @Test fun ignitionOnDuringANetworkCheckCancelsThePendingLaunch() {
        val checking = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val fixture = Fixture(mode = "off", networkRead = {
            checking.countDown()
            check(proceed.await(5, TimeUnit.SECONDS))
        })
        try {
            fixture.online.acc(false)
            fixture.online.enable(true)
            assertTrue(checking.await(5, TimeUnit.SECONDS))
            fixture.online.acc(true)
            proceed.countDown()
            fixture.await("Standing by until the car switches off")
            assertEquals(0, fixture.launched.size)
            assertFalse(fixture.awake)
        } finally { proceed.countDown(); fixture.close() }
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

    @Test fun anEstablishedParkedTunnelSurvivesSleepingTelemetryUntilIgnitionOn() {
        val fixture = Fixture(mode = "off")
        try {
            fixture.online.enable(true)
            fixture.online.acc(false)
            val process = fixture.launched.poll(5, TimeUnit.SECONDS)!!
            fixture.now.addAndGet(21_000L)
            fixture.ready = true
            fixture.online.vehicle(null)
            fixture.await("Connected")
            assertTrue(process.isAlive)
            fixture.online.acc(true)
            fixture.await("Standing by until the car switches off")
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
        assertTrue(payload.getBoolean("configured"))
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
        for (action in listOf("toggle&enabled=true", "save&method=cloudflare&name=other.example.com",
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

    private class Fixture(pinSet: Boolean = true, mode: String = "always",
                          networkRead: () -> Unit = {}, readVehicle: (() -> VehicleSnapshot?)? = null,
                          powerRead: (Boolean, Boolean, () -> Boolean) -> Boolean = { _, _, _ -> true }) {
        private val directory = createTempDirectory().toFile()
        val pin = Pin(File(directory, "pin.json"), File(directory, "reset"))
        val browsers = BrowserGate(BrowserAccess(File(directory, "browser-access.json")))
        val now = AtomicLong(100_000L)
        val launched = LinkedBlockingQueue<Child>()
        val states = LinkedBlockingQueue<String>()
        val polls = LinkedBlockingQueue<Boolean>()
        val powers = LinkedBlockingQueue<Triple<Boolean, Boolean, Boolean>>()
        @Volatile var ready = false
        @Volatile var internet = true
        @Volatile var network = "wifi"
        @Volatile var accessReady = true
        @Volatile var awake = false
        @Volatile var parked = false
        @Volatile var canAcquireParked = false
        val online: Online

        private val method = object : RemoteMethod {
            override val connecting = "Connecting to Cloudflare"
            override val problem: String? = null
            override fun prepare() = Unit
            override fun start(): Process = Child().also { child -> launched.offer(child) }
            override fun ready(): Boolean = this@Fixture.ready
            override fun failure(line: String): String? = tunnelError(line, sampleTunnelToken())
            override fun address(): String = "https://car.example.com/"
        }

        init {
            if (pinSet) pin.set("2580")
            val saved = OnlineSettings(File(directory, "online.json"))
            saved.configure(CLOUDFLARE, "car.example.com", sampleTunnelToken(), mode)
            online = Online(saved, { accessReady }, mapOf(CLOUDFLARE to method),
                { networkRead(); polls.offer(true); if (internet) network else null },
                { now.get() }, { _, text -> states.offer(text) },
                keepAwake = { cpu, hardware, stillWanted ->
                    val completed = powerRead(cpu, hardware, stillWanted)
                    canAcquireParked = hardware && stillWanted()
                    if (completed) {
                        awake = cpu
                        parked = hardware
                    }
                    powers.offer(Triple(cpu, hardware, canAcquireParked))
                    completed
                }, readVehicle = readVehicle)
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

        fun awaitPower(cpu: Boolean, hardware: Boolean, canAcquire: Boolean? = null) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
            while (true) {
                val remaining = deadline - System.nanoTime()
                val found = powers.poll(maxOf(0L, remaining), TimeUnit.NANOSECONDS)
                assertNotNull("Expected power $cpu/$hardware; got $awake/$parked", found)
                if (found!!.first == cpu && found.second == hardware &&
                    (canAcquire == null || found.third == canAcquire)) return
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
        fun error(reason: String) {
            output.write((JSONObject().put("level", "error").put("error", reason).toString() + "\n").toByteArray())
            output.flush()
        }
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
