package com.strike.recording

import com.strike.surveillance.EventStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class ClipFileLockTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test(timeout = 5_000L)
    fun deletingARecordingWaitsForPublicationThenRemovesThePublishedFile() {
        val clip = source("drive_20260916_180500.mp4")
        afterPublication(clip, { ClipStore(folder.root).delete(clip.name) }) {
            clip.writeText("published")
        }
        assertFalse(clip.exists())
        assertTrue(File(folder.root, ".strike-clips.lock").isFile)
        assertTrue(ClipStore(folder.root).list().isEmpty())
    }

    @Test(timeout = 5_000L)
    fun deletingAnEventWaitsForPublicationAndRemovesItsSidecars() {
        val clip = source("event_20260916_180500.mp4")
        val facts = source("event_20260916_180500.json")
        val hero = source("event_20260916_180500.jpg")
        afterPublication(clip, { EventStore(folder.root).delete(clip.name) }) {
            clip.writeText("published")
        }
        assertFalse(clip.exists())
        assertFalse(facts.exists())
        assertFalse(hero.exists())
    }

    @Test(timeout = 5_000L)
    fun retentionUsesTheSamePublicationLockBeforeRemovingAClip() {
        val clip = source("drive_20260916_180500.mp4")
        afterPublication(clip, { Retention(ClipStore(folder.root), 0).enforce(null) == 1 }) {
            clip.writeText("published")
        }
        assertFalse(clip.exists())
    }

    @Test
    fun anExceptionReleasesTheOperatingSystemLockAndAllowsTheNextOperation() {
        val clip = source("drive_20260916_180500.mp4")
        val failure = assertThrows(IOException::class.java) {
            withClipFileLock(clip) { throw IOException("publication failed") }
        }
        assertEquals("publication failed", failure.message)
        val path = File(folder.root, ".strike-clips.lock").toPath()
        FileChannel.open(path, StandardOpenOption.WRITE).use { channel ->
            val lock = channel.tryLock()
            assertTrue(lock != null)
            lock!!.release()
        }
        assertEquals("next", withClipFileLock(clip) { "next" })
    }

    @Test
    fun anUnavailableLockDoesNotDeleteRecordingsOrEventsOrTheirSidecars() {
        val clip = source("drive_20260916_180500.mp4")
        val event = source("event_20260916_180500.mp4")
        val facts = source("event_20260916_180500.json")
        val hero = source("event_20260916_180500.jpg")
        folder.newFolder(".strike-clips.lock")

        assertFalse(ClipStore(folder.root).delete(clip.name))
        assertFalse(EventStore(folder.root).delete(event.name))
        assertEquals(0, Retention(ClipStore(folder.root), 0).enforce(null))
        for (file in listOf(clip, event, facts, hero)) assertTrue(file.isFile)
    }

    private fun source(name: String): File = folder.newFile(name).also { it.writeText(name) }

    private fun afterPublication(clip: File, delete: () -> Boolean, publish: () -> Unit) {
        val workers = Executors.newFixedThreadPool(2)
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val deleting = CountDownLatch(1)
        try {
            val publication = workers.submit {
                withClipFileLock(clip) {
                    held.countDown()
                    assertTrue(release.await(3, TimeUnit.SECONDS))
                    publish()
                }
            }
            assertTrue(held.await(3, TimeUnit.SECONDS))
            val deletion = workers.submit<Boolean> {
                deleting.countDown()
                delete()
            }
            assertTrue(deleting.await(3, TimeUnit.SECONDS))
            try {
                deletion.get(100, TimeUnit.MILLISECONDS)
                fail("deletion completed before publication released the lock")
            } catch (expected: TimeoutException) {
                assertTrue(clip.isFile)
            }
            release.countDown()
            publication.get(3, TimeUnit.SECONDS)
            assertTrue(deletion.get(3, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS))
        }
    }
}
