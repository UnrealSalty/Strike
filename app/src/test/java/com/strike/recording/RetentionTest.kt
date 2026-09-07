package com.strike.recording

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RetentionTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun keepsEverythingWhenUnderBudget() {
        write("drive_20260901_100000.mp4", 100)
        write("drive_20260902_100000.mp4", 100)

        val store = ClipStore(folder.root)

        assertEquals(0, Retention(store, 500).enforce(null))
        assertEquals(2, store.list().size)
    }

    @Test
    fun dropsOldestUntilUnderBudget() {
        write("drive_20260901_100000.mp4", 100)
        write("drive_20260902_100000.mp4", 100)
        write("parked_20260903_100000.mp4", 100)
        write("manual_20260904_100000.mp4", 100)

        val store = ClipStore(folder.root)

        assertEquals(2, Retention(store, 250).enforce(null))
        assertEquals(
            listOf("manual_20260904_100000.mp4", "parked_20260903_100000.mp4"),
            store.list().map { it.id }
        )
    }

    @Test
    fun neverDropsTheClipBeingWritten() {
        write("drive_20260901_100000.mp4", 100)
        write("drive_20260902_100000.mp4", 100)
        write("drive_20260903_100000.mp4", 100)

        val store = ClipStore(folder.root)

        // The oldest clip is the one being written, so the newer two go instead.
        assertEquals(2, Retention(store, 100).enforce("drive_20260901_100000.mp4"))
        assertEquals(listOf("drive_20260901_100000.mp4"), store.list().map { it.id })
    }

    @Test
    fun leavesForeignFilesAlone() {
        write("drive_20260901_100000.mp4", 100)
        write("drive_20260902_100000.mp4", 100)
        folder.newFile("notes.txt").writeText("keep me")

        Retention(ClipStore(folder.root), 100).enforce(null)

        assertEquals(true, File(folder.root, "notes.txt").isFile)
    }

    @Test
    fun aMissingDirectoryNeedsNoReaping() {
        assertEquals(0, Retention(ClipStore(File(folder.root, "never-mounted")), 0).enforce(null))
    }

    private fun write(name: String, bytes: Int) {
        folder.newFile(name).writeBytes(ByteArray(bytes))
    }
}
