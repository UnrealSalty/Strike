package com.strike.recording

import android.media.MediaFormat
import android.media.MediaMuxer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

class RecorderStorageTest {
    @get:Rule val folder = TemporaryFolder()
    private val card by lazy { folder.newFolder("card") }
    private val internal by lazy { folder.newFolder("internal") }
    private var now = 0L
    private var cardReady = false
    private var internalReady = false
    private var opens = 0
    private var codecFailure = false
    private val writers = ArrayList<ClipWriter>()
    private val destinations = ArrayList<File>()

    @Test fun eventStorageRetriesKeepOneEncoderAndWaitBetweenRealOpenFailures() {
        val recorder = recorder(RecordingMode.EVENT).also { it.eventActive = true }
        val encoder = encoder()
        write(recorder, encoder,
            { sample(0) },
            {
                assertEquals(1, opens)
                assertTrue(recorder.isRecording)
                assertFalse(recorder.isWriting)
                assertNull(recorder.clip)
                now = 9_999L
                sample(9_999)
            },
            { assertEquals(1, opens); now = 10_000L; sample(10_000) },
            { assertEquals(2, opens); now = 19_999L; sample(19_999) },
            { assertEquals(2, opens); cardReady = true; now = 20_000L; sample(20_000) },
            {
                assertEquals(3, opens)
                assertTrue(recorder.isRecording)
                assertTrue(recorder.isWriting)
                assertSame(encoder, field(recorder, "encoder"))
                assertTrue(recorder.clipStartedAtMs - recorder.mediaStartedAtMs <= 11_000L)
                finish(recorder)
            }
        )
        assertTrue(destinations.all { it == card })
    }

    @Test fun anExpiredEventDoesNotOpenLateAndTheNextEventGetsFreshBoundedPreRoll() {
        val recorder = recorder(RecordingMode.EVENT).also { it.eventActive = true }
        val steps = ArrayList<() -> Sample?>()
        steps.add { sample(0) }
        for (second in 1..30) steps.add {
            recorder.eventActive = false
            now = second * 1_000L
            assertEquals(1, opens)
            sample(now)
        }
        steps.add {
            assertEquals(1, opens)
            val ring = field(recorder, "ring") as PreRoll
            assertTrue(ring.spanMs <= 11_000L)
            assertTrue(ring.count <= 12)
            assertTrue(ring.snapshot().first().timeUs >= 19_000_000L)
            cardReady = true
            recorder.eventActive = true
            now = 31_000L
            sample(now)
        }
        steps.add { assertEquals(2, opens); assertTrue(recorder.isWriting); finish(recorder) }
        write(recorder, encoder(), *steps.toTypedArray())
    }

    @Test fun driveStorageWaitsForAKeyframeAfterBothDestinationsFail() {
        val recorder = recorder(RecordingMode.DRIVE)
        write(recorder, encoder(),
            { sample(0) },
            {
                assertEquals(2, opens)
                assertTrue(recorder.isRecording)
                assertFalse(recorder.isWriting)
                now = 10_000L
                cardReady = true
                sample(now, key = false)
            },
            { assertEquals(2, opens); now++; sample(now, key = false) },
            { assertEquals(2, opens); now++; sample(now) },
            {
                assertEquals(3, opens)
                assertTrue(recorder.isWriting)
                assertEquals(1L, field(writers.last(), "videoSamples"))
                finish(recorder)
            }
        )
        assertEquals(listOf(card, internal, card), destinations)
    }

    @Test fun driveStorageStillFallsBackWithoutStoppingVideo() {
        internalReady = true
        val recorder = recorder(RecordingMode.DRIVE)
        write(recorder, encoder(),
            { sample(0) },
            {
                assertEquals(2, opens)
                assertTrue(recorder.isWriting)
                assertFalse(field(recorder, "waitingForStorage") as Boolean)
                assertEquals(internal, field(writers.last(), "out"))
                finish(recorder)
            }
        )
        assertEquals(listOf(card, internal), destinations)
    }

