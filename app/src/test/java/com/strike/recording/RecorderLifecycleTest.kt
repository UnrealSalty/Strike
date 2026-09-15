package com.strike.recording

import android.graphics.SurfaceTexture
import android.media.MediaFormat
import android.view.Surface
import com.strike.camera.frameBusForTest
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RecorderLifecycleTest {
    @Test
    fun aBlockedGraphicsOwnerPreventsReleasingItsEncoder() {
        val recorder = recorder()
        val bus = frameBusForTest()
        val encoder = encoder()
        val release = CountDownLatch(1)
        val owner = heldThread(release)
        set(bus, "thread", owner)
        set(recorder, "bus", bus)
        set(recorder, "encoder", encoder)
        try {
            assertFalse(recorder.stop())
            assertSame(bus, field(recorder, "bus"))
            assertSame(encoder, field(recorder, "encoder"))
            assertEquals(true, field(encoder, "running"))
            assertEquals("unfinished.mp4", recorder.clip)
        } finally {
            release.countDown()
            owner.join(2_000L)
        }
        assertTrue(recorder.stop())
        assertNull(recorder.clip)
    }

    @Test
    fun aBlockedEncoderDrainKeepsItsSurfaceAndRecorderReference() {
        val recorder = recorder()
        val encoder = encoder()
        val surface = Surface(null as SurfaceTexture?)
        set(encoder, "inputSurface", surface)
        val release = CountDownLatch(1)
        val drain = heldThread(release)
        set(encoder, "drain", drain)
        set(recorder, "encoder", encoder)
        try {
            assertFalse(recorder.stop())
            assertSame(encoder, field(recorder, "encoder"))
            assertSame(drain, field(encoder, "drain"))
            assertSame(surface, field(encoder, "inputSurface"))
            assertEquals("unfinished.mp4", recorder.clip)
        } finally {
            release.countDown()
            drain.join(2_000L)
        }
        assertTrue(recorder.stop())
        assertNull(field(encoder, "inputSurface"))
        assertNull(field(recorder, "encoder"))
    }

    @Test
    fun unfinishedFileWorkersAreRetainedUntilTheyActuallyExit() {
        val release = CountDownLatch(1)
        val completed = ConcurrentHashMap<String, Boolean>()
        val sessions = listOf("writerThread", "closer").associateWith {
            recorder() to heldThread(release)
        }
        val stoppers = sessions.map { (field, held) ->
            set(held.first, field, held.second)
            Thread { completed[field] = held.first.stop() }.also { it.start() }
        }
        try {
            stoppers.forEach { it.join(30_000L) }
            assertTrue(stoppers.none { it.isAlive })
            for ((name, held) in sessions) {
                assertEquals(false, completed[name])
                assertSame(held.second, field(held.first, name))
                assertEquals("unfinished.mp4", held.first.clip)
            }
        } finally {
            release.countDown()
            sessions.values.forEach { it.second.join(2_000L) }
            stoppers.forEach { it.join(2_000L) }
        }
        for ((name, held) in sessions) {
            assertTrue(held.first.stop())
            assertNull(field(held.first, name))
            assertNull(held.first.clip)
        }
    }

    @Test
    fun aSlowIndexCopyDoesNotPreventRecordingFromStopping() {
        val recorder = recorder()
        val release = CountDownLatch(1)
        val indexer = heldThread(release)
        set(recorder, "indexer", indexer)
        try {
            assertTrue(recorder.stop())
            assertTrue(indexer.isAlive)
            assertNull(field(recorder, "indexer"))
            assertNull(recorder.clip)
        } finally {
            release.countDown()
            indexer.join(2_000L)
        }
    }

    @Test
    fun aWriterAndCloserFinishingWithinTheBudgetAllowCleanStop() {
        val recorder = recorder()
        val release = CountDownLatch(1)
        val writer = heldThread(release)
        val closer = heldThread(release)
        set(recorder, "writerThread", writer)
        set(recorder, "closer", closer)
        val completed = AtomicReference<Boolean?>()
        val stopper = Thread { completed.set(recorder.stop()) }.also { it.start() }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (field(recorder, "running") == true && System.nanoTime() < deadline) Thread.yield()
            assertEquals(false, field(recorder, "running"))
            assertNull(completed.get())
        } finally {
            release.countDown()
            writer.join(2_000L)
            closer.join(2_000L)
            stopper.join(2_000L)
        }
        assertTrue(completed.get() == true)
        assertNull(field(recorder, "writerThread"))
        assertNull(field(recorder, "closer"))
        assertNull(recorder.clip)
    }
    private fun recorder() = Recorder(File("."), RecordingMode.EVENT, null) {}.also {
        set(it, "running", true)
        set(it, "clip", "unfinished.mp4")
    }

    private fun encoder() = Encoder(64, 64, 15, 1_000_000, MediaFormat.MIMETYPE_VIDEO_AVC) {}.also {
        set(it, "running", true)
    }

    private fun heldThread(release: CountDownLatch): Thread {
        val entered = CountDownLatch(1)
        val thread = Thread { entered.countDown(); release.await() }.also { it.start() }
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        return thread
    }

    private fun field(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).also { it.isAccessible = true }.get(target)

    private fun set(target: Any, name: String, value: Any) =
        target.javaClass.getDeclaredField(name).also { it.isAccessible = true }.set(target, value)
}
