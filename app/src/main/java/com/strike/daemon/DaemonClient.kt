package com.strike.daemon

import com.strike.vehicle.VehicleSnapshot
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket

private const val HOST = "127.0.0.1"
private const val CONNECT_MS = 1_500
private const val READ_MS = 2_500

// Use a fresh socket per command; null means the daemon did not answer.
class DaemonClient {

    fun status(): JSONObject? = send("status")

    fun shutdown(): Boolean = accepted(send("shutdown"))

    fun liveStart(view: String, bitrateBps: Int): Boolean = accepted(exchange(JSONObject()
        .put("cmd", "live.start").put("view", view).put("bitrateBps", bitrateBps)))

    fun liveStop(): Boolean = accepted(send("live.stop"))

    fun vehicle(snapshot: VehicleSnapshot?, observed: (VehicleSnapshot?) -> Unit = {}): Boolean {
        val request = JSONObject()
        request.put("cmd", "vehicle")
        if (snapshot?.accOn != null) request.put("on", snapshot.accOn)
        if (snapshot?.gear != null) request.put("gear", snapshot.gear)
        if (snapshot?.locked != null) request.put("locked", snapshot.locked)
        val reply = exchange(request)
        observed(if (accepted(reply) && reply!!.has("vehicle")) {
            reply.optJSONObject("vehicle")?.let {
                VehicleSnapshot(null, null, null,
                    if (it.has("gear")) it.getString("gear") else null,
                    if (it.has("on")) it.getBoolean("on") else null,
                    if (it.has("locked")) it.getBoolean("locked") else null)
            }
        } else snapshot)
        return accepted(reply)
    }

    /** A broadcast edge, distinct from a possibly stale power-state poll. */
    fun acc(accOn: Boolean): Boolean {
        val request = JSONObject()
        request.put("cmd", "acc")
        request.put("on", accOn)
        return accepted(exchange(request))
    }

    fun previewScreen(message: String, seconds: Int): Boolean {
        val request = JSONObject()
        request.put("cmd", "screen.preview")
        request.put("message", message)
        request.put("seconds", seconds)
        return accepted(exchange(request))
    }

    private fun send(command: String, view: String? = null): JSONObject? {
        val request = JSONObject()
        request.put("cmd", command)
        if (view != null) request.put("view", view)
        return exchange(request)
    }

    private fun exchange(request: JSONObject, timeoutMs: Int = READ_MS): JSONObject? =
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(HOST, COMMAND_PORT), CONNECT_MS)
                socket.soTimeout = timeoutMs
                PrintWriter(socket.getOutputStream(), true).println(request.toString())
                val line = BufferedReader(InputStreamReader(socket.getInputStream())).readLine()
                if (line == null) null else JSONObject(line)
            }
        } catch (e: IOException) {
            null
        } catch (e: JSONException) {
            null
        }

    private fun accepted(reply: JSONObject?): Boolean = reply?.optString("status") == "ok"
}
