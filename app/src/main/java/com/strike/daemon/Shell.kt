package com.strike.daemon

import android.content.Context
import android.provider.Settings
import com.strike.core.Logs
import dadb.AdbKeyPair
import dadb.AdbShellResponse
import dadb.Dadb
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private const val TAG = "Shell"
private const val HOST = "127.0.0.1"
private const val PORT = 5555
private const val PORT_PROBE_MS = 300
private const val CONNECT_TIMEOUT_MS = 3_000
private const val SOCKET_TIMEOUT_MS = 45_000
private const val HANDSHAKE_MS = 2_500L

private const val REFUSED_TTL_MS = 5_000L

// Local ADB provides shell UID 2000 after the owner authorizes its key.
class Shell(private val context: Context) {

    private val lock = Any()
    private val connector = Executors.newSingleThreadExecutor()
    private var keys: AdbKeyPair? = null
    private var dadb: Dadb? = null
    private var authorised: Boolean? = null
    private var refusedAtMs = 0L

    fun isAuthorised(): Boolean = synchronized(lock) {
        authorised ?: (connect() != null)
    }

    /** The exit code, or null when there is no shell at all. */
    fun run(command: String): Int? = synchronized(lock) {
        val exitCode = exec(command)?.exitCode
        if (exitCode != null && exitCode != 0) {
            Logs.w(TAG, "$command exited $exitCode")
        }
        exitCode
    }

    /** For probes, where a non-zero exit is an answer rather than a failure. */
    fun check(command: String): Boolean = synchronized(lock) {
        exec(command)?.exitCode == 0
    }

    /** Standard output, or null when the command could not run or failed. */
    fun read(command: String): String? = synchronized(lock) {
        val response = exec(command) ?: return null
        if (response.exitCode != 0) return null
        response.output
    }

    private fun exec(command: String): AdbShellResponse? {
        val connection = connect() ?: return null
        return try {
            connection.shell(command)
        } catch (e: IOException) {
            Logs.d(TAG, "shell lost during: $command")
            drop()
            null
        }
    }

    fun retry(): Boolean = synchronized(lock) {
        drop()
        connect() != null
    }

    // Retry refusals after a short delay so accepting the ADB prompt takes effect.
    private fun connect(): Dadb? {
        dadb?.let { return it }
        if (authorised == false && System.currentTimeMillis() - refusedAtMs < REFUSED_TTL_MS) {
            return null
        }
        if (!portOpen()) {
            enableAdb()
            if (!portOpen()) return refused()
        }
        val opened = handshake()
        if (opened == null) return refused()
        authorised = true
        dadb = opened
        Logs.d(TAG, "shell authorised")
        return opened
    }

    private fun refused(): Dadb? {
        authorised = false
        refusedAtMs = System.currentTimeMillis()
        return null
    }

    private fun handshake(): Dadb? {
        val attempt = connector.submit<Dadb?> {
            try {
                val fresh = Dadb.create(HOST, PORT, keyPair(), CONNECT_TIMEOUT_MS, SOCKET_TIMEOUT_MS)
                if (fresh.shell("echo ok").exitCode == 0) {
                    fresh
                } else {
                    fresh.close()
                    null
                }
            } catch (e: Exception) {
                Logs.d(TAG, "adb handshake refused: ${e.message}")
                null
            }
        }
        return try {
            attempt.get(HANDSHAKE_MS, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            attempt.cancel(true)
            null
        }
    }

    private fun keyPair(): AdbKeyPair {
        keys?.let { return it }
        val privateKey = File(context.filesDir, "adbkey")
        val publicKey = File(context.filesDir, "adbkey.pub")
        if (!privateKey.exists() || !publicKey.exists()) {
            AdbKeyPair.generate(privateKey, publicKey)
        }
        val pair = AdbKeyPair.read(privateKey, publicKey)
        keys = pair
        return pair
    }

    private fun portOpen(): Boolean = try {
        Socket().use {
            it.connect(InetSocketAddress(HOST, PORT), PORT_PROBE_MS)
            true
        }
    } catch (e: IOException) {
        false
    }

    private fun enableAdb() {
        try {
            val resolver = context.contentResolver
            Settings.Global.putInt(resolver, "adb_enabled", 1)
            Settings.Global.putInt(resolver, "adb_wifi_enabled", 1)
            Settings.Global.putInt(resolver, "adb_allowed_connection_time", 0)
        } catch (e: SecurityException) {
            Logs.d(TAG, "cannot turn adb on from here")
        }
    }

    private fun drop() {
        try {
            dadb?.close()
        } catch (e: IOException) {
            Logs.d(TAG, "adb close failed")
        }
        dadb = null
        authorised = null
    }
}
