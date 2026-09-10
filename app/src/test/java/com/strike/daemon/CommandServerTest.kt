package com.strike.daemon

import com.strike.vehicle.VehicleSnapshot
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket

class CommandServerTest {

    private val seen = ArrayList<String>()
    private var vehicle: JSONObject? = null
    private var observed: VehicleSnapshot? = null
    private var legacy = false
    private val server = CommandServer { command ->
        when (command.optString("cmd")) {
            "status" -> ok().put("recording", true)
            "acc" -> ok()
            "vehicle" -> { vehicle = command; if (legacy) ok() else vehicleReply(observed) }
            else -> failed("no command named ${command.optString("cmd")}")
        }.also { seen.add(command.optString("cmd")) }
    }
    private val serving = Thread { server.serveForever() }

    @After
    fun stopServing() {
        server.stop()
        serving.join(2_000)
    }

    @Test
    fun theAppTalksToTheDaemonOneLineEachWay() {
        serving.start()
        val client = DaemonClient()
        val status = awaitAnswer(client)

        assertEquals(true, status.getBoolean("recording"))
        assertTrue(client.acc(true))
        assertTrue(client.vehicle(VehicleSnapshot(null, null, null, null, null, "P", false, true)))
        assertEquals(listOf("status", "acc", "vehicle"), seen)
        assertEquals(false, vehicle!!.getBoolean("on"))
        assertEquals(true, vehicle!!.getBoolean("locked"))
        assertEquals("P", vehicle!!.getString("gear"))
    }

    @Test
    fun aDaemonThatIsNotThereAnswersNothing() {
        assertNull(DaemonClient().status())
    }

    @Test
    fun theAppReceivesTheDaemonsParkedReadingWhenItsOwnReadingIsUnavailable() {
        observed = VehicleSnapshot(null, null, null, null, null, "P", false, true)
        serving.start()
        val client = DaemonClient()
        awaitAnswer(client)
        assertTrue(client.vehicle(VehicleSnapshot(null, null, null, null, null, null, null, null)) {
            assertEquals(false, it!!.accOn)
            assertEquals("P", it.gear)
            assertEquals(true, it.locked)
        })
    }

    @Test
    fun unknownDaemonReadingsStayUnknown() {
        serving.start()
        val client = DaemonClient()
        awaitAnswer(client)
        assertTrue(client.vehicle(VehicleSnapshot(null, null, null, null, null, "P", false, true)) {
            assertNull(it)
        })
        observed = VehicleSnapshot(null, null, null, null, null, "P", null, null)
        assertTrue(client.vehicle(null) {
            assertEquals("P", it!!.gear)
            assertNull(it.accOn)
            assertNull(it.locked)
        })
    }

    @Test
    fun anOlderDaemonOrMissingDaemonLeavesTheAppReadingAvailable() {
        val snapshot = VehicleSnapshot(null, null, null, null, null, "P", false, true)
        val client = DaemonClient()
        client.vehicle(snapshot) { assertEquals(snapshot, it) }
        legacy = true
        serving.start()
        awaitAnswer(client)
        assertTrue(client.vehicle(snapshot) { assertEquals(snapshot, it) })
    }

    @Test
    fun anUnknownCommandIsRefusedWithoutDroppingTheConnection() {
        serving.start()
        awaitAnswer(DaemonClient())
        Socket("127.0.0.1", COMMAND_PORT).use { socket ->
            val out = PrintWriter(socket.getOutputStream(), true)
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            out.println("""{"cmd":"dance"}""")
            val refused = JSONObject(reader.readLine())
            out.println("""{"cmd":"acc","on":true}""")
            val accepted = JSONObject(reader.readLine())

            assertEquals("error", refused.getString("status"))
            assertEquals("no command named dance", refused.getString("message"))
            assertEquals("ok", accepted.getString("status"))
        }
    }

    private fun awaitAnswer(client: DaemonClient): JSONObject {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            client.status()?.let { return it }
            Thread.sleep(50)
        }
        throw AssertionError("the command port never answered")
    }
}
