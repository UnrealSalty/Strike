package com.strike.daemon

import com.strike.core.Logs
import org.json.JSONObject
import java.util.concurrent.Executor
import java.util.concurrent.Executors

private const val TAG = "Recorder"
private const val ANSWER_WITHIN_MS = 20_000L
private const val STABLE_UPTIME_MS = 3_000L
private const val ASK_EVERY_MS = 500L
private const val FAILURE_TAIL_LINES = 20

enum class Phase { OFF, STARTING, RUNNING, STOPPING, FAILED }

class RecorderDaemon internal constructor(
    private val authorise: () -> Boolean,
    private val prepare: () -> Unit,
    private val launchDaemon: () -> Boolean,
    private val haltDaemon: () -> Boolean,
    private val readStatus: () -> JSONObject?,
    private val lastError: () -> String?,
    private val worker: Executor = Executors.newSingleThreadExecutor {
        Thread(it, "recorder-control").also { thread -> thread.isDaemon = true }
    },
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val pause: (Long) -> Unit = { Thread.sleep(it) }
) {

    constructor(shell: Shell, daemon: Daemon, client: DaemonClient, beforeLaunch: () -> Unit) : this(
        { shell.isAuthorised() || shell.retry() },
        beforeLaunch,
        daemon::start,
        { daemon.stop(client::shutdown) },
        client::status,
        {
            val tail = shell.read("tail -n $FAILURE_TAIL_LINES $CAM_LOG_PATH")
            tail?.let { lastDaemonError(it) }
        }
    )

    @Volatile
    var phase = Phase.OFF
        private set

    @Volatile
    var step = ""
        private set

    @Volatile
    var failure = ""
        private set

    @Volatile
    var canStop = false
        private set

    private var request = 0L

    fun status(): JSONObject? {
        val current = synchronized(this) { request }
        val reply = readStatus()
        synchronized(this) {
            if (current != request || phase == Phase.STOPPING) return null
            if (phase == Phase.STARTING) return reply
            if (reply != null) {
                phase = Phase.RUNNING
                canStop = true
                step = ""
                failure = ""
            } else if (phase == Phase.RUNNING) {
                phase = Phase.STARTING
                step = "Waiting for restart"
                worker.execute { awaitDaemon(current) }
            }
        }
        return reply
    }

    @Synchronized
    fun start() {
        if (phase == Phase.STARTING || phase == Phase.RUNNING || phase == Phase.STOPPING) return
        val current = ++request
        phase = Phase.STARTING
        canStop = true
        failure = ""
        step = "Authorising shell"
        worker.execute { launch(current) }
    }

    @Synchronized
    fun stop() {
        if (phase == Phase.STOPPING) return
        val current = ++request
        phase = Phase.STOPPING
        step = "Stopping"
        worker.execute {
            val stopped = haltDaemon()
            synchronized(this) {
                if (current != request) return@execute
                phase = if (stopped) Phase.OFF else Phase.FAILED
                canStop = !stopped
                step = ""
                failure = if (stopped) "" else "The recorder could not be stopped. Try Stop again"
                if (!stopped) Logs.w(TAG, failure)
            }
        }
    }

    private fun launch(current: Long) {
        if (!isStarting(current)) return
        try {
            if (!authorise()) return fail(current, "Accept the debugging prompt on the head unit", cleanup = false)
            if (!setStep(current, "Writing watchdog")) return
            prepare()
            if (!isStarting(current)) return
            if (!launchDaemon()) return fail(current, "The watchdog could not be started")
            if (!setStep(current, "Waiting for the daemon")) return
            awaitDaemon(current)
        } catch (e: Exception) {
            fail(current, "Recorder setup failed", error = e)
        }
    }

    private fun awaitDaemon(current: Long) {
        val deadline = nowMs() + ANSWER_WITHIN_MS
        while (isStarting(current) && nowMs() < deadline) {
            val reply = readStatus()
            synchronized(this) {
                if (!isStarting(current)) return
                if (reply != null && reply.optLong("uptimeMs") >= STABLE_UPTIME_MS) {
                    phase = Phase.RUNNING
                    step = ""
                    Logs.d(TAG, "daemon answering on 127.0.0.1:$COMMAND_PORT")
                    return
                }
            }
            pause(ASK_EVERY_MS)
        }
        if (isStarting(current)) fail(current, lastError() ?: "The daemon did not stay running")
    }

    private fun fail(current: Long, reason: String, cleanup: Boolean = true, error: Throwable? = null) {
        if (!isStarting(current)) return
        val stopped = !cleanup || haltDaemon()
        synchronized(this) {
            if (!isStarting(current)) return
            failure = if (stopped) reason else "$reason. Stop could not be confirmed. Try Stop again"
            canStop = !stopped
            step = ""
            phase = Phase.FAILED
            Logs.w(TAG, "recorder daemon failed: $failure", error)
        }
    }

    @Synchronized
    private fun isStarting(current: Long): Boolean = current == request && phase == Phase.STARTING

    @Synchronized
    private fun setStep(current: Long, next: String): Boolean {
        if (!isStarting(current)) return false
        step = next
        return true
    }
}

internal fun lastDaemonError(tail: String): String? =
    parseDaemonLog(tail).lastOrNull { it.level == "error" || it.tag == "watchdog" && it.level == "warn" }?.message
