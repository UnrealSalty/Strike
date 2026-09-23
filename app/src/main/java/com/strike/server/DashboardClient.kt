package com.strike.server

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.strike.BootDiagnostics
import com.strike.prepareBootAccess
import com.strike.core.Logs
import com.strike.core.PinSession
import com.strike.daemon.Shell
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors

internal class DashboardClient(private val context: Context, private val shell: Shell) {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "dashboard-client").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private val identityFile = File(context.filesDir, "dashboard.identity")
    private val migrated = File(context.filesDir, "dashboard.migrated")
    private val identity = if (identityFile.isFile) identityFile.readText() else {
        check(!context.isDeviceProtectedStorage) { "The saved boot dashboard identity is unavailable" }
        UUID.randomUUID().toString().also { atomicDashboardWrite(identityFile, it) }
    }
    private var bootstrap: DashboardRuntime? = null
    private var onSession: ((String, Boolean) -> Unit)? = null
    private var session: JSONObject? = null
    private val logSource = UUID.randomUUID().toString()
    private var logsThrough = 0L
    private var bootLogVersion = -1L

    fun observe(callback: (String, Boolean) -> Unit) {
        worker.execute {
            onSession = callback
            session?.let { publish(it, force = true) }
            connect()
        }
    }

    fun authorised() = worker.execute { connect() }

    fun reconnect(completed: (Boolean) -> Unit) = worker.execute {
        val ready = connect(force = true)
        main.post { completed(ready) }
    }

    fun restoreAfterBoot(active: () -> Boolean, completed: (Boolean) -> Unit) = worker.execute {
        if (!active()) return@execute
        var stage = "shell"
        val startedAtMs = SystemClock.elapsedRealtime()
        BootDiagnostics.record("recovery attempt started")
        val ready = try {
            shell.retry()
            val deadline = SystemClock.elapsedRealtime() + 60_000L
            while (active() && !shell.isAuthorised() && shell.isPending && SystemClock.elapsedRealtime() < deadline) {
                Thread.sleep(500L)
            }
            if (!active() || !shell.isAuthorised()) {
                BootDiagnostics.record("shell not ready pending=${shell.isPending} active=${active()}")
                false
            } else {
                stage = "dashboard"
                BootDiagnostics.record("shell authorised; connecting dashboard")
                if (!connect(active = active) || bootstrap != null) false else {
                    stage = "recorder"
                    BootDiagnostics.record("dashboard connected; waiting for recorder recovery")
                    awaitBootRecovery(active, { start ->
                        exchange(JSONObject().put("op", "boot").put("start", start))?.optBoolean("ready") == true
                    })
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } catch (e: Exception) {
            BootDiagnostics.record("recovery exception stage=$stage type=${e.javaClass.simpleName}")
            Logs.w("Boot", "Could not restore Strike after head unit restart", e)
            false
        }
        BootDiagnostics.record("recovery ended stage=$stage ready=$ready active=${active()} " +
            "durationMs=${SystemClock.elapsedRealtime() - startedAtMs}")
        main.post { completed(ready) }
    }

    fun flushBootDiagnostics() = worker.execute {
        try {
            exchange(JSONObject().put("op", "diagnostics"))
        } catch (e: Exception) {
            Logs.w("Boot", "Boot diagnostics remain saved on this device")
        }
    }

    fun resume(callback: (Boolean) -> Unit) = worker.execute {
        val reply = exchange(JSONObject().put("op", "resume"))
        val set = reply?.optBoolean("pinSet", true) ?: bootstrap?.pin?.isSet() ?: true
        main.post { callback(set) }
        if (reply != null && !reply.optBoolean("seedNeeded")) {
            publish(reply)
        } else if (bootstrap == null) connect()
    }

    fun acc(on: Boolean) = worker.execute {
        bootstrap?.online?.acc(on)
        if (!on) PinSession.lock()
        exchange(JSONObject().put("op", "acc").put("on", on))
    }

    private fun connect(force: Boolean = false, active: () -> Boolean = { true }): Boolean {
        if (!active()) return false
        try {
            val current = exchange(JSONObject().put("op", "attach"))
            if (!active()) return false
            if (current != null) {
                finish(current, force)
                return true
            }
            if (bootstrap == null && !migrated.isFile && !context.isDeviceProtectedStorage) {
                bootstrap = DashboardRuntime(context, shell).also { it.start(false) }
                publish(JSONObject().put("cookie", bootstrap!!.browsers.nativeCookie())
                    .put("pinSet", bootstrap!!.pin.isSet()))
            }
            if (!shell.isAuthorised()) {
                if (force && bootstrap != null) session?.let { publish(it, force = true) }
                return bootstrap != null
            }
            val local = bootstrap
            if (local != null) {
                // The shell dashboard owns recovery of an inherited install record.
                if (!local.updates.awaitIdle(0L)) {
                    if (force) session?.let { publish(it, force = true) }
                    return true
                }
                local.close()
            }
            bootstrap = null
            if (!active()) return false
            check(launchDashboard(context, shell)) { "Could not start the dashboard daemon" }
            repeat(30) {
                if (!active()) return false
                val opened = exchange(JSONObject().put("op", "attach"))
                if (!active()) return false
                if (opened != null) {
                    finish(opened, force)
                    return true
                }
                Thread.sleep(500L)
            }
            Logs.w("Online", "The dashboard daemon did not answer. Reopen Strike to retry")
        } catch (e: Exception) {
            Logs.w("Online", "Could not move the dashboard to shell. Reopen Strike to retry", e)
        }
        return false
    }

    private fun finish(reply: JSONObject, force: Boolean) {
        bootstrap?.close()
        bootstrap = null
        val opened = if (reply.optBoolean("seedNeeded")) {
            check(!context.isDeviceProtectedStorage) { "Dashboard setup must finish after Android unlocks" }
            exchange(JSONObject().put("seed", dashboardSeed(context.filesDir)))
                ?: throw IOException("Dashboard setup was interrupted")
        } else reply
        check(!opened.optBoolean("seedNeeded"))
        if (!migrated.isFile) atomicDashboardWrite(migrated, identity)
        if (!context.isDeviceProtectedStorage) prepareBootAccess(context)
        publish(opened, force)
    }

    private fun publish(reply: JSONObject, force: Boolean = false) {
        val changed = reply.optString("cookie") != session?.optString("cookie")
        session = reply
        if (!changed && !force) return
        val callback = onSession ?: return
        val cookie = reply.getString("cookie")
        val set = reply.getBoolean("pinSet")
        main.post { callback(cookie, set) }
    }

    private fun exchange(request: JSONObject): JSONObject? {
        if (!request.has("seed")) Logs.batch(logsThrough)?.let {
            request.put("logSource", logSource).put("logs", it)
        }
        if (!request.has("seed")) BootDiagnostics.snapshot(bootLogVersion)?.let {
            request.put("bootLog", JSONObject().put("source", BootDiagnostics.source)
                .put("version", it.version).put("text", it.text))
        }
        val bytes = request.put("identity", identity).put("apk", context.applicationInfo.sourceDir)
            .toString().toByteArray()
        require(bytes.size <= DASHBOARD_MAX_BYTES)
        val reply = shell.exchangeLocal(context.applicationInfo.sourceDir, bytes, DASHBOARD_MAX_BYTES)
            ?: return null
        return JSONObject(reply.toString(Charsets.UTF_8)).also {
            logsThrough = maxOf(logsThrough, it.optLong("logsThrough"))
            bootLogVersion = maxOf(bootLogVersion, it.optLong("bootLogVersion", -1L))
        }
    }
}

internal fun awaitBootRecovery(
    active: () -> Boolean,
    ready: (Boolean) -> Boolean,
    nowMs: () -> Long = { SystemClock.elapsedRealtime() },
    pause: (Long) -> Unit = { Thread.sleep(it) }
): Boolean {
    val deadline = nowMs() + 30_000L
    var start = true
    while (active() && nowMs() <= deadline) {
        if (ready(start)) return active()
        start = false
        val remainingMs = deadline - nowMs()
        if (!active() || remainingMs <= 0L) return false
        pause(minOf(500L, remainingMs))
    }
    return false
}
