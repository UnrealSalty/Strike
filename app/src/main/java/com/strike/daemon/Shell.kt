package com.strike.daemon

import android.content.Context
import android.provider.Settings
import android.os.Process
import com.strike.core.Logs
import dadb.AdbKeyPair
import dadb.AdbShellResponse
import dadb.Dadb
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executor
import java.util.concurrent.Executors

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
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val onAuthorised: () -> Unit = {}
) {
    constructor(context: Context, onAuthorised: () -> Unit = {}) :
        this(adbOpener(context), onAuthorised = onAuthorised)

    private val lock = Any()
    private val commands = Any()
    private var dadb: Dadb? = null
    private var connecting = false
    private var retryAfterConnect = false
    private var failures = 0
    private var retryAtMs = 0L
    private val local = Process.myUid() == 2000

    val isPending: Boolean
        get() = synchronized(lock) { connecting || failures in 1 until AUTH_ATTEMPTS }

    fun isAuthorised(): Boolean = local || connect() != null

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
        if (local) return@synchronized try {
            Files.copy(file.toPath(), File(path).toPath(), StandardCopyOption.REPLACE_EXISTING)
            true
        } catch (e: IOException) {
            Logs.w(TAG, "Could not transfer the update to the installer")
            false
        }
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
        if (local) return localCommand(command)
        val connection = connect() ?: return null
        return try {
            connection.shell(command)
        } catch (e: IOException) {
            Logs.d(TAG, "shell lost during: $command")
            drop(connection)
            null
        }
    }

    fun retry(): Boolean = local || connect(force = true) != null

    fun exchangeLocal(apk: String, request: ByteArray, maxBytes: Int): ByteArray? = synchronized(commands) {
        val connection = connect() ?: return null
        try {
            val quoted = apk.replace("'", "'\"'\"'")
            connection.open("exec:CLASSPATH='$quoted' app_process /system/bin " +
                "com.strike.server.DashboardControl").use { stream ->
                stream.sink.writeInt(request.size).write(request).flush()
                val length = stream.source.readInt()
                if (length !in 1..maxBytes) throw IOException("Invalid dashboard response")
                stream.source.readByteArray(length.toLong())
            }
        } catch (e: IOException) {
            null
        }
    }

    private fun connect(force: Boolean = false): Dadb? = synchronized(lock) {
        dadb?.let { return it }
        if (connecting) {
            if (force) retryAfterConnect = true
            return null
        }
        if (force) {
            failures = 0
            retryAtMs = 0L
        }
        if (failures >= AUTH_ATTEMPTS || nowMs() < retryAtMs) return null

        connecting = true
        connector.execute {
            val opened = try {
                open()
            } catch (e: Exception) {
                Logs.d(TAG, "adb connection failed: ${e.message}")
                null
            }
            val retryRequested = synchronized(lock) {
                connecting = false
                dadb = opened
                val again = opened == null && retryAfterConnect
                retryAfterConnect = false
                if (opened != null) {
                    failures = 0
                    retryAtMs = 0L
                } else {
                    failures++
                    retryAtMs = nowMs() + minOf(RETRY_MS * (1L shl (failures - 1)), MAX_RETRY_MS)
                    if (failures == AUTH_ATTEMPTS && !again) {
                        Logs.w(TAG, "shell unavailable; accept the debugging prompt and press Connect to retry")
                    }
                }
                again
            }
            if (opened != null) {
                Logs.d(TAG, "shell authorised")
                onAuthorised()
            } else if (retryRequested) connect(force = true)
        }
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

private fun localCommand(command: String): AdbShellResponse? = try {
    val child = ProcessBuilder("timeout", "-s", "KILL", "45", "sh", "-c", "umask 022; $command")
        .redirectErrorStream(true).start()
    try {
        val output = child.inputStream.bufferedReader().use { it.readText() }
        AdbShellResponse(output, "", child.waitFor())
    } finally {
        if (child.isAlive) child.destroyForcibly()
    }
} catch (e: IOException) {
    Logs.w(TAG, "Could not run the shell command")
    null
} catch (e: InterruptedException) {
    Thread.currentThread().interrupt()
    null
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
