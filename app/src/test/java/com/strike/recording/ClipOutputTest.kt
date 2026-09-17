package com.strike.recording

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class ClipOutputTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun aHealthyVolumeOpensTheClipWithoutMountCommands() {
        val (file, bytes) = openClipOutput(folder.root, "clip.tmp", false,
            recover = { fail("healthy storage must not remount"); false }) {
            it.writeText("video")
            it.length()
        }
        assertEquals(5L, bytes)
        assertEquals("video", file.readText())
    }

    @Test fun anExistingButStaleMountRetriesTheActualOpenOnce() {
        var opens = 0
        var recoveries = 0
        val (file, _) = openClipOutput(folder.root, "clip.tmp", false,
            recover = { recoveries++; true }) {
            if (++opens == 1) throw IOException("stale mount")
            it.writeText("video")
        }
        assertEquals(2, opens)
        assertEquals(1, recoveries)
        assertEquals("video", file.readText())
    }

    @Test fun aMissingVolumeIsRecoveredBeforeCreatingTheRecordingDirectory() {
        val absent = File(folder.root, "card/clips")
        var recoveries = 0
        val (file, _) = openClipOutput(absent, "clip.tmp", false,
            recover = { assertFalse(it.exists()); recoveries++; true }) {
            it.writeText("video")
        }
        assertEquals(1, recoveries)
        assertEquals("video", file.readText())
    }

    @Test fun aFailedRetryDoesNotLoopOrUseInternalStorageForSurveillance() {
        val internal = folder.newFolder("internal")
        var opens = 0
        var recoveries = 0
        assertThrows(IOException::class.java) {
            openClipOutput(folder.root, "clip.tmp", false, internal,
                recover = { recoveries++; true }) {
                opens++
                throw IOException("card gone")
            }
        }
        assertEquals(2, opens)
        assertEquals(1, recoveries)
        assertTrue(internal.listFiles()!!.isEmpty())
    }

    @Test fun aDriveClipFallsBackOnlyAfterCardRecoveryFails() {
        val card = folder.newFolder("card")
        val internal = folder.newFolder("internal")
        var recoveries = 0
        val attempted = mutableListOf<File>()
        val (file, _) = openClipOutput(card, "clip.tmp", true, internal,
            recover = { recoveries++; true }) {
            attempted.add(it.parentFile!!)
            if (it.parentFile == card) throw IOException("card gone")
            it.writeText("video")
        }
        assertEquals(listOf(card, card, internal), attempted)
        assertEquals(1, recoveries)
        assertEquals(File(internal, "clip.tmp"), file)
        assertEquals("video", file.readText())
    }

    @Test fun aCodecFailureDoesNotRemountOrFallBackToAnotherVolume() {
        val internal = folder.newFolder("internal")
        assertThrows(IllegalStateException::class.java) {
            openClipOutput(folder.root, "clip.tmp", true, internal,
                recover = { fail("codec failure is not a mount failure"); false }) {
                throw IllegalStateException("muxer configuration")
            }
        }
        assertTrue(internal.listFiles()!!.isEmpty())
    }

    @Test fun failedInternalStorageIsNotRetriedOnItself() {
        var opens = 0
        assertThrows(IOException::class.java) {
            openClipOutput(folder.root, "clip.tmp", true, folder.root,
                recover = { false }) {
                opens++
                throw IOException("full")
            }
        }
        assertEquals(1, opens)
    }
}
