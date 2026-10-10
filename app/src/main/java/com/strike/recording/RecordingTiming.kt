package com.strike.recording

import com.strike.camera.Spread
import com.strike.camera.TIMING_REPORT_NS
import com.strike.camera.millis
import com.strike.daemon.DaemonLog
import java.util.concurrent.atomic.AtomicLong

// The encoder thread calls [encoded]; the writer thread calls [wrote].
internal class RecordingTiming(
    private val queueCapacity: Int,
    private val nowNs: () -> Long = System::nanoTime,
    private val report: (String) -> Unit = { DaemonLog.d("Recorder", it) }
) {
    private val clipGaps = Spread()
    private val slowestWriteNs = AtomicLong()
    private var encoded = 0
    private var queuePeak = 0
    private var frameAtUs = Long.MIN_VALUE
    private var reportedAtNs = 0L

    fun wrote(ns: Long) {
        slowestWriteNs.accumulateAndGet(ns, ::maxOf)
    }

    fun encoded(timeUs: Long, queued: Int) {
        encoded++
        if (frameAtUs != Long.MIN_VALUE && timeUs > frameAtUs) clipGaps.add((timeUs - frameAtUs) * 1_000L)
        frameAtUs = timeUs
        if (queued > queuePeak) queuePeak = queued

        val now = nowNs()
        if (reportedAtNs == 0L) reportedAtNs = now
        if (now - reportedAtNs < TIMING_REPORT_NS) return
        report(
            "${(now - reportedAtNs) / 1_000_000_000L} s: $encoded frames encoded, " +
                "clip gap ${clipGaps.describe()}, queue peak $queuePeak of $queueCapacity, " +
                "slowest write ${millis(slowestWriteNs.getAndSet(0))} ms"
        )
        reportedAtNs = now
        encoded = 0
        queuePeak = 0
        clipGaps.reset()
    }
}
