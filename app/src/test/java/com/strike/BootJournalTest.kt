package com.strike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class BootJournalTest {
    @get:Rule val temporary = TemporaryFolder()
    private val saved: File get() = File(temporary.root, "boot.log")

    @Test fun aMissingJournalHasAnEmptyInitialSnapshot() {
        val journal = BootJournal(saved)
        assertEquals(BootLogSnapshot(0L, ""), journal.snapshot(-1L))
        assertNull(journal.snapshot(0L))
        assertFalse(saved.exists())
    }

    @Test fun recordsSurviveANewInstanceWhileVersionsBelongToTheWriter() {
        val first = BootJournal(saved)
        first.append("broadcast received")
        first.append("recovery scheduled")
        val history = "broadcast received\nrecovery scheduled\n"
        assertEquals(history, saved.readText())
        assertEquals(BootLogSnapshot(2L, history), first.snapshot(0L))
        assertNull(first.snapshot(2L))

        val restarted = BootJournal(saved)
        assertEquals(BootLogSnapshot(0L, history), restarted.snapshot(-1L))
        restarted.append("job started")
        assertEquals(BootLogSnapshot(1L, history + "job started\n"), restarted.snapshot(0L))
    }

    @Test fun retentionDropsOnlyTheOldestCompleteRecords() {
        val journal = BootJournal(saved)
        val records = List(80) { "attempt $it " + "x".repeat(590) }
        for (record in records) {
            journal.append(record)
            assertTrue(saved.length() <= BOOT_LOG_MAX_BYTES)
        }
        val text = journal.snapshot(-1L)!!.text
        val retained = text.removeSuffix("\n").split('\n')
        assertTrue(text.endsWith('\n'))
        assertTrue(retained.size < records.size)
        assertEquals(records.takeLast(retained.size), retained)
        assertEquals(text, saved.readText())
        val previousRecord = records[records.size - retained.size - 1]
        assertTrue(text.length + previousRecord.length + 1 > BOOT_LOG_MAX_BYTES)
    }

    @Test fun concurrentWritersKeepEveryDistinctRecordAndVersion() {
        val journal = BootJournal(saved)
        val writers = 6
        val perWriter = 20
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(writers)
        try {
            val jobs = (0 until writers).map { writer ->
                executor.submit {
                    start.await()
                    repeat(perWriter) { journal.append("writer=$writer record=$it") }
                }
            }
            start.countDown()
            jobs.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
        val snapshot = journal.snapshot(-1L)!!
        val records = snapshot.text.removeSuffix("\n").split('\n')
        val expected = (0 until writers).flatMap { writer ->
            (0 until perWriter).map { "writer=$writer record=$it" }
        }
        assertEquals(expected.size, records.size)
        assertEquals(expected.toSet(), records.toSet())
        assertEquals(expected.size.toLong(), snapshot.version)
        assertEquals(snapshot.text, saved.readText())
    }

    @Test fun failedWritesPreserveThePublishedHistoryAndVersion() {
        val journal = BootJournal(saved)
        journal.append("saved")
        val pending = File(saved.path + ".tmp")
        assertTrue(pending.mkdir())
        val occupied = File(pending, "occupied").also { it.writeText("held") }
        try {
            journal.append("not saved")
            fail("A blocked journal write must throw IOException")
        } catch (expected: IOException) {
            assertEquals("saved\n", saved.readText())
            assertNull(journal.snapshot(1L))
            assertEquals(BootLogSnapshot(1L, "saved\n"), journal.snapshot(0L))
        }
        assertTrue(occupied.delete())
        assertTrue(pending.delete())
        journal.append("retry saved")
        assertEquals(BootLogSnapshot(2L, "saved\nretry saved\n"), journal.snapshot(1L))
    }

    @Test fun inputCannotInjectControlCharactersOrExtraRecords() {
        val journal = BootJournal(saved)
        journal.append("boot\r\nforged\t\u0000\u001b\u007f\u00e9")
        journal.append("x".repeat(2000) + "\nforged")
        val records = saved.readText().removeSuffix("\n").split('\n')
        assertEquals(2, records.size)
        assertEquals("boot  forged     ", records[0])
        assertEquals("x".repeat(1024), records[1])
        assertTrue(saved.readBytes().all { it == 10.toByte() || it.toInt() in 32..126 })
    }

    @Test fun aHugeExistingFileReturnsOnlyCompleteRecordsFromItsBoundedTail() {
        val records = List(100) { "old attempt $it " + "x".repeat(500) }
        RandomAccessFile(saved, "rw").use { output ->
            output.setLength(8L * 1024 * 1024)
            output.seek(output.length())
            output.write(("\n" + records.joinToString("\n") + "\n").toByteArray())
        }
        val journal = BootJournal(saved)
        val text = journal.snapshot(-1L)!!.text
        val retained = text.removeSuffix("\n").split('\n')
        assertTrue(text.length <= BOOT_LOG_MAX_BYTES)
        assertTrue(retained.size < records.size)
        assertEquals(records.takeLast(retained.size), retained)
        journal.append("recovered")
        assertTrue(saved.length() <= BOOT_LOG_MAX_BYTES)
        assertTrue(saved.readText().endsWith("recovered\n"))
    }

    @Test fun aTailStartingAtARecordBoundaryKeepsThatWholeRecord() {
        val records = "x\n".repeat(BOOT_LOG_MAX_BYTES / 2)
        saved.writeText("older\n" + records)
        assertEquals(records, BootJournal(saved).snapshot(-1L)!!.text)
    }

    @Test fun anInterruptedLastRecordIsDiscardedBeforeTheNextAppend() {
        saved.writeText("broadcast received\njob started\ninterrupted")
        val journal = BootJournal(saved)
        val complete = "broadcast received\njob started\n"
        assertEquals(BootLogSnapshot(0L, complete), journal.snapshot(-1L))
        journal.append("recovery resumed")
        assertEquals(complete + "recovery resumed\n", saved.readText())
    }

    @Test fun corruptAndUnterminatedExistingRecordsCannotEscapeTheBounds() {
        saved.writeBytes(byteArrayOf(0, 27, -1, 10) + "x".repeat(2000).toByteArray() + byteArrayOf(10))
        val journal = BootJournal(saved)
        assertEquals("   \n" + "x".repeat(1024) + "\n", journal.snapshot(-1L)!!.text)
        saved.writeText("x".repeat(BOOT_LOG_MAX_BYTES * 2))
        assertEquals("", journal.snapshot(-1L)!!.text)
        journal.append("fresh")
        assertEquals("fresh\n", saved.readText())
    }

    @Test fun anAbandonedTemporaryWriteDoesNotReplacePublishedRecords() {
        saved.writeText("published\n")
        File(saved.path + ".tmp").writeText("interrupted\n")
        val journal = BootJournal(saved)
        assertEquals(BootLogSnapshot(0L, "published\n"), journal.snapshot(-1L))
        journal.append("next")
        assertEquals("published\nnext\n", saved.readText())
        assertFalse(File(saved.path + ".tmp").exists())
    }
}
