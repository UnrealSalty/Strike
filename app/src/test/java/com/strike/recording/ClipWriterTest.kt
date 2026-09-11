package com.strike.recording

import android.media.MediaFormat
import com.strike.surveillance.EventStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

private const val MINUTE_MS = 60_000L

class ClipWriterTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun clipNameCarriesTheKindAndTheWallClock() {
        val calendar = Calendar.getInstance(TimeZone.getDefault(), Locale.US)
        calendar.set(2026, Calendar.SEPTEMBER, 6, 14, 12, 33)
        calendar.set(Calendar.MILLISECOND, 0)

        assertEquals("drive_20260906_141233.mp4", clipName(RecordingMode.DRIVE, calendar.timeInMillis))
        assertEquals("parked_20260906_141233.mp4", clipName(RecordingMode.PARKED, calendar.timeInMillis))
    }

    @Test
    fun aClipBeingWrittenIsNotYetAClip() {
        assertEquals(null, readClip("drive_20260906_141233.mp4.tmp", 0))
        assertEquals(null, readClip("drive_20260906_141233.mp4.broken", 0))
    }

    @Test
    fun anEventAppearsOnlyAfterItsFileIsFinalized() {
        val writer = ClipWriter(folder.root)
        assertTrue(writer.open(RecordingMode.EVENT, MediaFormat()))
        val clip = writer.name!!
        File(folder.root, "$clip.tmp").writeBytes(byteArrayOf(1, 2, 3))
        val events = EventStore(folder.root)
        assertTrue(events.list().isEmpty())

        assertNotNull(writer.close())

        assertEquals(listOf(clip), events.list().map { it.id })
        assertFalse(File(folder.root, "$clip.tmp").exists())
        assertNull(writer.close())
    }

    @Test
    fun aMissingClipFileIsNotReportedAsSaved() {
        val writer = ClipWriter(folder.root)
        assertTrue(writer.open(RecordingMode.EVENT, MediaFormat()))

        assertNull(writer.close())
        assertTrue(EventStore(folder.root).list().isEmpty())
    }

    @Test
    fun sweepDropsOldHalfWrittenFilesAndKeepsClips() {
        val now = System.currentTimeMillis()
        val unfinished = write("drive_20260906_141233.mp4.tmp", now - 10 * MINUTE_MS)
        val broken = write("drive_20260906_142233.mp4.broken", now - 10 * MINUTE_MS)
        val clip = write("drive_20260906_143233.mp4", now - 10 * MINUTE_MS)

        sweepUnfinished(folder.root, now)

        assertFalse(unfinished.exists())
        assertFalse(broken.exists())
        assertTrue(clip.exists())
    }

    @Test
    fun sweepSparesTheClipTheDaemonIsWritingRightNow() {
        val now = System.currentTimeMillis()
        val inFlight = write("drive_20260906_141233.mp4.tmp", now - MINUTE_MS)

        sweepUnfinished(folder.root, now)

        assertTrue(inFlight.exists())
    }

    private fun write(name: String, modifiedAtMs: Long): File {
        val file = folder.newFile(name)
        file.writeBytes(ByteArray(8))
        file.setLastModified(modifiedAtMs)
        return file
    }
}
