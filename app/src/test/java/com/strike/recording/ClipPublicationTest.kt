package com.strike.recording

import com.strike.surveillance.EventStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

class ClipPublicationTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun publicationRequiresAnUnchangedIdentifiableSource() {
        val clip = clip()
        val original = attributes(clip)
        val scratch = scratch()
        val identifiable = original.fileKey() != null
        assertEquals(identifiable, replaceIndexedClip(scratch, clip, original))
        assertEquals(if (identifiable) "indexed" else "source", clip.readText())
        assertEquals(!identifiable, scratch.exists())
    }

    @Test fun deletingDuringTheCopyCannotRestoreARecordingOrEvent() {
        for (event in listOf(false, true)) {
            val clip = clip(if (event) "event" else "drive")
            val original = attributes(clip)
            val scratch = scratch()
            val deleted = if (event) EventStore(folder.root).delete(clip.name)
                          else ClipStore(folder.root).delete(clip.name)
            assertTrue(deleted)
            assertFalse(replaceIndexedClip(scratch, clip, original))
            assertFalse(clip.exists())
            assertEquals("indexed", scratch.readText())
            scratch.delete()
        }
    }

    @Test fun aNewClipWithTheSameNameSizeAndTimestampIsNotOverwritten() {
        val clip = clip()
        val original = attributes(clip)
        val scratch = scratch()
        val replacement = folder.newFile("replacement").also { it.writeText("second") }
        Files.setLastModifiedTime(replacement.toPath(), original.lastModifiedTime())
        withClipFileLock(clip) {
            Files.move(replacement.toPath(), clip.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        assertEquals(original.size(), clip.length())
        assertEquals(original.lastModifiedTime(), attributes(clip).lastModifiedTime())
        assertFalse(replaceIndexedClip(scratch, clip, original))
        assertEquals("second", clip.readText())
    }

    @Test fun aClipStillChangingIsNotReplacedWithAnOlderCopy() {
        val clip = clip()
        val original = attributes(clip)
        clip.appendText("more")
        assertFalse(replaceIndexedClip(scratch(), clip, original))
        assertEquals("sourcemore", clip.readText())
    }

    @Test fun deletionAfterPublicationRemovesTheIndexedClip() {
        val clip = clip()
        val original = attributes(clip)
        assertEquals(original.fileKey() != null, replaceIndexedClip(scratch(), clip, original))
        assertTrue(ClipStore(folder.root).delete(clip.name))
        assertFalse(clip.exists())
    }

    private fun clip(mode: String = "drive"): File =
        folder.newFile("${mode}_20260916_180500.mp4").also { it.writeText("source") }

    private fun scratch(): File = File(folder.root, "clip.fast").also { it.writeText("indexed") }

    private fun attributes(file: File): BasicFileAttributes =
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
}
