package com.strike.server

import com.strike.BOOT_LOG_MAX_BYTES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class BootLogMirrorTest {
    @get:Rule val temporary = TemporaryFolder()

    private val source = "00000000-0000-0000-0000-000000000001"
    private val restartedSource = "00000000-0000-0000-0000-000000000002"
    private val file by lazy { File(temporary.root, "boot.log") }
    private val mirror by lazy { BootLogMirror(file) }

    @Test
    fun delayedSnapshotsCannotRollBackNewerBootDiagnostics() {
        assertEquals(3L, mirror.save(source, 3L, "newest\n"))

        assertEquals(3L, mirror.save(source, 1L, "older\n"))
        assertEquals(3L, mirror.save(source, 3L, "duplicate with different text\n"))

        assertEquals("newest\n", file.readText())
    }

    @Test
    fun aNewAppProcessCanRestartItsRevisionSequence() {
        mirror.save(source, 50L, "previous process\n")

        assertEquals(0L, mirror.save(restartedSource, 0L, "new process\n"))

        assertEquals("new process\n", file.readText())
        assertEquals(1L, mirror.save(restartedSource, 1L, "new process continued\n"))
        assertEquals("new process continued\n", file.readText())
    }

    @Test
    fun rejectedTextCannotReplaceTheSavedDiagnostics() {
        mirror.save(source, 1L, "saved\n")
        val rejected = listOf(
            "x".repeat(BOOT_LOG_MAX_BYTES + 1),
            "tab\there",
            "carriage\rreturn",
            "null\u0000byte",
            "delete\u007fbyte",
            "non-ASCII \u00e9",
            "supplementary \ud83d\ude00"
        )

        for (text in rejected) {
            assertThrows(IOException::class.java) { mirror.save(source, 2L, text) }
            assertEquals("saved\n", file.readText())
        }
        assertEquals(2L, mirror.save(source, 2L, "accepted\n"))
        assertEquals("accepted\n", file.readText())
    }

    @Test
    fun invalidSourcesAndNegativeRevisionsCannotReplaceTheSavedDiagnostics() {
        mirror.save(source, 1L, "saved\n")
        for (invalid in listOf("", "x".repeat(36), "../boot.log", source + "\n")) {
            assertThrows(IOException::class.java) { mirror.save(invalid, 2L, "rejected\n") }
        }
        assertThrows(IOException::class.java) { mirror.save(source, -1L, "rejected\n") }

        assertEquals("saved\n", file.readText())
    }

    @Test
    fun aFailedWriteDoesNotAdvanceTheAcknowledgedRevision() {
        mirror.save(source, 1L, "saved\n")
        val blocked = File(file.path + ".tmp")
        assertTrue(blocked.mkdir())

        assertThrows(IOException::class.java) { mirror.save(source, 3L, "not saved\n") }
        assertEquals("saved\n", file.readText())
        assertTrue(blocked.delete())

        assertEquals(2L, mirror.save(source, 2L, "next successful write\n"))
        assertEquals("next successful write\n", file.readText())
    }

    @Test
    fun aFailedWriteFromANewProcessCanBeRetriedAtTheSameRevision() {
        mirror.save(source, 9L, "saved\n")
        val blocked = File(file.path + ".tmp")
        assertTrue(blocked.mkdir())

        assertThrows(IOException::class.java) { mirror.save(restartedSource, 0L, "new process\n") }
        assertEquals("saved\n", file.readText())
        assertTrue(blocked.delete())

        assertEquals(0L, mirror.save(restartedSource, 0L, "new process\n"))
        assertEquals("new process\n", file.readText())
    }

    @Test
    fun aSnapshotAtTheByteLimitIsPreservedExactly() {
        val text = "x".repeat(BOOT_LOG_MAX_BYTES - 1) + "\n"

        assertEquals(0L, mirror.save(source, 0L, text))

        assertEquals(BOOT_LOG_MAX_BYTES.toLong(), file.length())
        assertEquals(text, file.readText())
    }
}
