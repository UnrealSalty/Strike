package com.strike.daemon

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class RecoveryPowerWorker private constructor(
    private val power: RecorderRecoveryPower,
    private val nowMs: () -> Long,
    private val execute: (Runnable) -> Unit,
    private val shutdown: () -> Unit
) {
    constructor(
        power: RecorderRecoveryPower,
        nowMs: () -> Long = SystemClock::uptimeMillis,
        executor: ExecutorService = Executors.newSingleThreadExecutor {
            Thread(it, "recorder-power").apply { isDaemon = true }
        }
    ) : this(power, nowMs, executor::execute, executor::shutdown)

    data class Snapshot(
        val held: Boolean = false,
        val ready: Boolean = true,
        val inFlightSinceMs: Long? = null,
        val lastCompletedAtMs: Long? = null,
        val failure: String? = null
    )

    private val gate = Object()
    private var state = Snapshot()
    private var scheduled = false
    private var requested = 0L
    private var completed = 0L
    private var pending = false
    private var closed = false
    private var closeFinished = false

    fun snapshot(): Snapshot = synchronized(gate) { state }

    fun request() = synchronized(gate) {
        if (!closed) enqueue()
    }

    fun awaitHeld(timeoutMs: Long): Boolean = synchronized(gate) {
        if (closed) return false
        val target = enqueue()
        await(timeoutMs) { closed || completed >= target }
        !closed && completed >= target && state.ready && state.held
    }

    fun close(): Boolean {
        power.cancel()
        return synchronized(gate) {
            closed = true
            val released = closeFinished && state.ready && !state.held
            if (!released) {
                closeFinished = false
                dispatch()
            }
            gate.notifyAll()
            released
        }
    }

    fun awaitClosed(timeoutMs: Long): Boolean {
        close()
        return synchronized(gate) {
            await(timeoutMs) { closeFinished }
            closeFinished && state.ready && !state.held
        }
    }

    private fun enqueue(): Long {
        requested++
        pending = true
        dispatch()
        return requested
    }

    private fun dispatch() {
        if (scheduled) return
        scheduled = true
        state = state.copy(inFlightSinceMs = state.inFlightSinceMs ?: nowMs())
        execute(Runnable(::runOnce))
    }

    private fun runOnce() {
        val closing: Boolean
        val target: Long
        synchronized(gate) {
            closing = closed
            target = requested
            pending = false
        }
        var failure: String? = null
        val ready = try {
            if (closing) power.close() else power.refresh()
        } catch (e: RuntimeException) {
            failure = e.javaClass.simpleName
            false
        }
        synchronized(gate) {
            completed = target
            state = Snapshot(power.isHeld, ready, lastCompletedAtMs = nowMs(), failure = failure)
            scheduled = false
            if (closing) closeFinished = true else if (closed || pending) dispatch()
            gate.notifyAll()
        }
        if (closing && ready && !power.isHeld) shutdown()
    }

    private fun await(timeoutMs: Long, finished: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(0L))
        while (!finished()) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0L) return
            try {
                TimeUnit.NANOSECONDS.timedWait(gate, remaining)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    companion object {
        fun android(power: RecorderRecoveryPower): RecoveryPowerWorker {
            val thread = HandlerThread("recorder-power").apply { start() }
            val handler = Handler(thread.looper)
            return RecoveryPowerWorker(power, SystemClock::uptimeMillis,
                { check(handler.post(it)) { "Recorder power worker has stopped" } },
                { thread.quitSafely() })
        }
    }
}
