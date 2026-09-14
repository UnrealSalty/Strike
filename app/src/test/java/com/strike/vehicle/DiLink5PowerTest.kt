package com.strike.vehicle

import com.strike.daemon.AccMonitor
import com.strike.recording.sentryMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class DiLink5PowerTest {
    @Test
    fun currentPowerModeWinsOverTheListOfAllPossibleStates() {
        val dump = """
            Power Mute State:
              All items: {0=PowerMode Off, 4=PowerMode Standby, 2=PowerMode StartUp}
              previous: {4=PowerMode Standby}
              current: {2=PowerMode StartUp}
        """.trimIndent()
        assertEquals(true, diLink5AccOnOf(dump))
    }

    @Test
    fun theSupportedCurrentPowerModesMapToTheirReportedIgnitionState() {
        for ((mode, name) in listOf(0 to "Off", 1 to "Pre StartUp", 4 to "Standby", 5 to "Str",
            8 to "Sleep", 9 to "Str Suspending", 12 to "Tod")) {
            assertEquals(false, diLink5AccOnOf(dump(mode, name)))
        }
        for ((mode, name) in listOf(2 to "StartUp", 3 to "Degraded", 10 to "DisPlay on")) {
            assertEquals(true, diLink5AccOnOf(dump(mode, name)))
        }
    }

    @Test
    fun aPowerModeOutsideItsCurrentSectionCannotEstablishIgnition() {
        for (dump in listOf(
            "current: {4=PowerMode Standby}",
            "Power Mute State:\nAll items: {4=PowerMode Standby}",
            "Power Mute State:\nprevious: {4=PowerMode Standby}",
            "Other State:\ncurrent: {4=PowerMode Standby}\nPower Mute State:",
            "Power Mute State:\n\n\n\ncurrent: {4=PowerMode Standby}",
            "Power Mute State:\nAll items: {current=4=PowerMode Standby}"
        )) assertNull(diLink5AccOnOf(dump))
    }

    @Test
    fun ambiguousOrUnrecognizedCurrentModesRemainUnknown() {
        for (dump in listOf(
            dump(14, "Standby"), dump(-4, "Standby"), dump(4, "Sleep"), dump(6, "Unknown"),
            dump(4, "Standby extended"), dump(255, "Standby"),
            "Power Mute State:\ncurrent: {4=PowerMode Standby, 2=PowerMode StartUp}",
            "Power Mute State:\ncurrent: {4=PowerMode Standby}\ncurrent: {2=PowerMode StartUp}",
            dump(4, "Standby") + "\n" + dump(2, "StartUp"),
            "Power Mute State:\n{4=PowerMode Standby}: not current"
        )) assertNull(diLink5AccOnOf(dump))
    }

    @Test
    fun aTimedOutOrUnavailableDumpCannotReuseItsLastOffReading() {
        assertEquals(false, diLink5AccOnOf(dump(4, "Standby")))
        for (dump in listOf(null, "", "Permission Denial: cannot dump car_service",
            dump(4, "Standby") + "\n*** DUMP TIMEOUT EXPIRED ***")) {
            assertNull(diLink5AccOnOf(dump))
        }
        assertEquals(true, diLink5AccOnOf(dump(2, "StartUp")))
    }

    @Test
    fun legacyIgnitionNeverStartsTheDiLink5Reader() {
        for (level in listOf(null, -1, 0, 1, 2, 3, 4, 255)) {
            assertEquals(accOnOf(level), ignitionOf(false, { accOnOf(level) }) {
                error("A legacy car must not request car_service")
            })
            assertEquals(polledAccOnOf(level), ignitionOf(false, { polledAccOnOf(level) }) {
                error("A legacy parked poll must not request car_service")
            })
        }
    }

    @Test
    fun diLink5DoesNotConsultTheLegacyHalEvenWhenItsPowerModeIsUnavailable() {
        for (on in listOf(true, false, null)) {
            assertEquals(on, ignitionOf(true, { error("The legacy HAL is not authoritative here") }, { on }))
        }
    }

    @Test
    fun confirmedParkingArmsAndAnUnavailableDumpDisablesTheDeterrent() {
        var now = 100_000L
        var printed: String? = dump(4, "Standby")
        val monitor = AccMonitor({
            VehicleSnapshot(null, null, null, null, null, "P", diLink5AccOnOf(printed), null)
        }, { now })
        monitor.poll()
        assertNull(monitor.snapshot())
        now += 2_000L
        monitor.poll()
        assertEquals("smart", sentryMode(true, "smart", monitor.snapshot(), "off", monitor.parkedForMs()))
        printed = null
        now += 2_000L
        monitor.poll()
        assertNull(monitor.snapshot()?.accOn)
        assertNull(sentryMode(true, "smart", monitor.snapshot(), "off", monitor.parkedForMs()))
    }

    @Test
    fun aCompletedDumpCanBeReadWithoutDependingOnAndroid() {
        val process = DumpProcess(dump(4, "Standby"))
        assertEquals(false, diLink5AccOnOf(readDiLink5PowerDump({ process })))
        assertFalse(process.destroyed)
    }

    @Test
    fun partialOffOutputFromATimedOutProcessCannotArm() {
        val process = DumpProcess(dump(4, "Standby"), completed = false)
        assertNull(diLink5AccOnOf(readDiLink5PowerDump({ process })))
        assertTrue(process.destroyed)
    }

    @Test
    fun anExitFailureOrAnOversizedDumpCannotEstablishIgnition() {
        assertNull(readDiLink5PowerDump({ DumpProcess(dump(4, "Standby"), exit = 1) }))
        val oversized = dump(4, "Standby") + " ".repeat(512 * 1024)
        assertNull(diLink5AccOnOf(oversized))
        assertNull(readDiLink5PowerDump({ DumpProcess(oversized) }))
    }

    @Test
    fun aMissingCommandIsAnUnknownReading() {
        assertNull(readDiLink5PowerDump({ throw IOException("not installed") }))
    }

    @Test
    fun interruptingAReadCancelsItsProcessAndPreservesTheInterrupt() {
        val process = DumpProcess(dump(4, "Standby"), completed = false, interrupted = true)
        try {
            assertNull(readDiLink5PowerDump({ process }))
            assertTrue(Thread.currentThread().isInterrupted)
            assertTrue(process.destroyed)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun aBlockedShellCannotAccumulateWorkersOrPublishALateOffReading() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val worker = AtomicReference<Thread>()
        val printed = AtomicReference(dump(4, "Standby"))
        val reader = DiLink5Power({
            calls.incrementAndGet()
            worker.set(Thread.currentThread())
            val sampled = printed.get()
            entered.countDown()
            release.await()
            sampled
        }, timeoutMs = 25)
        try {
            assertNull(reader.read())
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            repeat(20) { assertNull(reader.read()) }
            assertEquals(1, calls.get())
            printed.set(dump(2, "StartUp"))
        } finally {
            release.countDown()
            worker.get()?.join(1_000)
        }
        assertFalse(worker.get().isAlive)
        assertEquals(true, diLink5AccOnOf(reader.read()))
        assertEquals(2, calls.get())
    }

    @Test
    fun aFailedShellReadCanBeRetriedWithoutKeepingTheLastValue() {
        val worker = AtomicReference<Thread>()
        var failed = true
        val reader = DiLink5Power({
            worker.set(Thread.currentThread())
            if (failed) throw IOException("connection lost")
            dump(4, "Standby")
        })
        assertNull(reader.read())
        worker.get().join(1_000)
        failed = false
        assertEquals(false, diLink5AccOnOf(reader.read()))
    }

    @Test
    fun concurrentDirectReadsStartOneDumpAndTheNextReadGetsFreshPower() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val starts = AtomicInteger()
        val worker = AtomicReference<Thread>()
        val printed = AtomicReference(dump(4, "Standby"))
        val reader = DiLink5Power({
            readDiLink5PowerDump({
                starts.incrementAndGet()
                worker.set(Thread.currentThread())
                DumpProcess(printed.get(), onWait = {
                    entered.countDown()
                    release.await(1, TimeUnit.SECONDS)
                })
            })
        })
        val firstOutput = AtomicReference<String?>()
        val first = Thread { firstOutput.set(reader.read()) }.also { it.start() }
        val reused = AtomicInteger()
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            val concurrent = List(8) {
                Thread { if (reader.read() != null) reused.incrementAndGet() }.also { it.start() }
            }
            concurrent.forEach { it.join(1_000) }
            assertTrue(concurrent.none { it.isAlive })
            assertEquals(0, reused.get())
            assertEquals(1, starts.get())
        } finally {
            release.countDown()
            first.join(1_000)
            worker.get()?.join(1_000)
        }
        assertFalse(first.isAlive)
        assertEquals(false, diLink5AccOnOf(firstOutput.get()))
        printed.set(dump(2, "StartUp"))
        assertEquals(true, diLink5AccOnOf(reader.read()))
        assertEquals(2, starts.get())
    }

    private fun dump(mode: Int, name: String) = "Power Mute State:\n  current: {$mode=PowerMode $name}"

    private class DumpProcess(
        printed: String,
        private val completed: Boolean = true,
        private val exit: Int = 0,
        private val interrupted: Boolean = false,
        private val onWait: (() -> Boolean)? = null
    ) : Process() {
        private val stdout = ByteArrayInputStream(printed.toByteArray())
        var destroyed = false
            private set
        override fun getInputStream() = stdout
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getErrorStream() = ByteArrayInputStream(ByteArray(0))
        override fun waitFor() = exit
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            if (interrupted) throw InterruptedException()
            return onWait?.invoke() ?: completed
        }
        override fun exitValue() = exit
        override fun isAlive() = !completed && !destroyed
        override fun destroy() { destroyed = true }
        override fun destroyForcibly(): Process { destroy(); return this }
    }
}
