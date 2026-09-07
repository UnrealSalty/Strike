package com.strike.recording

import android.media.MediaFormat
import com.strike.surveillance.EventStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RecorderTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun stoppingBeforeTheLastClipIsQueuedStillPublishesIt() {
        val saved = AtomicInteger()
        val recorder = Recorder(folder.root, RecordingMode.EVENT, null) { saved.incrementAndGet() }
        val pending = LastClipQueue()
        set(recorder, "toClose", pending)
        set(recorder, "writerFinished", false)
        val closer = Thread { call(recorder, "closeFinished") }
        val writer = ClipWriter(folder.root)
        assertTrue(writer.open(RecordingMode.EVENT, MediaFormat()))
        val clip = writer.name!!
        File(folder.root, "$clip.tmp").writeBytes(byteArrayOf(1, 2, 3))

        closer.start()
        try {
            assertTrue(pending.waitingForLastClip.await(2, TimeUnit.SECONDS))
            call(recorder, "finish", writer)
        } finally {
            set(recorder, "writerFinished", true)
            closer.join(2_000)
        }

        assertFalse(closer.isAlive)
        assertEquals(1, saved.get())
        assertEquals(listOf(clip), EventStore(folder.root).list().map { it.id })
        assertFalse(File(folder.root, "$clip.tmp").exists())
    }

    private fun set(recorder: Recorder, name: String, value: Any) {
        Recorder::class.java.getDeclaredField(name).also { it.isAccessible = true }.set(recorder, value)
    }

    private fun call(recorder: Recorder, name: String, writer: ClipWriter? = null) {
        val parameters = if (writer == null) emptyArray() else arrayOf(ClipWriter::class.java)
        val method = Recorder::class.java.getDeclaredMethod(name, *parameters)
        method.isAccessible = true
        if (writer == null) method.invoke(recorder) else method.invoke(recorder, writer)
    }

    private class LastClipQueue : ArrayBlockingQueue<ClipWriter>(8) {
        val waitingForLastClip = CountDownLatch(1)
        private var wasEmpty = false

        override fun poll(timeout: Long, unit: TimeUnit): ClipWriter? {
            if (!wasEmpty) {
                wasEmpty = true
                return null
            }
            waitingForLastClip.countDown()
            return super.poll(timeout, unit)
        }
    }
}
