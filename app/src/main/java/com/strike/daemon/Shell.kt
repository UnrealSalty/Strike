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
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask

private const val TAG = "Shell"
private const val HOST = "127.0.0.1"
private const val PORT = 5555
private const val PORT_PROBE_MS = 300
private const val CONNECT_TIMEOUT_MS = 3_000
private const val SOCKET_TIMEOUT_MS = 45_000
private const val AUTH_ATTEMPTS = 5
private const val RETRY_MS = 5_000L
private const val MAX_RETRY_MS = 30_000L

// The app shares one shell connection and key pair across HTTP and background work.
class Shell internal constructor(
    private val open: () -> Dadb?,
    private val connector: Executor = Executors.newSingleThreadExecutor {
        Thread(it, "shell-connect").also { thread -> thread.isDaemon = true }
    },
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    constructor(context: Context) : this(adbOpener(context))

    private val lock = Any()
    private val commands = Any()
    private var dadb: Dadb? = null
    private var pending: FutureTask<Dadb?>? = null
    private var failures = 0
    private var retryAtMs = 0L

    val isPending: Boolean
        get() = synchronized(lock) { pending != null || failures in 1 until AUTH_ATTEMPTS }

    fun isAuthorised(): Boolean = connect() != null

    /** The exit code, or null when there is no shell at all. */
    fun run(command: String): Int? = synchronized(commands) {
        val exitCode = exec(command)?.exitCode
        if (exitCode != null && exitCode != 0) {
            Logs.w(TAG, "$command exited $exitCode")
        }
        exitCode
    }

    /** For probes, where a non-zero exit is an answer rather than a failure. */
    fun check(command: String): Boolean = synchronized(commands) {
        exec(command)?.exitCode == 0
    }

    /** Standard output, or null when the command could not run or failed. */
    fun read(command: String): String? = synchronized(commands) {
        val response = exec(command) ?: return null
        if (response.exitCode != 0) return null
        response.output
    }

    fun push(file: File, path: String): Boolean = synchronized(commands) {
        val connection = connect() ?: return false
        try {
            connection.push(file, path)
            true
        } catch (e: IOException) {
            Logs.w(TAG, "Could not transfer the update to the installer")
            drop(connection)
            false
        }
    }

    private fun exec(command: String): AdbShellResponse? {
        val connection = connect() ?: return null
        return try {
            connection.shell(command)
        } catch (e: IOException) {
            Logs.d(TAG, "shell lost during: $command")
            drop(connection)
            null
        }
    }

    fun retry(): Boolean = connect(force = true) != null

    private fun connect(force: Boolean = false): Dadb? = synchronized(lock) {
        dadb?.let { return it }
        val attempt = pending
        if (attempt != null) {
            if (!attempt.isDone) return null
            pending = null
            val opened = attempt.get()
            if (opened != null) {
                dadb = opened
                failures = 0
                Logs.d(TAG, "shell authorised")
                return opened
            }
            failures++
            retryAtMs = nowMs() + minOf(RETRY_MS * (1L shl (failures - 1)), MAX_RETRY_MS)
            if (failures == AUTH_ATTEMPTS) {
                Logs.w(TAG, "shell unavailable; accept the debugging prompt and press Connect to retry")
            }
        }
        if (force) {
            failures = 0
            retryAtMs = 0L
        }
        if (failures >= AUTH_ATTEMPTS || nowMs() < retryAtMs) return null

        // Keep a late approval: cancelling a Future does not stop socket I/O.
        val next = FutureTask<Dadb?> {
            try {
                open()
            } catch (e: Exception) {
                Logs.d(TAG, "adb connection failed: ${e.message}")
                null
            }
        }
        pending = next
        connector.execute(next)
        null
    }

    private fun drop(connection: Dadb) {
        try {
            connection.close()
        } catch (e: IOException) {
            Logs.d(TAG, "adb close failed")
        }
        synchronized(lock) {
            dadb = null
            failures = 0
            retryAtMs = 0L
        }
    }
}

private fun adbOpener(context: Context): () -> Dadb? {
    val keys by lazy {
        val privateKey = File(context.filesDir, "adbkey")
        val publicKey = File(context.filesDir, "adbkey.pub")
        if (!privateKey.exists() || !publicKey.exists()) {
            AdbKeyPair.generate(privateKey, publicKey)
        }
        AdbKeyPair.read(privateKey, publicKey)
    }
    return {
        var listening = portOpen()
        if (!listening) {
            enableAdb(context)
            listening = portOpen()
        }
        if (!listening) {
            null
        } else {
            val fresh = Dadb.create(HOST, PORT, keys, CONNECT_TIMEOUT_MS, SOCKET_TIMEOUT_MS)
            var accepted = false
            try {
                accepted = fresh.shell("echo ok").exitCode == 0
                if (accepted) fresh else null
            } finally {
                if (!accepted) fresh.close()
            }
        }
    }
}

private fun portOpen(): Boolean = try {
    Socket().use {
        it.connect(InetSocketAddress(HOST, PORT), PORT_PROBE_MS)
        true
    }
} catch (e: IOException) {
    false
}

private fun enableAdb(context: Context) {
    try {
        val resolver = context.contentResolver
        Settings.Global.putInt(resolver, "adb_enabled", 1)
        Settings.Global.putInt(resolver, "adb_wifi_enabled", 1)
        Settings.Global.putInt(resolver, "adb_allowed_connection_time", 0)
    } catch (e: SecurityException) {
        Logs.d(TAG, "cannot turn adb on from here")
    }
}
