package com.strike.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class DashboardLogTest {
    @Test fun aLargeLogKeepsItsLatestCompleteLinesAndRemainsAppendable() {
        val directory = createTempDirectory().toFile()
        try {
            val log = File(directory, "daemon.log")
            log.writeText("earlier\n".repeat(750_000) + "last connection\n")
            trimDashboardLog(log)
            assertTrue(log.length() <= 65_536L)
            assertTrue(log.readText().startsWith("earlier\n"))
            assertTrue(log.readText().endsWith("last connection\n"))
            log.appendText("next connection\n")
            assertTrue(log.readText().endsWith("last connection\nnext connection\n"))
        } finally { directory.deleteRecursively() }
    }

    @Test fun aShortLogIsPreservedAndAnAbsentLogIsNotCreated() {
        val directory = createTempDirectory().toFile()
        try {
            val log = File(directory, "daemon.log")
            trimDashboardLog(log)
            assertTrue(!log.exists())
            log.writeText("connection\n")
            trimDashboardLog(log)
            assertEquals("connection\n", log.readText())
        } finally { directory.deleteRecursively() }
    }
}
