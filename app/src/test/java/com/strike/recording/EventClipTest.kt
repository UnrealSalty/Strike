package com.strike.recording

import android.media.MediaCodec
import android.media.MediaFormat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EventClipTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val recorder by lazy { Recorder(folder.root, RecordingMode.EVENT, null) { } }
    private val encoder = Encoder(64, 64, 30, 1_000_000, MediaFormat.MIMETYPE_VIDEO_AVC) { }
    private var writer: Thread? = null

    @After
    fun stopWriting() {
        set(Recorder::class.java, recorder, "running", false)
        writer?.join(5_000)
    }

    @Test
    fun armedSurveillanceHoldsItsFootageUntilAnEventOpensAClip() {
        write()
        feed(0L, 1_000L, 2_000L)
        until("the footage was not held") { ring().count == 3 }

        assertNull(recorder.clip)
    }

    @Test
    fun theEventClipStartsThePreRollBeforeTheSighting() {
        write()
        feed(0L, 1_000L, 2_000L, 3_000L)
        until("the footage was not held") { ring().count == 4 }

        recorder.eventActive = true
        until("no clip opened for the event") { recorder.clip != null }

        assertEquals(3_000L, recorder.clipStartedAtMs - recorder.mediaStartedAtMs)
        assertEquals(0, ring().count)
    }

    @Test
    fun theClipIsFinishedWhenTheEventWindowCloses() {
        write()
        feed(0L, 1_000L)
        recorder.eventActive = true
        until("no clip opened for the event") { recorder.clip != null }
        val opened = recorder.clip

        recorder.eventActive = false
        until("the clip stayed open after the event") { recorder.clip == null }

        assertNotNull(opened)
    }

    private fun write() {
        set(Encoder::class.java, encoder, "format", MediaFormat())
        set(Recorder::class.java, recorder, "running", true)
        val writeClips = Recorder::class.java
            .getDeclaredMethod("writeClips", Encoder::class.java, RecordingOptions::class.java)
        writeClips.isAccessible = true
        val options = RecordingOptions(120_000L, "high", "h264", 30, false)
        writer = Thread { writeClips.invoke(recorder, encoder, options) }.also { it.start() }
    }

    private fun feed(vararg atMs: Long) {
        val enqueue = Recorder::class.java.getDeclaredMethod("enqueue", Sample::class.java)
            .also { it.isAccessible = true }
        for (at in atMs) {
            enqueue.invoke(recorder, Sample(ByteArray(1), at * 1000L, MediaCodec.BUFFER_FLAG_KEY_FRAME, false))
        }
    }

    private fun ring(): PreRoll = field(Recorder::class.java, "ring").get(recorder) as PreRoll

    private fun until(reason: String, ready: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            if (ready()) return
            Thread.yield()
        }
        throw AssertionError(reason)
    }

    private fun set(owner: Class<*>, target: Any, name: String, value: Any) {
        field(owner, name).set(target, value)
    }

    private fun field(owner: Class<*>, name: String) =
        owner.getDeclaredField(name).also { it.isAccessible = true }
}
