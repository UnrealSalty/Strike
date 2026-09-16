package com.strike.recording

import android.media.MediaCodec
import android.media.MediaFormat
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecorderRotationTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun aConsumedBoundaryAllowsTheNextRotationWithoutReplayingTheOldOne() {
        val recorder = Recorder(folder.root, RecordingMode.DRIVE, null) {}
        val encoder = encoder()
        val rotation = encoder.rotation
        var firstId = 0L
        var secondId = 0L

        val clips = writeClips(recorder, encoder, Long.MAX_VALUE,
            { sample(0L, key = true) },
            {
                assertTrue(rotation.request(1_000L))
                firstId = rotation.boundary(keyFrame = true, nowMs = 1_001L)
                assertTrue(firstId != 0L)
                sample(1L, key = true, rotationId = firstId)
            },
            {
                assertEquals(1, finished(recorder).size)
                assertFalse(rotation.isPending(firstId))
                assertTrue(rotation.request(2_000L))
                secondId = rotation.boundary(keyFrame = true, nowMs = 2_001L)
                assertTrue(secondId > firstId)
                sample(2L, key = true, rotationId = firstId)
            },
            {
                assertEquals(1, finished(recorder).size)
                assertTrue(rotation.isPending(secondId))
                sample(3L, key = true, rotationId = secondId)
            },
            {
                assertEquals(2, finished(recorder).size)
                assertFalse(rotation.isPending(secondId))
                set(recorder, "running", false)
                null
            }
        )

        assertEquals(3, clips)
    }

    @Test
    fun anOverdueKeyframeMakesTheQueuedEncoderBoundaryObsolete() {
        val recorder = Recorder(folder.root, RecordingMode.DRIVE, null) {}
        val encoder = encoder()
        val rotation = encoder.rotation
        var obsoleteId = 0L
        var nextId = 0L

        val clips = writeClips(recorder, encoder, 0L,
            {
                assertTrue(rotation.request(1_000L))
                obsoleteId = rotation.boundary(keyFrame = false, nowMs = 4_000L)
                assertTrue(obsoleteId != 0L)
                sample(0L, key = true)
            },
            {
                assertEquals(1, finished(recorder).size)
                assertFalse(rotation.isPending(obsoleteId))
                nextId = rotation.boundary(keyFrame = true, nowMs = 4_001L)
                assertTrue(nextId > obsoleteId)
                sample(1L, rotationId = obsoleteId)
            },
            {
                assertEquals(1, finished(recorder).size)
                assertTrue(rotation.isPending(nextId))
                set(recorder, "running", false)
                null
            }
        )

        assertEquals(2, clips)
    }

    private fun encoder() = Encoder(64, 64, 15, 1_000_000, MediaFormat.MIMETYPE_VIDEO_AVC) {}.also {
        set(it, "format", MediaFormat())
    }

    private fun sample(atUs: Long, key: Boolean = false, rotationId: Long = 0L) =
        Sample(byteArrayOf(atUs.toByte()), atUs, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0, rotationId)

    private fun writeClips(
        recorder: Recorder,
        encoder: Encoder,
        clipLengthMs: Long,
        vararg steps: () -> Sample?
    ): Int {
        val queue = field(recorder, "samples") as SampleQueue
        set(queue, "held", ScriptedSamples(*steps))
        set(recorder, "running", true)
        val method = Recorder::class.java.getDeclaredMethod(
            "writeClips", Encoder::class.java, RecordingOptions::class.java
        ).also { it.isAccessible = true }
        try {
            method.invoke(recorder, encoder, RecordingOptions(clipLengthMs, "standard", "h264", 15, false))
            return finished(recorder).size
        } finally {
            set(recorder, "running", false)
            while (true) {
                val writer = finished(recorder).poll() ?: break
                writer.close()
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun finished(recorder: Recorder) = field(recorder, "toClose") as ArrayBlockingQueue<ClipWriter>

    private fun field(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).also { it.isAccessible = true }.get(target)

    private fun set(target: Any, name: String, value: Any) =
        target.javaClass.getDeclaredField(name).also { it.isAccessible = true }.set(target, value)

    private class ScriptedSamples(vararg steps: () -> Sample?) : ArrayBlockingQueue<Sample>(1) {
        private val pending = steps.iterator()

        override fun poll(timeout: Long, unit: TimeUnit): Sample? {
            assertTrue("The writer consumed every scripted sample", pending.hasNext())
            return pending.next().invoke()
        }
    }
}
