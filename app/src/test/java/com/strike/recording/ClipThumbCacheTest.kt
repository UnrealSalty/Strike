package com.strike.recording

import java.io.File
import java.util.concurrent.Executor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ClipThumbCacheTest {
    @get:Rule
    val folder = TemporaryFolder()
    private val work = QueuedExecutor()
    private var nowMs = 0L

    @Test
    fun anExistingDiskThumbnailLoadsOffTheRequestThreadWithoutAnotherDecode() {
        val dir = folder.newFolder("thumbs")
        val clip = source("clip")
        val jpeg = byteArrayOf(1, 2, 3)
        val first = ClipThumbs(dir, work, { nowMs }) { _, _ -> Thumb(jpeg, 2_000L, "h264") }
        assertSame(ThumbResult.Pending, first.of(clip, "clip"))
        work.runNext()
        val second = ClipThumbs(dir, work, { nowMs }) { _, _ -> throw AssertionError("Cached clip was decoded") }
        assertSame(ThumbResult.Pending, second.of(clip, "clip"))
        work.runNext()
        val retained = ready(second.of(clip, "clip"))
        assertArrayEquals(jpeg, retained.jpeg)
        assertEquals(2_000L, retained.durationMs)
        assertEquals("h264", retained.codec)
        assertEquals(0, work.size)
    }

    @Test
    fun anUnreadableClipIsRetriedThirtySecondsAfterDecodeCompletion() {
        var scans = 0
        val clip = source("broken")
        val thumbnails = ClipThumbs(folder.newFolder("thumbs"), work, { nowMs }) { _, _ ->
            scans++
            if (scans == 1) { nowMs = 5_000L; null } else Thumb(byteArrayOf(4), 1_000L, null)
        }
        assertSame(ThumbResult.Pending, thumbnails.of(clip, "broken"))
        work.runNext()
        nowMs = 34_999L
        assertSame(ThumbResult.Missing, thumbnails.of(clip, "broken"))
        assertEquals(0, work.size)
        nowMs = 35_000L
        assertSame(ThumbResult.Pending, thumbnails.of(clip, "broken"))
        work.runNext()
        assertArrayEquals(byteArrayOf(4), ready(thumbnails.of(clip, "broken")).jpeg)
        assertEquals(2, scans)
    }

    @Test
    fun forgettingDuringDecodeCannotRestoreADeletedThumbnail() {
        val dir = folder.newFolder("thumbs")
        val clip = source("clip")
        var scans = 0
        lateinit var thumbnails: ClipThumbs
        thumbnails = ClipThumbs(dir, work, { nowMs }) { _, _ ->
            if (++scans == 1) thumbnails.forget("clip")
            Thumb(byteArrayOf(scans.toByte()), 2_000L, null)
        }
        thumbnails.of(clip, "clip")
        work.runNext()
        assertFalse(File(dir, "clip.jpg").exists())
        assertFalse(File(dir, "clip.facts").exists())
        assertSame(ThumbResult.Pending, thumbnails.of(clip, "clip"))
        work.runNext()
        assertArrayEquals(byteArrayOf(2), ready(thumbnails.of(clip, "clip")).jpeg)
    }

    @Test
    fun forgettingACachedThumbnailRemovesItsMemoryAndDiskCopies() {
        val dir = folder.newFolder("thumbs")
        val clip = source("clip")
        var scans = 0
        val thumbnails = ClipThumbs(dir, work, { nowMs }) { _, _ ->
            Thumb(byteArrayOf((++scans).toByte()), 1_000L, "h264")
        }
        thumbnails.of(clip, "clip")
        work.runNext()
        assertTrue(File(dir, "clip.jpg").isFile)
        assertTrue(File(dir, "clip.facts").isFile)
        thumbnails.forget("clip")
        assertFalse(File(dir, "clip.jpg").exists())
        assertFalse(File(dir, "clip.facts").exists())
        assertSame(ThumbResult.Pending, thumbnails.of(clip, "clip"))
        work.runNext()
        assertArrayEquals(byteArrayOf(2), ready(thumbnails.of(clip, "clip")).jpeg)
    }

    @Test
    fun anotherCardOrChangedClipDoesNotReuseTheSameIdsOldThumbnail() {
        val dir = folder.newFolder("thumbs")
        val first = source("card-a")
        val second = source("card-b")
        var scans = 0
        val thumbnails = ClipThumbs(dir, work, { nowMs }) { _, _ ->
            Thumb(byteArrayOf((++scans).toByte()), 1_000L, "h264")
        }
        fun load(clip: File, expected: Int) {
            assertSame(ThumbResult.Pending, thumbnails.of(clip, "same-id"))
            work.runNext()
            assertArrayEquals(byteArrayOf(expected.toByte()), ready(thumbnails.of(clip, "same-id")).jpeg)
        }
        load(first, 1)
        load(second, 2)
        val stamp = second.lastModified()
        second.appendBytes(byteArrayOf(2))
        assertTrue(second.setLastModified(stamp))
        load(second, 3)
        assertTrue(second.setLastModified(stamp + 2_000L))
        load(second, 4)
        assertEquals(4, scans)
    }

    @Test
    fun aChangedDetectionStillDoesNotReuseAnEarlierHeroOrVideoFrame() {
        val clip = source("clip")
        val first = source("first-hero")
        val second = source("second-hero")
        var scans = 0
        val thumbnails = ClipThumbs(folder.newFolder("thumbs"), work, { nowMs }) { _, _ ->
            Thumb(byteArrayOf((++scans).toByte()), 1_000L, "h264")
        }
        fun load(hero: File?, expected: Int) {
            assertSame(ThumbResult.Pending, thumbnails.of(clip, "clip", hero))
            work.runNext()
            assertArrayEquals(byteArrayOf(expected.toByte()), ready(thumbnails.of(clip, "clip", hero)).jpeg)
        }
        load(null, 1)
        load(first, 2)
        load(second, 3)
        val stamp = second.lastModified()
        second.appendBytes(byteArrayOf(2))
        assertTrue(second.setLastModified(stamp))
        load(second, 4)
        assertTrue(second.setLastModified(stamp + 2_000L))
        load(second, 5)
        load(null, 6)
        assertEquals(6, scans)
    }

    @Test
    fun aSourceChangedDuringDecodeCannotPublishTheOldFrame() {
        val clip = source("clip")
        val dir = folder.newFolder("thumbs")
        var scans = 0
        val thumbnails = ClipThumbs(dir, work, { nowMs }) { _, _ ->
            if (++scans == 1) clip.appendBytes(byteArrayOf(2))
            Thumb(byteArrayOf(scans.toByte()), 1_000L, null)
        }
        thumbnails.of(clip, "clip")
        work.runNext()
        assertFalse(File(dir, "clip.jpg").exists())
        assertFalse(File(dir, "clip.facts").exists())
        assertSame(ThumbResult.Pending, thumbnails.of(clip, "clip"))
        work.runNext()
        assertArrayEquals(byteArrayOf(2), ready(thumbnails.of(clip, "clip")).jpeg)
    }

    @Test
    fun anUnavailableDiskCacheStillLeavesTheDecodedThumbnailInMemory() {
        val dir = folder.newFile("not-a-directory")
        val clip = source("clip")
        var scans = 0
        val thumbnails = ClipThumbs(dir, work, { nowMs }) { _, _ ->
            scans++
            Thumb(byteArrayOf(3, 2, 1), 5_000L, "h265")
        }
        assertSame(ThumbResult.Pending, thumbnails.of(clip, "clip"))
        work.runNext()
        repeat(3) { assertArrayEquals(byteArrayOf(3, 2, 1), ready(thumbnails.of(clip, "clip")).jpeg) }
        assertEquals(1, scans)
        assertEquals(0, work.size)
    }

    @Test
    fun aDecoderFailureDoesNotLeaveRequestsPendingOrBlockTheNextClip() {
        val thumbnails = ClipThumbs(folder.newFolder("thumbs"), work, { nowMs }) { clip, _ ->
            when (clip.name) {
                "broken.mp4" -> throw IllegalStateException("Unreadable header")
                "failed.mp4" -> throw AssertionError("Decoder failure")
                else -> Thumb(byteArrayOf(7), 1_000L, null)
            }
        }
        val broken = source("broken")
        val failed = source("failed")
        val next = source("next")
        thumbnails.of(broken, "broken")
        work.runNext()
        assertSame(ThumbResult.Missing, thumbnails.of(broken, "broken"))
        thumbnails.of(failed, "failed")
        assertThrows(AssertionError::class.java) { work.runNext() }
        assertSame(ThumbResult.Missing, thumbnails.of(failed, "failed"))
        assertSame(ThumbResult.Pending, thumbnails.of(next, "next"))
        work.runNext()
        assertArrayEquals(byteArrayOf(7), ready(thumbnails.of(next, "next")).jpeg)
    }

    private fun source(name: String): File = File(folder.root, "$name.mp4").also {
        it.writeBytes(byteArrayOf(1))
        assertTrue(it.setLastModified(1_000_000L))
    }

    private fun ready(result: ThumbResult): Thumb = (result as ThumbResult.Ready).thumb

    private class QueuedExecutor : Executor {
        private val jobs = ArrayDeque<Runnable>()
        val size: Int get() = jobs.size
        override fun execute(command: Runnable) { jobs.addLast(command) }
        fun runNext() { jobs.removeFirst().run() }
    }
}
