package com.strike.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CabinAudioTest {

    @Test
    fun aFailedCaptureCanRetryAfterItsResourcesAreClosed() {
        for (failure in listOf(IOException("connection lost"), IllegalStateException("encoder failed"))) {
            val entered = CountDownLatch(1)
            val fail = CountDownLatch(1)
            val nextEntered = CountDownLatch(1)
            val stop = CountDownLatch(1)
            val attempts = AtomicInteger()
            val closed = AtomicInteger()
            val audio = CabinAudio { capture ->
                try {
                    if (attempts.incrementAndGet() == 1) {
                        entered.countDown()
                        await(fail)
                        throw failure
                    }
                    capture.onStop { stop.countDown() }
                    nextEntered.countDown()
                    await(stop)
                } finally {
                    closed.incrementAndGet()
                }
            }
            assertTrue(audio.start())
            await(entered)
            val failed = worker(audio)
            try {
                fail.countDown()
                join(failed)
                assertFalse(audio.isCapturing)
                assertEquals(1, closed.get())
                assertTrue(audio.start())
                await(nextEntered)
            } finally {
                fail.countDown()
                audio.stop()
            }
            assertFalse(audio.isCapturing)
            assertEquals(2, attempts.get())
            assertEquals(2, closed.get())
        }
    }

    @Test
    fun stopDoesNotAllowAnotherWorkerDuringCleanup() {
        val entered = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val cleaning = CountDownLatch(1)
        val release = CountDownLatch(1)
        val attempts = AtomicInteger()
        val interrupts = AtomicInteger()
        val closed = AtomicInteger()
        val audio = CabinAudio { capture ->
            attempts.incrementAndGet()
            capture.onStop {
                interrupts.incrementAndGet()
                interrupted.countDown()
            }
            entered.countDown()
            try {
                await(interrupted)
            } finally {
                cleaning.countDown()
                await(release)
                closed.incrementAndGet()
            }
        }
        assertTrue(audio.start())
        await(entered)
        val stopping = Thread { audio.stop() }
        stopping.start()
        try {
            await(cleaning)
            assertFalse(audio.isCapturing)
            assertFalse(audio.start())
            assertEquals(1, attempts.get())
            assertEquals(0, closed.get())
        } finally {
            release.countDown()
            join(stopping)
            audio.stop()
        }
        assertEquals(1, interrupts.get())
        assertEquals(1, closed.get())
    }

    @Test
    fun stoppingBeforeResourcesAreRegisteredStillUnblocksThem() {
        val entered = CountDownLatch(1)
        val register = CountDownLatch(1)
        val interrupted = AtomicInteger()
        val audio = CabinAudio { capture ->
            entered.countDown()
            assertTrue(register.await(5, TimeUnit.SECONDS))
            capture.onStop { interrupted.incrementAndGet() }
        }
        assertTrue(audio.start())
        await(entered)
        val starting = worker(audio)
        try {
            audio.stop()
            assertFalse(audio.isCapturing)
            assertFalse(audio.start())
        } finally {
            register.countDown()
            join(starting)
        }
        assertEquals(1, interrupted.get())
    }

    @Test
    fun anUnblockActionCanWaitForTheWorkerWithoutHoldingItsLock() {
        val entered = CountDownLatch(1)
        val leave = CountDownLatch(1)
        val audio = CabinAudio { capture ->
            capture.onStop {
                val active = worker(capture)
                leave.countDown()
                join(active)
            }
            entered.countDown()
            await(leave)
        }
        assertTrue(audio.start())
        await(entered)
        audio.stop()
        assertFalse(audio.isCapturing)
    }

    private fun worker(audio: CabinAudio): Thread =
        CabinAudio::class.java.getDeclaredField("thread").also { it.isAccessible = true }.get(audio) as Thread

    private fun await(latch: CountDownLatch) {
        assertTrue("capture did not reach the expected state", latch.await(2, TimeUnit.SECONDS))
    }

    private fun join(thread: Thread) {
        thread.join(2_000)
        assertFalse("capture worker did not finish", thread.isAlive)
    }
}
