package com.strike.camera

import com.strike.daemon.DaemonLog
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

internal const val TIMING_REPORT_NS = 30_000_000_000L
private const val GPU_BUSY_PATH = "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage"

internal class Spread {
    private var count = 0
    private var totalNs = 0L
    private var worstNs = 0L

    fun add(ns: Long) {
        count++
        totalNs += ns
        if (ns > worstNs) worstNs = ns
    }

    fun describe(unit: String = "ms", show: (Long) -> String = ::millis): String =
        if (count == 0) "—" else "avg ${show(totalNs / count)} worst ${show(worstNs)} $unit"

    fun reset() {
        count = 0
        totalNs = 0L
        worstNs = 0L
    }
}

internal fun millis(ns: Long): String = String.format(Locale.US, "%.1f", ns / 1_000_000.0)

// The reader thread calls [arrived]; everything else runs on the bus thread.
internal class FrameTiming(
    private val nowNs: () -> Long = System::nanoTime,
    private val gpuBusyPercent: () -> Int? = GpuBusy()::read,
    private val report: (String) -> Unit = { DaemonLog.d("FrameBus", it) }
) {
    private val arrivals = AtomicLong()
    private val cameraGaps = Spread()
    private val clockGaps = Spread()
    private val draws = Spread()
    private var drawn = 0
    private var unorderedStamps = 0
    private var cameraAtNs = 0L
    private var clockAtNs = 0L
    private var reportedAtNs = 0L

    fun arrived() {
        arrivals.incrementAndGet()
    }

    fun drawn(cameraNs: Long, clockNs: Long, drawNs: Long) {
        drawn++
        if (cameraNs > cameraAtNs) {
            if (cameraAtNs != 0L) cameraGaps.add(cameraNs - cameraAtNs)
            cameraAtNs = cameraNs
        } else {
            unorderedStamps++
        }
        if (clockAtNs != 0L) clockGaps.add(clockNs - clockAtNs)
        clockAtNs = clockNs
        draws.add(drawNs)

        val now = nowNs()
        if (reportedAtNs == 0L) reportedAtNs = now
        if (now - reportedAtNs < TIMING_REPORT_NS) return
        val dropped = arrivals.getAndSet(0) - drawn
        val gpu = gpuBusyPercent()?.let { "$it %" } ?: "—"
        val unordered = if (unorderedStamps > 0) ", $unorderedStamps camera stamps out of order" else ""
        report(
            "${(now - reportedAtNs) / 1_000_000_000L} s: $drawn frames drawn, $dropped dropped, " +
                "camera stamp step ${cameraGaps.describe("raw", Long::toString)}, " +
                "our clock gap ${clockGaps.describe()}, " +
                "draw ${draws.describe()}, GPU $gpu$unordered"
        )
        reportedAtNs = now
        drawn = 0
        unorderedStamps = 0
        cameraGaps.reset()
        clockGaps.reset()
        draws.reset()
    }
}

private class GpuBusy {
    private var hasLoggedFailure = false

    fun read(): Int? = try {
        File(GPU_BUSY_PATH).readText().trim().takeWhile { it.isDigit() }.toIntOrNull()
    } catch (e: IOException) {
        if (!hasLoggedFailure) DaemonLog.d("FrameBus", "GPU load unreadable: ${e.message}")
        hasLoggedFailure = true
        null
    }
}
