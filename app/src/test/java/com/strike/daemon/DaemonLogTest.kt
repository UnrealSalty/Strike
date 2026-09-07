package com.strike.daemon

import org.junit.Assert.assertEquals
import org.junit.Test

class DaemonLogTest {

    @Test
    fun readsBackTheLinesTheDaemonWrote() {
        val lines = parseDaemonLog("1757150000000 debug Recorder started\n1757150001000 warn Camera busy\n")
        assertEquals(2, lines.size)
        assertEquals(1757150000000L, lines[0].atMs)
        assertEquals("debug", lines[0].level)
        assertEquals("Recorder", lines[0].tag)
        assertEquals("started", lines[0].message)
        assertEquals("warn", lines[1].level)
    }

    @Test
    fun keepsACrashTraceInPlaceUnderTheLineItFollows() {
        val lines = parseDaemonLog(
            "1757150000000 error Recorder open failed\n" +
                "java.lang.RuntimeException: camera busy\n" +
                "\tat android.hardware.AVMCamera.open(AVMCamera.java:1)\n"
        )
        assertEquals(3, lines.size)
        assertEquals(1757150000000L, lines[1].atMs)
        assertEquals(1757150000000L, lines[2].atMs)
        assertEquals("daemon", lines[1].tag)
    }

    @Test
    fun dropsBlankLines() {
        assertEquals(1, parseDaemonLog("\n\n1757150000000 debug Recorder up\n\n").size)
    }
}
