package com.strike.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ClipStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun nameCarriesKindDateAndTime() {
        val clip = readClip("drive_20260905_184530.mp4", 1024)!!
        assertEquals(RecordingMode.DRIVE, clip.mode)
        assertEquals("2026-09-05", clip.date)
        assertEquals("18:45", clip.time)
        assertEquals(1024L, clip.bytes)
    }

    @Test
    fun rotationSuffixKeepsTheKind() {
        assertEquals(RecordingMode.PARKED, readClip("parked_20260905_184530_2.mp4", 0)!!.mode)
    }

    @Test
    fun everyKindIsRecognised() {
        assertEquals(RecordingMode.DRIVE, readClip("drive_20260905_184530.mp4", 0)!!.mode)
        assertEquals(RecordingMode.PARKED, readClip("parked_20260905_184530.mp4", 0)!!.mode)
        assertEquals(RecordingMode.MANUAL, readClip("manual_20260905_184530.mp4", 0)!!.mode)
    }

    @Test
    fun foreignFilesAreNotClips() {
        assertNull(readClip("thumbs.db", 0))
        assertNull(readClip("cam_20260905_184530.mp4", 0))
        assertNull(readClip("drive_20260905_184530.txt", 0))
        assertNull(readClip("drive_2026095_184530.mp4", 0))
    }

    @Test
    fun impossibleStampIsNotAClip() {
        assertNull(readClip("drive_20261305_184530.mp4", 0))
        assertNull(readClip("drive_20260905_996530.mp4", 0))
    }

    @Test
    fun listIsNewestFirstAndSkipsStrangers() {
        write("drive_20260905_180000.mp4")
        write("manual_20260905_193000.mp4")
        write("parked_20260904_090000.mp4")
        write("notes.txt")

        val clips = ClipStore(folder.root).list()

        assertEquals(3, clips.size)
        assertEquals("manual_20260905_193000.mp4", clips[0].id)
        assertEquals("drive_20260905_180000.mp4", clips[1].id)
        assertEquals("parked_20260904_090000.mp4", clips[2].id)
    }

    @Test
    fun aMissingDirectoryListsNothing() {
        assertTrue(ClipStore(File(folder.root, "never-mounted")).list().isEmpty())
    }

    @Test
    fun onlyRealClipsResolveToAFile() {
        write("drive_20260905_180000.mp4")
        val store = ClipStore(folder.root)

        assertEquals("drive_20260905_180000.mp4", store.file("drive_20260905_180000.mp4")!!.name)
        assertNull(store.file("drive_20260905_999999.mp4"))
        assertNull(store.file("../../local.properties"))
    }

    private fun write(name: String) {
        folder.newFile(name).writeText(name)
    }
}
