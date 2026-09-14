package com.strike.recording

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeoutException
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.SECONDS

class ClipThumbsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val workers = Executors.newFixedThreadPool(3)

    @After
    fun stopWorkers() {
        workers.shutdownNow()
        assertTrue(workers.awaitTermination(2, SECONDS))
    }

    @Test
    fun recordingAndSurveillanceThumbnailsShareOneDecoder() {
        val recordings = ClipThumbs(folder.newFolder("recordings"))
        val surveillance = ClipThumbs(folder.newFolder("surveillance"))
        val decoding = CountDownLatch(1)
        val release = CountDownLatch(1)
        val secondRequested = CountDownLatch(1)
        val secondOpened = CountDownLatch(1)
        val first = workers.submit<Thumb?> {
            recordings.of(source {
                decoding.countDown()
                assertTrue(release.await(5, SECONDS))
            }, "first")
        }
        try {
            assertTrue(decoding.await(2, SECONDS))
            val second = workers.submit<Thumb?> {
                secondRequested.countDown()
                surveillance.of(source { secondOpened.countDown() }, "second")
            }
            assertTrue(secondRequested.await(2, SECONDS))
            assertFalse(secondOpened.await(200, MILLISECONDS))
            release.countDown()
            assertNull(first.get(2, SECONDS))
            assertNull(second.get(2, SECONDS))
            assertTrue(secondOpened.await(2, SECONDS))
        } finally {
            release.countDown()
        }
    }

    @Test
    fun cachedThumbnailDoesNotWaitForABusyDecoder() {
        val dir = folder.newFolder("thumbs")
        val thumbnails = ClipThumbs(dir)
        val jpeg = byteArrayOf(1, 2, 3)
        cache(dir, "cached", jpeg)
        val decoding = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = workers.submit<Thumb?> {
            thumbnails.of(source {
                decoding.countDown()
                assertTrue(release.await(5, SECONDS))
            }, "uncached")
        }
        try {
            assertTrue(decoding.await(2, SECONDS))
            val cached = workers.submit<Thumb?> {
                thumbnails.of(source { throw AssertionError("Cached clip was opened") }, "cached")
            }.get(2, SECONDS)
            assertArrayEquals(jpeg, cached!!.jpeg)
        } finally {
            release.countDown()
        }
        assertNull(first.get(2, SECONDS))
    }

    @Test
    fun waitingRequestUsesTheThumbnailCachedByTheFirstRequest() {
        val dir = folder.newFolder("thumbs")
        val thumbnails = ClipThumbs(dir)
        val jpeg = byteArrayOf(4, 5, 6)
        val decoding = CountDownLatch(1)
        val release = CountDownLatch(1)
        val secondRequested = CountDownLatch(1)
        val first = workers.submit<Thumb?> {
            thumbnails.of(source {
                decoding.countDown()
                assertTrue(release.await(5, SECONDS))
                cache(dir, "same", jpeg)
            }, "same")
        }
        try {
            assertTrue(decoding.await(2, SECONDS))
            val second = workers.submit<Thumb?> {
                secondRequested.countDown()
                thumbnails.of(source { throw AssertionError("Clip was decoded twice") }, "same")
            }
            assertTrue(secondRequested.await(2, SECONDS))
            assertThrows(TimeoutException::class.java) { second.get(200, MILLISECONDS) }
            release.countDown()
            assertNull(first.get(2, SECONDS))
            assertArrayEquals(jpeg, second.get(2, SECONDS)!!.jpeg)
        } finally {
            release.countDown()
        }
    }

    @Test
    fun failedDecodeDoesNotBlockTheNextThumbnail() {
        val thumbnails = ClipThumbs(folder.newFolder("thumbs"))
        assertNull(thumbnails.of(source { throw IllegalStateException("Unreadable clip") }, "broken"))
        assertNull(workers.submit<Thumb?> {
            thumbnails.of(source {}, "next")
        }.get(2, SECONDS))

        assertThrows(AssertionError::class.java) {
            thumbnails.of(source { throw AssertionError("Decoder failure") }, "failed")
        }
        assertNull(workers.submit<Thumb?> {
            thumbnails.of(source {}, "last")
        }.get(2, SECONDS))
    }

    private fun source(open: () -> Unit): File = object : File(folder.root, "clip.mp4") {
        override fun getAbsolutePath(): String {
            open()
            return super.getAbsolutePath()
        }
    }

    private fun cache(dir: File, id: String, jpeg: ByteArray) {
        File(dir, "$id.jpg").writeBytes(jpeg)
        File(dir, "$id.facts").writeText("2000\nh264")
    }
}
