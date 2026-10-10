package com.strike.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameTimingTest {

    @Test
    fun aReportEveryThirtySecondsCountsDropsGapsAndDrawTime() {
        var nowNs = 1_000L
        val reports = ArrayList<String>()
        val timing = FrameTiming({ nowNs }, { 63 }, { reports.add(it) })
        val frameNs = 66_666_667L
        for (frame in 1..451) {
            timing.arrived()
            if (frame % 10 == 0) timing.arrived()
            nowNs += frameNs
            timing.drawn(frame * frameNs, nowNs, if (frame == 200) 30_000_000L else 8_000_000L)
        }
        assertEquals(
            listOf(
                "30 s: 451 frames drawn, 45 dropped, camera stamp step avg 66666667 worst 66666667 raw, " +
                    "our clock gap avg 66.7 worst 66.7 ms, draw avg 8.0 worst 30.0 ms, GPU 63 %"
            ),
            reports
        )
    }

    @Test
    fun cameraStampsThatRepeatAreCountedInsteadOfMeasured() {
        var nowNs = 1_000L
        val reports = ArrayList<String>()
        val timing = FrameTiming({ nowNs }, { null }, { reports.add(it) })
        for (frame in 1..31) {
            timing.arrived()
            nowNs += 1_000_000_000L
            timing.drawn(if (frame % 2 == 0) 0L else frame * 1_000_000_000L, nowNs, 1_000_000L)
        }
        assertEquals(
            listOf(
                "30 s: 31 frames drawn, 0 dropped, camera stamp step avg 2000000000 worst 2000000000 raw, " +
                    "our clock gap avg 1000.0 worst 1000.0 ms, draw avg 1.0 worst 1.0 ms, GPU —, " +
                    "15 camera stamps out of order"
            ),
            reports
        )
    }
}