    @Test fun aStorageFailureAtRotationClosesTheOutgoingClipAndResumesOnANewKeyframe() {
        cardReady = true
        val recorder = recorder(RecordingMode.DRIVE)
        val encoder = encoder()
        write(recorder, encoder,
            { sample(0) },
            {
                assertTrue(recorder.isWriting)
                cardReady = false
                assertTrue(encoder.rotation.request(1_000L))
                val boundary = encoder.rotation.boundary(true, 1_001L)
                sample(1_000, rotation = boundary)
            },
            {
                assertFalse(recorder.isWriting)
                assertNull(recorder.clip)
                assertEquals(1, finished(recorder).size)
                assertEquals(3, opens)
                now = 10_000L
                cardReady = true
                sample(10_000, key = false)
            },
            { assertEquals(3, opens); sample(10_001) },
            {
                assertEquals(4, opens)
                assertTrue(recorder.isWriting)
                assertEquals(1L, field(writers.last(), "videoSamples"))
                finish(recorder)
            }
        )
    }

    @Test fun stoppingDuringStorageWaitDoesNotOpenAnotherClip() {
        val recorder = recorder(RecordingMode.EVENT).also { it.eventActive = true }
        write(recorder, encoder(),
            { sample(0) },
            {
                assertEquals(1, opens)
                assertTrue(recorder.stop())
                cardReady = true
                now = 60_000L
                null
            }
        )
        assertEquals(1, opens)
        assertFalse(recorder.isWriting)
    }

    @Test fun aCodecConfigurationFailureDoesNotMasqueradeAsStorageRecovery() {
        codecFailure = true
        val recorder = recorder(RecordingMode.EVENT).also { it.eventActive = true }
        write(recorder, encoder(), { sample(0) })
        assertEquals(1, opens)
        assertFalse(recorder.isRecording)
        assertFalse(field(recorder, "waitingForStorage") as Boolean)
        assertNull(writers.single().outputFailure)
    }

    private fun recorder(mode: RecordingMode) = Recorder(card, mode, null,
        createWriter = { root ->
            ClipWriter(root) { dir, name, mayUseInternal ->
                openClipOutput(dir, name, mayUseInternal, internal, recover = { false }) { file ->
                    opens++
                    destinations.add(file.parentFile!!)
                    File(file.parentFile, ".probe").writeText("ok")
                    if (codecFailure) throw IllegalStateException("muxer configuration failed")
                    if (file.parentFile == card && !cardReady || file.parentFile == internal && !internalReady) {
                        throw IOException("open failed: EIO (I/O error)")
                    }
                    MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                }
            }.also { writers.add(it) }
        }, monotonicMs = { now }) {}

    private fun encoder() = Encoder(64, 64, 15, 1_000_000, MediaFormat.MIMETYPE_VIDEO_AVC) {}.also {
        set(it, "format", MediaFormat())
    }

    private fun sample(atMs: Long, key: Boolean = true, rotation: Long = 0L) =
        Sample(byteArrayOf(1), atMs * 1_000L, if (key) 1 else 0, rotation)

    private fun finish(recorder: Recorder): Sample? {
        set(recorder, "running", false)
        return null
    }

    private fun write(recorder: Recorder, encoder: Encoder, vararg steps: () -> Sample?) {
        val pending = steps.iterator()
        val queue = field(recorder, "samples") as SampleQueue
        set(queue, "held", object : ArrayBlockingQueue<Sample>(1) {
            override fun poll(timeout: Long, unit: TimeUnit): Sample? {
                assertTrue("Unexpected extra writer turn", pending.hasNext())
                return pending.next().invoke()
            }
        })
        set(recorder, "running", true)
        set(recorder, "encoder", encoder)
        val method = Recorder::class.java.getDeclaredMethod(
            "writeClips", Encoder::class.java, RecordingOptions::class.java
        ).also { it.isAccessible = true }
        try {
            method.invoke(recorder, encoder, RecordingOptions(Long.MAX_VALUE, "standard", "h264", 15, false))
            assertFalse("The writer exited before checking every step", pending.hasNext())
        } catch (e: InvocationTargetException) {
            throw e.targetException
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
}
