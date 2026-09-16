package com.strike.recording

import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ClipThumbsTest {
    @get:Rule
    val folder = TemporaryFolder()
    private val executors = ArrayList<ExecutorService>()

    @After
    fun stopWorkers() {
        executors.forEach { it.shutdownNow() }
        executors.forEach { assertTrue(it.awaitTermination(2, SECONDS)) }
    }

    @Test(timeout = 10_000L)
    fun eightRequestThreadsReturnPendingWhileOneSharedDecodeIsBlocked() {
        val worker = executor(1)
        val requests = executor(8)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val scans = AtomicInteger()
        val clip = source("same")
        val jpeg = byteArrayOf(1, 2, 3)
        val thumbnails = ClipThumbs(folder.newFolder("thumbs"), worker, { 0L }) { _, _ ->
            scans.incrementAndGet()
            entered.countDown()
            await(release)
            Thumb(jpeg, 2_000L, "h264")
        }

        try {
            assertSame(ThumbResult.Pending, thumbnails.of(clip, "same"))
            await(entered)
            val ready = CountDownLatch(8)
            val start = CountDownLatch(1)
            val replies = List(8) {
                requests.submit<ThumbResult> {
                    ready.countDown()
                    await(start)
                    thumbnails.of(clip, "same")
                }
            }
            await(ready)
            start.countDown()
            replies.forEach { assertSame(ThumbResult.Pending, it.get(2, SECONDS)) }
            assertEquals(1, scans.get())
        } finally {
            release.countDown()
        }
        worker.submit {}.get(2, SECONDS)
        val cached = (thumbnails.of(clip, "same") as ThumbResult.Ready).thumb
        assertArrayEquals(jpeg, cached.jpeg)
        assertEquals(2_000L, cached.durationMs)
        assertEquals("h264", cached.codec)
        assertEquals(1, scans.get())
    }

    @Test(timeout = 10_000L)
    fun recordingAndSurveillanceStoresShareASerialWorkerWithoutBlockingRequests() {
        val worker = executor(1)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val opened = AtomicInteger()
        val decode: (File, File?) -> Thumb? = { clip, _ ->
            val count = active.incrementAndGet()
            maximum.updateAndGet { maxOf(it, count) }
            opened.incrementAndGet()
            try {
                if (clip.name == "first.mp4") {
                    entered.countDown()
                    await(release)
                }
                Thumb(byteArrayOf(7), 3_000L, null)
            } finally {
                active.decrementAndGet()
            }
        }
        val recordings = ClipThumbs(folder.newFolder("recordings"), worker, { 0L }, decode)
        val surveillance = ClipThumbs(folder.newFolder("surveillance"), worker, { 0L }, decode)
        val first = source("first")
        val second = source("second")
        try {
            assertSame(ThumbResult.Pending, recordings.of(first, "first"))
            await(entered)
            assertSame(ThumbResult.Pending, surveillance.of(second, "second"))
            assertEquals(1, opened.get())
            assertEquals(1, active.get())
        } finally {
            release.countDown()
        }
        worker.submit {}.get(2, SECONDS)
        assertTrue(recordings.of(first, "first") is ThumbResult.Ready)
        assertTrue(surveillance.of(second, "second") is ThumbResult.Ready)
        assertEquals(2, opened.get())
        assertEquals(1, maximum.get())
    }

    @Test(timeout = 10_000L)
    fun aWarmMemoryThumbnailReturnsWhileAnotherClipIsBeingDecoded() {
        val worker = executor(1)
        val requests = executor(1)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val warm = source("warm")
        val cold = source("cold")
        val jpeg = byteArrayOf(4, 5, 6)
        val thumbnails = ClipThumbs(folder.newFolder("thumbs"), worker, { 0L }) { clip, _ ->
            if (clip == cold) {
                entered.countDown()
                await(release)
            }
            Thumb(jpeg, 4_000L, "h265")
        }
        thumbnails.of(warm, "warm")
        worker.submit {}.get(2, SECONDS)
        try {
            thumbnails.of(cold, "cold")
            await(entered)
            val reply = requests.submit<ThumbResult> { thumbnails.of(warm, "warm") }.get(2, SECONDS)
            assertArrayEquals(jpeg, (reply as ThumbResult.Ready).thumb.jpeg)
        } finally {
            release.countDown()
        }
        worker.submit {}.get(2, SECONDS)
    }

    @Test(timeout = 10_000L)
    fun aFullWorkerQueueReturnsBusyAndTheRejectedClipCanBeRetried() {
        val worker = ThreadPoolExecutor(1, 1, 0L, SECONDS, ArrayBlockingQueue(8))
        executors.add(worker)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(9)
        val scans = AtomicInteger()
        val thumbnails = ClipThumbs(folder.newFolder("thumbs"), worker, { 0L }) { clip, _ ->
            scans.incrementAndGet()
            if (clip.name == "first.mp4") {
                entered.countDown()
                await(release)
            }
            completed.countDown()
            Thumb(byteArrayOf(9), 1_000L, "h264")
        }
        val first = source("first")
        val overflow = source("overflow")
        try {
            assertSame(ThumbResult.Pending, thumbnails.of(first, "first"))
            await(entered)
            repeat(8) { assertSame(ThumbResult.Pending, thumbnails.of(source("queued-$it"), "queued-$it")) }
            assertSame(ThumbResult.Busy, thumbnails.of(overflow, "overflow"))
            assertEquals(8, worker.queue.size)
            assertEquals(1, scans.get())
        } finally {
            release.countDown()
        }
        await(completed)
        assertTrue(thumbnails.of(overflow, "overflow") != ThumbResult.Busy)
        worker.submit {}.get(2, SECONDS)
        assertTrue(thumbnails.of(overflow, "overflow") is ThumbResult.Ready)
        assertEquals(10, scans.get())
    }

    private fun executor(threads: Int): ExecutorService =
        Executors.newFixedThreadPool(threads).also { executors.add(it) }

    private fun source(name: String): File =
        File(folder.root, "$name.mp4").also { it.writeBytes(byteArrayOf(1)) }

    private fun await(latch: CountDownLatch) {
        assertTrue("The coordinated thumbnail operation did not finish", latch.await(2, SECONDS))
    }
}
