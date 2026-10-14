package com.strike.recording

import com.strike.surveillance.EventStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

private const val NOW_MS = 10_000_000L

class ClipMoveTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun clip(dir: File, name: String, bytes: ByteArray, ageMs: Long = 120_000L): File {
        dir.mkdirs()
        val file = File(dir, name)
        file.writeBytes(bytes)
        file.setLastModified(NOW_MS - ageMs)
        return file
    }

    @Test
    fun heldClipsLandOnTheCardWhole() {
        val held = folder.newFolder("held")
        val card = File(folder.root, "card")
        val bytes = ByteArray(3_000_000) { (it % 251).toByte() }
        clip(held, "drive_20261014_054000.mp4", bytes)
        clip(held, "drive_20261014_054200.mp4", byteArrayOf(1, 2, 3))

        assertEquals(2, ClipStore(held).moveInto(card, NOW_MS))

        assertArrayEquals(bytes, File(card, "drive_20261014_054000.mp4").readBytes())
        assertTrue(File(card, "drive_20261014_054200.mp4").isFile)
        assertTrue(ClipStore(held).list().isEmpty())
        assertTrue(card.listFiles()!!.none { it.name.endsWith(".moving") })
    }

    @Test
    fun aClipThatJustClosedStaysUntilItSettles() {
        val held = folder.newFolder("held")
        val card = File(folder.root, "card")
        clip(held, "drive_20261014_054000.mp4", byteArrayOf(1), ageMs = 5_000L)

        assertEquals(0, ClipStore(held).moveInto(card, NOW_MS))
        assertTrue(File(held, "drive_20261014_054000.mp4").isFile)
    }

    @Test
    fun aDifferentClipWithTheSameNameOnTheCardIsNeverOverwritten() {
        val held = folder.newFolder("held")
        val card = folder.newFolder("card")
        clip(held, "drive_20261014_054000.mp4", byteArrayOf(1, 2, 3))
        File(card, "drive_20261014_054000.mp4").writeBytes(byteArrayOf(9))

        assertEquals(0, ClipStore(held).moveInto(card, NOW_MS))
        assertArrayEquals(byteArrayOf(9), File(card, "drive_20261014_054000.mp4").readBytes())
        assertTrue(File(held, "drive_20261014_054000.mp4").isFile)
    }

    @Test
    fun aCopyThatAlreadyLandedOnlyClearsTheHeldOne() {
        val held = folder.newFolder("held")
        val card = folder.newFolder("card")
        clip(held, "drive_20261014_054000.mp4", byteArrayOf(1, 2, 3))
        File(card, "drive_20261014_054000.mp4").writeBytes(byteArrayOf(1, 2, 3))

        assertEquals(1, ClipStore(held).moveInto(card, NOW_MS))
        assertFalse(File(held, "drive_20261014_054000.mp4").exists())
    }

    @Test
    fun eventsMoveWithTheirLabelAndStill() {
        val held = folder.newFolder("held")
        val card = File(folder.root, "card")
        clip(held, "event_20261014_054000.mp4", byteArrayOf(1, 2, 3))
        File(held, "event_20261014_054000.json").writeText("{\"seen\":\"person\"}")
        File(held, "event_20261014_054000.jpg").writeBytes(byteArrayOf(7))

        assertEquals(1, EventStore(held).moveInto(card, NOW_MS))

        assertEquals("person", EventStore(card).list().single().seen)
        assertTrue(File(card, "event_20261014_054000.jpg").isFile)
        assertTrue(held.listFiles()!!.none { it.name.startsWith("event_") })
    }
}
