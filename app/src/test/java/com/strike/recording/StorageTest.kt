package com.strike.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StorageTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun clipsLiveAtTheTopOfTheVolumeNotUnderTheAppsOwnFolder() {
        assertEquals(
            "/storage/emulated/0",
            publicRoot("/storage/emulated/0/Android/data/com.strike/files")
        )
        assertEquals(
            "/storage/1234-ABCD",
            publicRoot("/storage/1234-ABCD/Android/data/com.strike/files")
        )
    }

    @Test
    fun aVolumeWithoutAnAndroidFolderIsItsOwnRoot() {
        assertEquals("/mnt/media_rw/usb", publicRoot("/mnt/media_rw/usb"))
    }

    @Test
    fun aCardKeepsItsPathWhenTheProbeFails() {
        val card = "/storage/7000-8000/Strike/clips"
        assertEquals(card, keepWritePath(false, card))
        assertEquals(card, keepWritePath(true, card))
    }

    @Test
    fun internalIsNotKeptWhenItCannotTakeFiles() {
        assertEquals(null, keepWritePath(false, "/storage/emulated/0/Strike/clips"))
    }

    @Test
    fun theAppCreatesAMissingEventsFolderWithoutTheShell() {
        val events = File(folder.root, "Strike/events")
        assertTrue(prepareDir(events, null))
        assertTrue(events.isDirectory)
        assertTrue(File(events, "probe-check").let {
            it.writeText("ok")
            it.delete()
        })
    }
}
