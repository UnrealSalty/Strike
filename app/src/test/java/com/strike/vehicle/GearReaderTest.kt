package com.strike.vehicle

import com.strike.daemon.AccMonitor
import com.strike.recording.sentryMode
import com.strike.recording.shouldRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GearReaderTest {
    @Test
    fun aValidPrimaryGearNeverOpensTheAdapter() {
        val reader = GearReader(adapter = { error("The primary reader is working") })
        for ((index, gear) in listOf("P", "R", "N", "D", "M", "S").withIndex()) {
            assertEquals(gear, reader.read(index + 1))
        }
    }

    @Test
    fun invalidPrimaryReadingsUseTheBodyAdapter() {
        val reader = GearReader(adapter = { Adapter(body = Body(4)) })
        for (primary in listOf(null, -1, 0, 7, 255)) {
            assertEquals("D", reader.read(primary))
        }
    }

    @Test
    fun theBodyAdapterAcceptsOnlyItsKnownGears() {
        val body = Body(null)
        val reader = GearReader(adapter = { Adapter(body = body) })
        for ((index, gear) in listOf("P", "R", "N", "D").withIndex()) {
            body.shift = index + 1
            assertEquals(gear, reader.read(null))
        }
        for (shift in listOf(null, -1, 0, 5, 6, 7, 255)) {
            body.shift = shift
            assertNull(reader.read(null))
        }
    }

    @Test
    fun cabinAutoModeSupportsAllLegacyGears() {
        val cabin = Cabin(null)
        val reader = GearReader(adapter = { Adapter(cabin = cabin) })
        for ((index, gear) in listOf("P", "R", "N", "D", "M", "S").withIndex()) {
            cabin.mode = index + 1
            assertEquals(gear, reader.read(null))
        }
    }

    @Test
    fun theOlderCabinGetterWorksWithoutTheAutoModeMethod() {
        val reader = GearReader(adapter = { Adapter(cabin = OlderCabin(2)) })
        assertEquals("R", reader.read(null))
    }

    @Test
    fun invalidAutoModeFallsThroughToTheOlderCabinGetter() {
        val reader = GearReader(adapter = { Adapter(cabin = BothCabinGetters(0, 4)) })
        assertEquals("D", reader.read(null))
    }

    @Test
    fun validBodyAndCabinReadingsKeepTheirPriority() {
        val body = Body(2)
        val reader = GearReader(adapter = { Adapter(body, BothCabinGetters(3, 4)) })
        assertEquals("R", reader.read(null))
        body.shift = 0
        assertEquals("N", reader.read(null))
    }

    @Test
    fun anUnavailableBodyDoesNotHideAWorkingCabin() {
        assertEquals("D", GearReader(adapter = {
            Adapter(ThrowingBody(), Cabin(4))
        }).read(null))
        assertEquals("D", GearReader(adapter = { FailingBodyAdapter() }).read(null))
    }

    @Test
    fun missingOrFailingReadersLeaveGearUnknown() {
        for (adapter in listOf(null, Any(), Adapter(), Adapter(Body(0), Cabin(0)), FailingAdapter())) {
            val reader = GearReader(adapter = { adapter })
            repeat(2) { assertNull(reader.read(null)) }
            assertEquals("D", reader.read(4))
        }
    }

    @Test
    fun readingsAreFreshAndReplacedManagersAreUsed() {
        val body = Body(1)
        val adapter = Adapter(body = body)
        val reader = GearReader(adapter = { adapter })
        assertEquals("P", reader.read(null))
        body.shift = 4
        assertEquals("D", reader.read(null))
        adapter.body = null
        assertNull(reader.read(null))
        adapter.cabin = OlderCabin(2)
        assertEquals("R", reader.read(null))
        adapter.cabin = Cabin(3)
        assertEquals("N", reader.read(null))
        assertEquals("D", reader.read(4))
    }

    @Test
    fun aDisconnectedServiceCannotSupplyItsOldGear() {
        var now = 0L
        val adapter = BoundAdapter()
        val reader = GearReader(adapter = { adapter }, nowMs = { now })
        assertEquals("P", reader.read(null))
        adapter.bound = false
        repeat(5) { assertNull(reader.read(null)) }
        assertEquals(1, adapter.connections)
        now = 29_999
        assertNull(reader.read(null))
        assertEquals(1, adapter.connections)
        now = 30_000
        assertNull(reader.read(null))
        assertEquals(2, adapter.connections)
        adapter.bound = true
        adapter.body = Body(4)
        assertEquals("D", reader.read(null))
    }

    @Test
    fun anUnreadableConnectionStateDoesNotExposeAStoredParkReading() {
        assertNull(GearReader(adapter = { UnreadableConnection() }).read(null))
    }

    @Test
    fun fallbackDriveBlocksSentryAndReturningToParkAllowsIt() {
        var now = 1_000_000L
        val body = Body(4)
        val reader = GearReader(adapter = { Adapter(body) })
        val monitor = AccMonitor(
            read = { VehicleSnapshot(null, null, null, reader.read(null), false, true) },
            nowMs = { now }
        )
        monitor.poll()
        now += 2_000
        monitor.poll()
        assertEquals("off", sentryMode(true, "smart", monitor.snapshot()))
        assertEquals(0L, monitor.parkedForMs())
        body.shift = 1
        now += 1_000
        monitor.poll()
        assertEquals("smart", sentryMode(true, "smart", monitor.snapshot()))
        body.shift = 2
        now += 1_000
        monitor.poll()
        assertEquals("off", sentryMode(true, "smart", monitor.snapshot()))
        assertEquals(0L, monitor.parkedForMs())
    }

    @Test
    fun aFallbackGearDoesNotInventIgnitionState() {
        val reader = GearReader(adapter = { Adapter(Body(1)) })
        val unknownPower = VehicleSnapshot(null, null, null, reader.read(null), null, true)
        assertNull(sentryMode(true, "smart", unknownPower))
        val ignitionOn = VehicleSnapshot(null, null, null, reader.read(null), true, true)
        assertEquals("off", sentryMode(true, "smart", ignitionOn))
        assertFalse(shouldRecord("driving", ignitionOn))
    }

    @Test
    fun primaryDriveOverridesFallbackParkForRecordingAndSurveillance() {
        val reader = GearReader(adapter = { Adapter(Body(1)) })
        val driving = VehicleSnapshot(null, null, null, reader.read(4), true, false)
        assertTrue(shouldRecord("driving", driving))
        assertEquals("off", sentryMode(true, "smart", driving))
    }

    class Body(var shift: Int?) {
        fun getShiftMode(): Int? = shift
    }

    class Cabin(var mode: Int?) {
        fun getGearboxAutoModeType(): Int? = mode
    }

    class OlderCabin(private val gear: Int) {
        fun getGear(): Int = gear
    }

    class BothCabinGetters(private val mode: Int, private val gear: Int) {
        fun getGearboxAutoModeType(): Int = mode
        fun getGear(): Int = gear
    }

    class Adapter(var body: Any? = null, var cabin: Any? = null) {
        fun getCarAdapterManager(section: String): Any? = when (section) {
            "body" -> body
            "cabin" -> cabin
            else -> error("Unexpected adapter: $section")
        }
    }

    class BoundAdapter {
        var bound = true
        var body = Body(1)
        var connections = 0
        fun isCarServiceBound(): Boolean = bound
        fun connect() { connections++ }
        fun getCarAdapterManager(section: String): Any? = if (section == "body") body else null
    }

    class UnreadableConnection {
        fun isCarServiceBound(): Boolean = error("Service disconnected")
        fun getCarAdapterManager(section: String): Any? = if (section == "body") Body(1) else null
    }

    class ThrowingBody {
        fun getShiftMode(): Int = error("Body service disconnected")
    }

    class FailingBodyAdapter {
        fun getCarAdapterManager(section: String): Any? = when (section) {
            "body" -> error("Body service disconnected")
            "cabin" -> Cabin(4)
            else -> null
        }
    }

    class FailingAdapter {
        fun getCarAdapterManager(section: String): Any? = error("Adapter $section disconnected")
    }
}
