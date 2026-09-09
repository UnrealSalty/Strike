package com.strike.server

import android.net.LocalSocket
import android.net.LocalSocketAddress
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

object DashboardControl {
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            val input = DataInputStream(System.`in`)
            val length = input.readInt()
            if (length !in 1..DASHBOARD_MAX_BYTES) return
            val request = ByteArray(length)
            input.readFully(request)
            val reply = exchange(request, 10_000) ?: return
            DataOutputStream(System.out).apply { writeInt(reply.size); write(reply); flush() }
        } catch (e: IOException) {
            // The dashboard may still be starting or restarting for an update.
        }
    }

    internal fun releasePanel(apk: String) {
        val request = JSONObject().put("op", "panel.release").put("apk", apk).toString().toByteArray()
        try {
            exchange(request, 1_000)
        } catch (e: IOException) {
            // Camera ownership waits for the panel lock even if this prompt release request is lost.
        }
    }

    private fun exchange(request: ByteArray, timeoutMs: Int): ByteArray? = LocalSocket().use { socket ->
        socket.connect(LocalSocketAddress(DASHBOARD_SOCKET))
        if (socket.peerCredentials.uid != 2000) return null
        socket.soTimeout = timeoutMs
        DataOutputStream(socket.outputStream).apply { writeInt(request.size); write(request); flush() }
        val received = DataInputStream(socket.inputStream)
        val length = received.readInt()
        if (length !in 1..DASHBOARD_MAX_BYTES) return null
        ByteArray(length).also { received.readFully(it) }
    }
}
