package com.strike.recording

import android.media.MediaFormat
import com.strike.daemon.AudioIngest
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecorderAudioTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun continuousAudioArrivalsYieldAndRemainAvailableForLaterTurns() {
        val frames = ReplenishingFrames()
        val audio = audio(frames)
        val recorder = Recorder(folder.root, RecordingMode.DRIVE, audio) {}
        val writer = writer(hasAudio = true)

        drain(recorder, writer)

        assertEquals(listOf(0L, 21_000L), frames.consumed.map { it.timeUs })
        assertEquals(2, frames.size)
        assertEquals(42_000L, frames.peek()!!.timeUs)
        drain(recorder, writer)
        assertEquals(listOf(0L, 21_000L, 42_000L, 63_000L), frames.consumed.map { it.timeUs })
        assertEquals(2, frames.size)
        assertEquals(84_000L, frames.peek()!!.timeUs)
        frames.consumed.forEachIndexed { index, sample ->
            assertArrayEquals(byteArrayOf(index.toByte()), sample.bytes)
        }
    }

    @Test
    fun aFiniteAudioBacklogIsDrainedInOrderWithoutChangingItsSamples() {
        val frames = ObservedFrames()
        val samples = listOf(sample(1), sample(2), sample(3))
        samples.forEach { frames.add(it) }
        val recorder = Recorder(folder.root, RecordingMode.DRIVE, audio(frames)) {}

        drain(recorder, writer(hasAudio = true))

        assertTrue(frames.isEmpty())
        assertEquals(3, frames.consumed.size)
        samples.indices.forEach { assertSame(samples[it], frames.consumed[it]) }
    }

    @Test
    fun anEmptyAudioQueueReturnsWithoutWaitingForAnArrival() {
        val frames = ObservedFrames()
        val recorder = Recorder(folder.root, RecordingMode.DRIVE, audio(frames)) {}

        drain(recorder, writer(hasAudio = true))

        assertTrue(frames.consumed.isEmpty())
        assertTrue(frames.isEmpty())
    }

    @Test
    fun audioDisconnectingDuringTheTurnReturnsWhenTheQueueClears() {
        val frames = object : ArrayBlockingQueue<Sample>(4) {
            var polls = 0
            override fun poll(): Sample? {
                polls++
                clear()
                return null
            }
        }
        frames.add(sample(1))
        frames.add(sample(2))
        val recorder = Recorder(folder.root, RecordingMode.DRIVE, audio(frames)) {}

        drain(recorder, writer(hasAudio = true))

        assertEquals(1, frames.polls)
        assertTrue(frames.isEmpty())
    }

    @Test
    fun aClipWithoutAnAudioTrackDiscardsItsQueuedAudio() {
        val frames = ObservedFrames()
        frames.add(sample(1))
        frames.add(sample(2))
        val recorder = Recorder(folder.root, RecordingMode.DRIVE, audio(frames)) {}

        drain(recorder, writer(hasAudio = false))

        assertTrue(frames.isEmpty())
        assertTrue(frames.consumed.isEmpty())
    }

    @Test
    fun aRecorderWithoutAudioHasNothingToDrain() {
        val recorder = Recorder(folder.root, RecordingMode.DRIVE, null) {}
        val writer = writer(hasAudio = false)

        drain(recorder, writer)

    }

    @Test
    fun idleVideoPollsLeaveSharedAudioQueuedUntilTheFirstVideoSample() {
        val frames = ObservedFrames()
        frames.add(sample(1))
        frames.add(sample(2))
        val recorder = Recorder(folder.root, RecordingMode.DRIVE, audio(frames)) {}
        var turns = 0

        writeVideoOnly(recorder) {
            when (turns++) {
                0 -> null
                1 -> {
                    assertEquals(2, frames.size)
                    null
                }
                2 -> {
                    assertEquals(2, frames.size)
                    Sample(byteArrayOf(1), 0L, 1)
                }
                else -> {
                    assertTrue(frames.isEmpty())
                    set(recorder, "running", false)
                    null
                }
            }
        }

        assertEquals(4, turns)
    }

    @Test
    fun stoppingBeforeTheFirstVideoSampleLeavesSharedAudioUnconsumed() {
        val frames = ObservedFrames()
        val pending = sample(1)
        frames.add(pending)
        val recorder = Recorder(folder.root, RecordingMode.DRIVE, audio(frames)) {}
        var turns = 0

        writeVideoOnly(recorder) {
            if (turns++ == 1) set(recorder, "running", false)
            null
        }

        assertEquals(2, turns)
        assertSame(pending, frames.peek())
        assertTrue(frames.consumed.isEmpty())
    }

    private fun writeVideoOnly(recorder: Recorder, nextVideo: () -> Sample?) {
        val encoder = Encoder(64, 64, 15, 1_000_000, "video/avc") {}
        set(encoder, "format", MediaFormat())
        set(recorder, "running", true)
        val queue = Recorder::class.java.getDeclaredField("samples").also {
            it.isAccessible = true
        }.get(recorder) as SampleQueue
        set(queue, "held", object : ArrayBlockingQueue<Sample>(1) {
            override fun poll(timeout: Long, unit: TimeUnit): Sample? = nextVideo()
        })
        val method = Recorder::class.java.getDeclaredMethod(
            "writeClips", Encoder::class.java, RecordingOptions::class.java
        ).also { it.isAccessible = true }
        try {
            method.invoke(recorder, encoder, RecordingOptions(Long.MAX_VALUE, "standard", "h264", 15, false))
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    private fun audio(frames: ArrayBlockingQueue<Sample>) = AudioIngest().also {
        set(it, "frames", frames)
    }

    private fun writer(hasAudio: Boolean) = ClipWriter(folder.root).also {
        if (hasAudio) set(it, "audioTrack", 0)
    }

    private fun drain(recorder: Recorder, writer: ClipWriter) {
        val method = Recorder::class.java.getDeclaredMethod(
            "drainAudio", ClipWriter::class.java, Long::class.javaPrimitiveType
        ).also { it.isAccessible = true }
        try {
            method.invoke(recorder, writer, Long.MIN_VALUE)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    private fun set(target: Any, name: String, value: Any) {
        target.javaClass.getDeclaredField(name).also { it.isAccessible = true }.set(target, value)
    }

    private fun sample(at: Int) = Sample(byteArrayOf(at.toByte()), at * 21_000L, 0)

    private class ReplenishingFrames : ArrayBlockingQueue<Sample>(200) {
        val consumed = ArrayList<Sample>()
        private var next = 0

        init {
            add(next())
            add(next())
        }

        override fun poll(): Sample? {
            assertTrue("Audio kept draining new arrivals without yielding to video", consumed.size < 64)
            val taken = super.poll() ?: return null
            consumed.add(taken)
            add(next())
            return taken
        }

        private fun next(): Sample {
            val at = next++
            return Sample(byteArrayOf(at.toByte()), at * 21_000L, 0)
        }
    }

    private class ObservedFrames : ArrayBlockingQueue<Sample>(200) {
        val consumed = ArrayList<Sample>()
        override fun poll(): Sample? = super.poll()?.also { consumed.add(it) }
    }
}
