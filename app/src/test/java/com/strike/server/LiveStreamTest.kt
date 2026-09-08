package com.strike.server

import com.strike.camera.LiveQuality
import com.strike.daemon.COMMAND_PORT
import com.strike.daemon.DaemonClient
import com.strike.daemon.PACKET_PORT
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class LiveStreamTest {
    @Test fun viewersShareTheLowestBitrateAndOnlyTheLastDepartureStopsTheStream() {
        Fixture().use { fixture ->
            val first = fixture.join(LiveQuality.HIGH)
            fixture.command("live.start", 1_500_000)
            val relay = fixture.relays.poll(3, TimeUnit.SECONDS)!!
            val high = fixture.join(LiveQuality.HIGH)
            fixture.command("live.start", 1_500_000)
            fixture.frame(relay, first, high)
            first.close()
            first.ended.get(3, TimeUnit.SECONDS)
            val low = fixture.join(LiveQuality.LOW)
            fixture.command("live.start", 500_000)
            fixture.frame(relay, high, low)

            low.close()
            fixture.command("live.start", 1_500_000)
            fixture.frame(relay, high)
            low.ended.get(3, TimeUnit.SECONDS)
            assertTrue(fixture.live.isWatched)

            high.close()
            high.ended.get(3, TimeUnit.SECONDS)
            fixture.command("live.stop")
            assertFalse(fixture.live.isWatched)
            assertEquals(-1, relay.getInputStream().read())

            val again = fixture.join(LiveQuality.BALANCED)
            fixture.command("live.start", 900_000)
            fixture.frame(fixture.relays.poll(3, TimeUnit.SECONDS)!!, again)
            again.close()
            again.ended.get(3, TimeUnit.SECONDS)
            fixture.command("live.stop")
            assertTrue(fixture.commands.isEmpty())
        }
    }

    @Test fun changingQualityWhileAnotherViewerWatchesKeepsFramesFlowing() {
        Fixture().use { fixture ->
            val car = fixture.join(LiveQuality.HIGH)
            fixture.command("live.start", 1_500_000)
            val relay = fixture.relays.poll(3, TimeUnit.SECONDS)!!
            var browser = fixture.join(LiveQuality.LOW)
            fixture.command("live.start", 500_000)
            fixture.frame(relay, car, browser)
            browser.close()
            fixture.command("live.start", 1_500_000)
            browser = fixture.join(LiveQuality.BALANCED)
            fixture.command("live.start", 900_000)
            fixture.frame(relay, car, browser)
            car.close()
            car.ended.get(3, TimeUnit.SECONDS)
            fixture.frame(relay, browser)
            browser.close()
            browser.ended.get(3, TimeUnit.SECONDS)
            fixture.command("live.stop")
        }
    }

    private class Viewer(val client: Socket, val ended: Future<*>) {
        fun close() = client.close()
    }

    private class Fixture : AutoCloseable {
        private val workers = Executors.newCachedThreadPool()
        private val commandPort = ServerSocket(COMMAND_PORT, 4, InetAddress.getLoopbackAddress())
        private val packetPort = ServerSocket(PACKET_PORT, 4, InetAddress.getLoopbackAddress())
        private val clients = ArrayList<Socket>()
        private val relaySockets = LinkedBlockingQueue<Socket>()
        val commands = LinkedBlockingQueue<JSONObject>()
        val relays = LinkedBlockingQueue<Socket>()
        val live = LiveStream(DaemonClient())

        init {
            workers.submit {
                try {
                    while (!commandPort.isClosed) commandPort.accept().use { socket ->
                        socket.soTimeout = 3000
                        val request = JSONObject(socket.getInputStream().bufferedReader().readLine())
                        socket.getOutputStream().write("{\"status\":\"ok\"}\n".toByteArray())
                        commands.offer(request)
                    }
                } catch (e: IOException) { if (!commandPort.isClosed) throw e }
            }
            workers.submit {
                try {
                    while (!packetPort.isClosed) {
                        val socket = packetPort.accept().also { it.soTimeout = 3000 }
                        relaySockets.offer(socket)
                        relays.offer(socket)
                    }
                } catch (e: IOException) { if (!packetPort.isClosed) throw e }
            }
        }

        fun join(quality: LiveQuality): Viewer {
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { port ->
                val client = Socket(InetAddress.getLoopbackAddress(), port.localPort)
                client.soTimeout = 3000
                clients.add(client)
                val accepted = port.accept()
                val ended = workers.submit {
                    accepted.use { live.serve(WebSocket(it.getInputStream(), it.getOutputStream()), "all", quality) }
                }
                return Viewer(client, ended)
            }
        }

        fun command(name: String, bitrateBps: Int? = null) {
            val command = commands.poll(3, TimeUnit.SECONDS)
            assertNotNull("Expected $name", command)
            assertEquals(name, command!!.getString("cmd"))
            if (bitrateBps != null) assertEquals(bitrateBps, command.getInt("bitrateBps"))
        }

        fun frame(relay: Socket, vararg viewers: Viewer) {
            val packet = byteArrayOf(2, 0, 0, 0, 0, 0, 0, 0, 1, 7, 8)
            DataOutputStream(relay.getOutputStream()).apply {
                writeInt(packet.size)
                write(packet)
                flush()
            }
            for (viewer in viewers) {
                val input = DataInputStream(viewer.client.getInputStream())
                assertEquals(0x82, input.readUnsignedByte())
                assertEquals(packet.size, input.readUnsignedByte())
                val received = ByteArray(packet.size)
                input.readFully(received)
                assertArrayEquals(packet, received)
            }
        }

        override fun close() {
            clients.forEach { it.close() }
            workers.shutdown()
            try {
                for (socket in relaySockets) socket.close()
                packetPort.close()
                commandPort.close()
            } finally {
                if (!workers.awaitTermination(5, TimeUnit.SECONDS)) workers.shutdownNow()
            }
        }
    }
}
