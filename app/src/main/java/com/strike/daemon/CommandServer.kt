package com.strike.daemon

import com.strike.vehicle.VehicleSnapshot
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

private const val TAG = "Command"
private const val BACKLOG = 4
private const val RESTART_MS = 2_000L

// Wire format: one JSON object per line in each direction.
class CommandServer(private val answer: (JSONObject) -> JSONObject) {

    @Volatile
    private var running = true
    private var socket: ServerSocket? = null

    fun serveForever() {
        while (running) {
            try {
                val server = ServerSocket(COMMAND_PORT, BACKLOG, InetAddress.getByName("127.0.0.1"))
                socket = server
                DaemonLog.d(TAG, "listening on 127.0.0.1:$COMMAND_PORT")
                while (running && !server.isClosed) {
                    val client = server.accept()
                    Thread({ serve(client) }, "command").start()
                }
            } catch (e: IOException) {
                if (!running) return
                DaemonLog.w(TAG, "command port lost: ${e.message}")
                Thread.sleep(RESTART_MS)
            }
        }
    }

    fun stop() {
        running = false
        try {
            socket?.close()
        } catch (e: IOException) {
            DaemonLog.w(TAG, "command port close failed")
        }
    }

    private fun serve(client: Socket) {
        try {
            client.use {
                val reader = BufferedReader(InputStreamReader(it.getInputStream()))
                val writer = PrintWriter(it.getOutputStream(), true)
                while (true) {
                    val line = reader.readLine() ?: return
                    writer.println(reply(line).toString())
                }
            }
        } catch (e: IOException) {
            DaemonLog.d(TAG, "client went away")
        }
    }

    private fun reply(line: String): JSONObject = try {
        answer(JSONObject(line))
    } catch (e: Exception) {
        failed(e.message ?: e.javaClass.simpleName)
    }
}

internal fun failed(reason: String): JSONObject {
    val payload = JSONObject()
    payload.put("status", "error")
    payload.put("message", reason)
    return payload
}

internal fun ok(): JSONObject {
    val payload = JSONObject()
    payload.put("status", "ok")
    return payload
}

internal fun vehicleReply(snapshot: VehicleSnapshot?): JSONObject = ok().put("vehicle",
    snapshot?.let {
        JSONObject().put("on", it.accOn).put("gear", it.gear).put("locked", it.locked)
    } ?: JSONObject.NULL)
