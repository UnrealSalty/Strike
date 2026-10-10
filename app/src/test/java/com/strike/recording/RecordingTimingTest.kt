package com.strike.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingTimingTest {

    @Test
    fun aReportEveryThirtySecondsShowsClipGapsQueuePeakAndSlowestWrite() {
        var nowNs = 1_000L
        val reports = ArrayList<String>()
        val timing = RecordingTiming(240, { nowNs }, { reports.add(it) })
        var timeUs = 0L
        for (frame in 1..451) {
            timeUs += if (frame == 100) 133_333L else 66_667L
            nowNs += 66_666_667L
            timing.wrote(if (frame == 300) 41_200_000L else 2_000_000L)
            timing.encoded(timeUs, if (frame == 120) 7 else 1)
        }
        assertEquals(
            listOf(
                "30 s: 451 frames encoded, clip gap avg 66.8 worst 133.3 ms, queue peak 7 of 240, " +
                    "slowest write 41.2 ms"
            ),
            reports
        )
    }
}
