package com.strike.server

import android.content.Context
import android.os.Handler
import android.os.Looper
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
    private val identity = if (identityFile.isFile) identityFile.readText() else
        UUID.randomUUID().toString().also { atomicDashboardWrite(identityFile, it) }
    private var bootstrap: DashboardRuntime? = null
    private var onSession: ((String, Boolean) -> Unit)? = null
    private var session: JSONObject? = null
    private val logSource = UUID.randomUUID().toString()
    private var logsThrough = 0L

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

    private fun connect(force: Boolean = false): Boolean {
        try {
            val current = exchange(JSONObject().put("op", "attach"))
            if (current != null) {
                finish(current, force)
                return true
            }
            if (bootstrap == null && !migrated.isFile) {
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
                if (local.updates.status().optBoolean("busy") || local.updates.isInstalling()) {
                    if (force) session?.let { publish(it, force = true) }
                    return true
                }
                local.close()
            }
            bootstrap = null
            check(launchDashboard(context, shell)) { "Could not start the dashboard daemon" }
            repeat(30) {
                val opened = exchange(JSONObject().put("op", "attach"))
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
            exchange(JSONObject().put("seed", dashboardSeed(context.filesDir)))
                ?: throw IOException("Dashboard setup was interrupted")
        } else reply
        check(!opened.optBoolean("seedNeeded"))
        if (!migrated.isFile) atomicDashboardWrite(migrated, identity)
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
        val bytes = request.put("identity", identity).put("apk", context.applicationInfo.sourceDir)
            .toString().toByteArray()
        require(bytes.size <= DASHBOARD_MAX_BYTES)
        val reply = shell.exchangeLocal(context.applicationInfo.sourceDir, bytes, DASHBOARD_MAX_BYTES)
            ?: return null
        return JSONObject(reply.toString(Charsets.UTF_8)).also {
            logsThrough = maxOf(logsThrough, it.optLong("logsThrough"))
        }
    }
}
