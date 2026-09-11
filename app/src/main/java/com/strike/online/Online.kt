package com.strike.online

import com.strike.core.Logs
import com.strike.daemon.AccMonitor
import com.strike.vehicle.VehicleSnapshot
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val EVERY_MS = 5_000L

class Online(
    private val settings: OnlineSettings,
    private val accessReady: () -> Boolean,
    private val methods: Map<String, RemoteMethod>,
    private val network: () -> String?,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val report: (String, String) -> Unit = { state, message ->
        if (state == "broken") Logs.w("Online", message) else Logs.d("Online", message)
    },
    private val keepAwake: (Boolean, Boolean, () -> Boolean) -> Boolean = { _, _, _ -> true },
    private val readVehicle: (() -> VehicleSnapshot?)? = null
) {
    private val lock = Object()
    private val ignition = AccMonitor(readVehicle ?: { null }, nowMs)
    private val retry = TunnelRetry()
    private var worker: Thread? = null
    private var child: Process? = null
    private var outputReader: Thread? = null
    private var startedAtMs = 0L
    private var state = "off"
    private var message = "Off"
    private var failureHint: String? = null
    private var revision = 0L
    private var wasAllowed = false
    private var closed = false

    val enabled: Boolean get() = synchronized(lock) { settings.enabled }

    private val method: RemoteMethod get() = methods.getValue(settings.method)

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
        if (closed || (!settings.enabled && child == null) || worker != null || retry.exhausted) return@synchronized
        worker = Thread({ watch() }, "strike-online").also { it.isDaemon = true; it.start() }
    }

    fun vehicle(snapshot: VehicleSnapshot?) {
        if (!enabled) return
        synchronized(lock) {
            ignition.fromApp(snapshot)
            waiting()
            wake()
        }
    }

    fun acc(on: Boolean) {
        synchronized(lock) {
            ignition.edge(on)
            waiting()
            wake()
        }
    }

    fun configure(service: String, name: String, secret: String, mode: String) = synchronized(lock) {
        check(!settings.enabled && child == null) { "Turn off remote access before changing its setup" }
        settings.configure(service, name, secret, mode)
        change("off", "Off")
    }

    fun select(service: String) = synchronized(lock) {
        check(!settings.enabled && child == null) { "Turn off remote access before changing service" }
        settings.select(service)
        change("off", "Off")
    }

    fun enable(enabled: Boolean) = synchronized(lock) {
        if (enabled) {
            check(accessReady()) { "Generate a browser access code in Online first" }
            check(settings.configured) { "Finish the remote access setup first" }
        }
        settings.enable(enabled)
        if (!enabled) wasAllowed = false
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
        check(settings.enabled || child != null) { "Turn on remote access first" }
        retry.reset()
        start()
        wake()
    }

    fun forget() = synchronized(lock) {
        check(!settings.enabled && child == null) { "Turn off remote access before removing its setup" }
        settings.forget()
        change("off", "Off")
    }

    fun status(): JSONObject = synchronized(lock) {
        val services = JSONObject()
        for (service in ONLINE_METHODS) {
            services.put(service, JSONObject().put("name", settings.name(service))
                .put("hasSecret", settings.secret(service).isNotEmpty())
                .put("configured", settings.isConfigured(service)))
        }
        JSONObject().put("enabled", settings.enabled).put("mode", settings.mode)
            .put("method", settings.method).put("methods", services)
            .put("name", settings.name(settings.method))
            .put("hasSecret", settings.secret(settings.method).isNotEmpty())
            .put("configured", settings.configured)
            .put("address", method.address() ?: JSONObject.NULL)
            .put("state", state).put("status", message).put("accessReady", accessReady())
            .put("running", child?.isAlive == true)
            .put("canRetry", state == "broken" && (settings.enabled || child != null))
    }

    fun close() {
        val thread = synchronized(lock) {
            closed = true
            wake()
            worker
        }
        thread?.join(15_000L)
        check(thread?.isAlive != true) { "Remote access is still stopping" }
    }

    private fun watch() {
        var failed = false
        var lastNetwork: String? = null
        var parked = false
        try {
            while (true) {
                val before = synchronized(lock) {
                    if (closed || !settings.enabled) return
                    revision
                }
                if (readVehicle != null) ignition.poll()
                val waitFor = synchronized(lock) {
                    if (!settings.enabled) return
                    when {
                        !accessReady() -> "Generate a browser access code in Online"
                        else -> waiting()
                    }
                }
                val activeNetwork = network()
                if (activeNetwork != lastNetwork) {
                    if (!stopChild()) { failed = true; return }
                    synchronized(lock) { retry.reset() }
                }
                val changed = synchronized(lock) {
                    if (!settings.enabled) return
                    revision != before
                }
                if (changed) continue
                val snapshot = ignition.snapshot()
                parked = when {
                    snapshot?.accOn == true || (snapshot?.gear != null && snapshot.gear != "P") -> false
                    snapshot?.accOn == false -> true
                    else -> parked
                }
                val allowed = waitFor == null && synchronized(lock) { !retry.exhausted }
                keepAwake(allowed, allowed && parked) {
                    synchronized(lock) {
                        val current = ignition.snapshot()
                        !closed && settings.enabled && revision == before && (!parked ||
                            (current?.accOn == false && (current.gear == null || current.gear == "P")))
                    }
                }
                if (waitFor != null || activeNetwork == null) {
                    if (!stopChild()) { failed = true; return }
                    synchronized(lock) {
                        change("waiting", waitFor ?: "Waiting for internet")
                    }
                } else {
                    supervise()
                }
                if (allowed && synchronized(lock) { retry.exhausted }) keepAwake(false, false) { false }
                lastNetwork = activeNetwork
                synchronized(lock) {
                    if (!settings.enabled) return
                    if (revision == before) lock.wait(EVERY_MS)
                }
            }
        } catch (e: InterruptedException) {
            failed = true
        } catch (e: Exception) {
            failed = true
            synchronized(lock) { change("broken", "Remote access stopped unexpectedly. Turn it off and on to retry") }
        } finally {
            stopChild()
            while (true) {
                val beforeRelease = synchronized(lock) { revision }
                if (keepAwake(false, false) { false }) break
                synchronized(lock) {
                    change("stopping", "Releasing the parked display")
                    if (revision == beforeRelease) lock.wait(EVERY_MS)
                }
            }
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
            outputReader?.join(500L)
            synchronized(lock) {
                child = null
                retry.failed(nowMs(), nowMs() - startedAtMs)
                val reason = failureHint ?: "Remote access exited with $exitCode"
                change(if (retry.exhausted) "broken" else "starting",
                    if (retry.exhausted) "$reason. Press Retry" else "$reason. Retrying shortly")
            }
            return
        }
        if (process == null) {
            val service = synchronized(lock) {
                if (!settings.enabled || waiting() != null || !accessReady() ||
                    nowMs() < retry.atMs || retry.exhausted) return
                failureHint = null
                method
            }
            // Slow one-off setup runs off the lock so status reads never wait on it.
            try {
                service.prepare()
            } catch (e: IOException) {
                synchronized(lock) {
                    if (!settings.enabled || retry.exhausted) return
                    retry.failed(nowMs(), 0L)
                    change(if (retry.exhausted) "broken" else "starting",
                        if (retry.exhausted) "Cannot start remote access. Press Retry"
                        else "Cannot start remote access. Retrying shortly")
                }
                return
            }
            synchronized(lock) {
                if (!settings.enabled || retry.exhausted) return
                val blocked = service.problem
                if (blocked != null) {
                    retry.failed(nowMs(), 0L)
                    change(if (retry.exhausted) "broken" else "starting",
                        if (retry.exhausted) "$blocked. Press Retry" else "$blocked. Retrying shortly")
                    return
                }
                try {
                    val opened = service.start()
                    child = opened
                    startedAtMs = nowMs()
                    change("starting", service.connecting)
                    outputReader = Thread({ readErrors(opened, service) }, "strike-tunnel-log").also {
                        it.isDaemon = true
                        it.start()
                    }
                } catch (e: IOException) {
                    retry.failed(nowMs(), 0L)
                    change(if (retry.exhausted) "broken" else "starting",
                        if (retry.exhausted) "Cannot start remote access. Press Retry"
                        else "Cannot start remote access. Retrying shortly")
                }
            }
            return
        }
        val service = synchronized(lock) { method }
        val connected = try {
            service.ready()
        } catch (e: IOException) {
            false
        }
        synchronized(lock) {
            if (child === process && settings.enabled) {
                if (connected) failureHint = null
                change(if (connected) "running" else "starting",
                    if (connected) "Connected"
                    else failureHint ?: service.problem ?: service.connecting)
            }
        }
    }

    private fun readErrors(process: Process, service: RemoteMethod) {
        try {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val hint = service.failure(line) ?: return@forEach
                    synchronized(lock) {
                        if (child === process && failureHint != hint) {
                            failureHint = hint
                            report("broken", hint)
                        }
                    }
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
                change("broken", "Remote access did not stop. Press Retry")
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

    private fun waiting(): String? {
        val reason = tunnelWaiting(settings.mode, ignition.snapshot(), wasAllowed)
        wasAllowed = settings.enabled && reason == null
        return reason
    }

    private fun wake() {
        revision++
        lock.notifyAll()
    }
}
