package com.strike.online

import com.strike.core.Logs
import com.strike.daemon.AccMonitor
import com.strike.vehicle.VehicleSnapshot
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val EVERY_MS = 5_000L

class Online(
    private val settings: TunnelSettings,
    private val accessReady: () -> Boolean,
    private val launch: (String) -> Process,
    private val ready: () -> Boolean,
    private val network: () -> Boolean,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val report: (String, String) -> Unit = { state, message ->
        if (state == "broken") Logs.w("Online", message) else Logs.d("Online", message)
    },
    private val keepAlive: (Boolean) -> Unit = {}
) {
    private val lock = Object()
    private val ignition = AccMonitor({ null }, nowMs)
    private val retry = TunnelRetry()
    private var worker: Thread? = null
    private var child: Process? = null
    private var startedAtMs = 0L
    private var state = "off"
    private var message = "Off"
    private var failureHint: String? = null
    private var revision = 0L

    val enabled: Boolean get() = synchronized(lock) { settings.enabled }

    fun restore() {
        if (!enabled) return
        try {
            enable(true)
        } catch (e: IOException) {
            synchronized(lock) { change("broken", "Cannot resume remote access. Check its setup") }
        } catch (e: IllegalStateException) {
            synchronized(lock) { change("broken", "Cannot resume remote access. Turn it on to retry") }
        }
    }

    fun start() = synchronized(lock) {
        if ((!settings.enabled && child == null) || worker != null || retry.exhausted) return@synchronized
        worker = Thread({ watch() }, "strike-online").also { it.isDaemon = true; it.start() }
    }

    fun vehicle(snapshot: VehicleSnapshot?) {
        if (!enabled) return
        ignition.fromApp(snapshot)
        synchronized(lock) { wake() }
    }

    fun acc(on: Boolean) {
        ignition.edge(on)
        synchronized(lock) { wake() }
    }

    fun configure(hostname: String, token: String, mode: String) = synchronized(lock) {
        check(!settings.enabled && child == null) { "Turn off the tunnel before changing its setup" }
        settings.configure(hostname, token, mode)
        change("off", "Off")
    }

    fun enable(enabled: Boolean) = synchronized(lock) {
        if (enabled) {
            check(accessReady()) { "Generate a browser access code in Online first" }
            check(settings.hostname.isNotEmpty() && validTunnelToken(settings.token)) {
                "Save your hostname and tunnel token first"
            }
        }
        settings.enable(enabled)
        try {
            keepAlive(enabled)
        } catch (e: RuntimeException) {
            settings.enable(false)
            wake()
            throw IllegalStateException("Android could not keep remote access running")
        }
        retry.reset()
        if (enabled) {
            change("starting", "Starting")
            start()
        } else {
            change(if (child == null) "off" else "stopping", if (child == null) "Off" else "Stopping")
            start()
        }
        wake()
    }

    fun retry() = synchronized(lock) {
        check(settings.enabled || child != null) { "Turn on the tunnel first" }
        if (settings.enabled) keepAlive(true)
        retry.reset()
        start()
        wake()
    }

    fun forget() = synchronized(lock) {
        check(!settings.enabled && child == null) { "Turn off the tunnel before removing its setup" }
        settings.forget()
        change("off", "Off")
    }

    fun status(): JSONObject = synchronized(lock) {
        JSONObject().put("enabled", settings.enabled).put("mode", settings.mode)
            .put("hostname", settings.hostname).put("hasToken", settings.token.isNotEmpty())
            .put("state", state).put("status", message).put("accessReady", accessReady())
            .put("running", child?.isAlive == true)
            .put("canRetry", state == "broken" && (settings.enabled || child != null))
    }

    private fun watch() {
        var failed = false
        try {
            while (true) {
                val before = synchronized(lock) { revision }
                val waitFor = synchronized(lock) {
                    if (!settings.enabled) return
                    when {
                        !accessReady() -> "Generate a browser access code in Online"
                        else -> tunnelWaiting(settings.mode, ignition.snapshot())
                    }
                }
                if (waitFor != null || !network()) {
                    if (!stopChild()) { failed = true; return }
                    synchronized(lock) {
                        change("waiting", waitFor ?: "Waiting for internet")
                    }
                } else {
                    supervise()
                }
                synchronized(lock) {
                    if (!settings.enabled || retry.exhausted) return
                    if (revision == before) lock.wait(EVERY_MS)
                }
            }
        } catch (e: InterruptedException) {
            failed = true
        } catch (e: Exception) {
            failed = true
            synchronized(lock) { change("broken", "Tunnel stopped unexpectedly. Turn it off and on to retry") }
        } finally {
            stopChild()
            synchronized(lock) {
                worker = null
                if (!settings.enabled && child == null) change("off", "Off")
                if (settings.enabled && child == null && !failed && !retry.exhausted) start()
            }
        }
    }

    private fun supervise() {
        val process = synchronized(lock) { child }
        if (process != null && !process.isAlive) {
            val exitCode = process.exitValue()
            synchronized(lock) {
                child = null
                retry.failed(nowMs(), nowMs() - startedAtMs)
                val reason = failureHint ?: "Tunnel exited with $exitCode"
                change(if (retry.exhausted) "broken" else "starting",
                    if (retry.exhausted) "$reason. Press Retry" else "$reason. Retrying shortly")
            }
            return
        }
        if (process == null) {
            synchronized(lock) {
                if (!settings.enabled || nowMs() < retry.atMs || retry.exhausted) return
                failureHint = null
                try {
                    val opened = launch(settings.token)
                    child = opened
                    startedAtMs = nowMs()
                    change("starting", "Connecting to Cloudflare")
                    Thread({ readErrors(opened) }, "strike-tunnel-log").also {
                        it.isDaemon = true
                        it.start()
                    }
                } catch (e: IOException) {
                    retry.failed(nowMs(), 0L)
                    change(if (retry.exhausted) "broken" else "starting",
                        if (retry.exhausted) "Cannot start the tunnel. Press Retry"
                        else "Cannot start the tunnel. Retrying shortly")
                }
            }
            return
        }
        val connected = ready()
        synchronized(lock) {
            if (child === process && settings.enabled) {
                change(if (connected) "running" else "starting",
                    if (connected) "Connected" else failureHint ?: "Connecting to Cloudflare")
            }
        }
    }

    private fun readErrors(process: Process) {
        try {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val hint = tunnelError(line) ?: return@forEach
                    synchronized(lock) { if (child === process) failureHint = hint }
                }
            }
        } catch (e: IOException) {
            // Stopping the process closes its output pipe.
        }
    }

    private fun stopChild(): Boolean {
        val process = synchronized(lock) { child } ?: return true
        process.destroy()
        if (!process.waitFor(3, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor(3, TimeUnit.SECONDS)
        }
        return synchronized(lock) {
            if (process.isAlive) {
                change("broken", "Tunnel did not stop. Press Retry")
                false
            } else {
                if (child === process) child = null
                true
            }
        }
    }

    private fun change(state: String, message: String) {
        if (this.state == state && this.message == message) return
        this.state = state
        this.message = message
        report(state, message)
    }

    private fun wake() {
        revision++
        lock.notifyAll()
    }
}

internal fun tunnelError(line: String): String? = when {
    line.contains("Unauthorized", true) || line.contains("invalid tunnel secret", true) ||
        line.contains("Invalid tunnel token", true) -> "Cloudflare rejected the token. Update the setup"
    line.contains("x509:", true) -> "Cloudflare certificate check failed. Check the car's date and time"
    line.contains("no such host", true) -> "Cloudflare could not be resolved. Check the car's connection"
    else -> null
}
