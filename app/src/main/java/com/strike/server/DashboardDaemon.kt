package com.strike.server

import android.content.Context
import android.content.pm.PackageManager
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.system.Os
import com.strike.core.Logs
import com.strike.core.PinSession
import com.strike.daemon.DaemonContext
import com.strike.daemon.DaemonLog
import com.strike.daemon.Shell
import com.strike.vehicle.VehicleTelemetry
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import kotlin.system.exitProcess

object DashboardDaemon {
    @JvmStatic
    fun main(args: Array<String>) {
        check(Process.myUid() == 2000)
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            DaemonLog.e("Online", "Dashboard stopped on ${thread.name}: $error")
            error.printStackTrace(System.err)
            exitProcess(1)
        }
        Os.umask(0x3f)
        Looper.prepareMainLooper()
        val base = checkNotNull(DaemonContext.get()) { "Shell context unavailable" }
        val installed = base.createPackageContext("com.strike", Context.CONTEXT_IGNORE_SECURITY)
        val uid = installed.applicationInfo.uid
        val directory = File(args.single())
        if (directory.path != dashboardDirectory(installed)) exitProcess(0)
        val lock = FileChannel.open(File(directory, "lock").toPath(),
            StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        val lease = lock.tryLock() ?: exitProcess(3)
        val server = try { LocalServerSocket(DASHBOARD_SOCKET) } catch (e: IOException) { exitProcess(3) }
        val context = DashboardContext(base, installed, directory)
        val host = DashboardHost(context, uid, VehicleTelemetry(base))
        host.restore()
        Runtime.getRuntime().addShutdownHook(Thread({
            try {
                host.close()
            } catch (e: Exception) {
                Logs.w("Online", "The dashboard stopped before its pending work finished", e)
            } finally {
                try {
                    server.close()
                    lease.release()
                    lock.close()
                } catch (e: IOException) {
                    Logs.w("Online", "Could not close the dashboard connection", e)
                }
            }
        }, "dashboard-stop"))
        Thread({
            while (true) {
                val client = try { server.accept() } catch (e: IOException) { break }
                try {
                    client.use { host.answer(it) }
                } catch (e: Exception) {
                    Logs.w("Online", "The car screen could not connect to the dashboard", e)
                }
            }
        }, "dashboard-control").start()
        val handler = Handler(Looper.getMainLooper())
        val apk = installed.applicationInfo.sourceDir
        val log = File(directory, "daemon.log")
        var nextLogCheckMs = 0L
        handler.postDelayed(object : Runnable {
            override fun run() {
                val current = try {
                    base.packageManager.getApplicationInfo("com.strike", 0)
                } catch (e: PackageManager.NameNotFoundException) { exitProcess(0) }
                if (current.uid != uid) exitProcess(0)
                if (current.sourceDir != apk) exitProcess(42)
                val nowMs = SystemClock.elapsedRealtime()
                if (nowMs >= nextLogCheckMs) {
                    nextLogCheckMs = nowMs + 3_600_000L
                    try {
                        trimDashboardLog(log)
                    } catch (e: IOException) {
                        Logs.w("Online", "Could not trim the dashboard log", e)
                    } catch (e: SecurityException) {
                        Logs.w("Online", "Could not trim the dashboard log", e)
                    }
                }
                handler.postDelayed(this, 15_000L)
            }
        }, 15_000L)
        Looper.loop()
    }
}

internal fun trimDashboardLog(file: File) {
    if (file.length() <= 5_242_880L) return
    RandomAccessFile(file, "rw").use { log ->
        val tail = ByteArray(65_536)
        log.seek(log.length() - tail.size)
        log.readFully(tail)
        val start = tail.indexOf('\n'.code.toByte()) + 1
        log.seek(0L)
        log.write(tail, start, tail.size - start)
        log.setLength((tail.size - start).toLong())
    }
}

private class DashboardHost(private val context: Context, private val uid: Int, private val vehicle: VehicleTelemetry) {
    private var runtime: DashboardRuntime? = null
    private val identityFile = File(context.filesDir, "identity")
    private var identity = if (identityFile.isFile) identityFile.readText() else ""

    fun restore() {
        if (identity.isNotEmpty()) start()
    }

    @Synchronized
    fun answer(client: LocalSocket) {
        // The app reaches this socket through its authenticated ADB connection.
        if (client.peerCredentials.uid != 2000) return
        if (context.packageManager.getApplicationInfo("com.strike", 0).uid != uid) return
        client.soTimeout = 10_000
        val input = DataInputStream(client.inputStream)
        val length = input.readInt()
        require(length in 1..DASHBOARD_MAX_BYTES)
        val bytes = ByteArray(length)
        input.readFully(bytes)
        val request = JSONObject(bytes.toString(Charsets.UTF_8))
        if (request.optString("op") == "panel.release") {
            val released = request.optString("apk") == context.applicationInfo.sourceDir &&
                (runtime?.releasePanel() ?: true)
            val reply = JSONObject().put("released", released).toString().toByteArray()
            DataOutputStream(client.outputStream).apply { writeInt(reply.size); write(reply); flush() }
            return
        }
        if (request.optString("apk") != context.applicationInfo.sourceDir) {
            Handler(Looper.getMainLooper()).post { exitProcess(42) }
            return
        }
        val wanted = request.getString("identity")
        val response = when {
            wanted != identity -> {
                val seed = request.optJSONObject("seed")
                if (seed == null) JSONObject().put("seedNeeded", true)
                else {
                    close()
                    importDashboard(context.filesDir, wanted, seed)
                    identity = wanted
                    start()
                    session()
                }
            }
            else -> {
                when (request.optString("op")) {
                    "attach" -> { PinSession.lock(); runtime?.updates?.resume() }
                    "resume" -> runtime?.updates?.resume()
                    "acc" -> {
                        val on = request.getBoolean("on")
                        runtime?.online?.acc(on)
                        if (!on) PinSession.lock()
                    }
                }
                session()
            }
        }
        request.optJSONObject("logs")?.let {
            response.put("logsThrough", Logs.receive(request.getString("logSource"), it))
        }
        val reply = response.toString().toByteArray()
        DataOutputStream(client.outputStream).apply { writeInt(reply.size); write(reply); flush() }
    }

    @Synchronized
    fun close() {
        runtime?.close()
        runtime = null
    }

    private fun start() {
        runtime = DashboardRuntime(context, Shell(context), vehicle).also { it.start() }
        DaemonLog.d("Online", "Dashboard running as shell, uid ${Process.myUid()}, pid ${Process.myPid()}")
    }

    private fun session(): JSONObject {
        val active = checkNotNull(runtime)
        return JSONObject().put("cookie", active.browsers.nativeCookie())
            .put("pinSet", active.pin.isSet())
    }
}
