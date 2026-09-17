package com.strike.recording

import java.io.File
import java.util.concurrent.Executor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ClipThumbWriteTest {
    @get:Rule
    val folder = TemporaryFolder()
    private val jobs = ArrayDeque<Runnable>()
    private val executor = Executor { jobs.addLast(it) }

    @Test
    fun loadingAValidDiskThumbnailDoesNotRewriteItsJpegOrFacts() {
        val dir = folder.newFolder("thumbs")
        val clip = source("card", 1)
        val first = ClipThumbs(dir, executor, { 0L }) { _, _ -> Thumb(byteArrayOf(2), 1_000L, "h264") }
        load(first, clip, 2)
        val jpeg = File(dir, "clip.jpg")
        val facts = File(dir, "clip.facts")
        assertTrue(jpeg.setLastModified(1_000_000L))
        assertTrue(facts.setLastModified(1_002_000L))
        val before = listOf(jpeg.lastModified(), facts.lastModified())
        val restored = ClipThumbs(dir, executor, { 0L }) { _, _ ->
            throw AssertionError("Valid cache was decoded")
        }
        load(restored, clip, 2)
        assertEquals(before, listOf(jpeg.lastModified(), facts.lastModified()))
    }

    @Test
    fun blockedFactsPreserveTheCachedJpegAndMemoryResultThenRecoverWhenThePathIsFreed() {
        val dir = folder.newFolder("thumbs")
        val jpeg = File(dir, "clip.jpg")
        jpeg.writeBytes(byteArrayOf(1))
        val blocked = File(dir, "clip.facts")
        assertTrue(blocked.mkdir())
        val sentinel = File(blocked, "keep")
        sentinel.writeText("unrelated")
        val clip = source("card", 3)
        var decodes = 0
        fun thumbnails() = ClipThumbs(dir, executor, { 0L }) { file, _ ->
            decodes++
            Thumb(file.readBytes(), 1_000L, "h264")
        }
        val first = thumbnails()
        load(first, clip, 3)
        assertArrayEquals(byteArrayOf(3), (first.of(clip, "clip") as ThumbResult.Ready).thumb.jpeg)
        assertArrayEquals(byteArrayOf(1), jpeg.readBytes())
        assertEquals("unrelated", sentinel.readText())
        assertEquals(1, decodes)
        assertTrue(sentinel.delete())
        assertTrue(blocked.delete())
        load(thumbnails(), clip, 3)
        assertEquals(2, decodes)
        assertArrayEquals(byteArrayOf(3), jpeg.readBytes())
        load(thumbnails(), clip, 3)
        assertEquals(2, decodes)
    }

    private fun load(thumbnails: ClipThumbs, clip: File, expected: Int) {
        assertSame(ThumbResult.Pending, thumbnails.of(clip, "clip"))
        jobs.removeFirst().run()
        val thumb = (thumbnails.of(clip, "clip") as ThumbResult.Ready).thumb
        assertArrayEquals(byteArrayOf(expected.toByte()), thumb.jpeg)
    }

    private fun source(card: String, byte: Int): File = File(folder.newFolder(card), "clip.mp4").also {
        it.writeBytes(byteArrayOf(byte.toByte()))
        assertTrue(it.setLastModified(1_000_000L))
    }
}
