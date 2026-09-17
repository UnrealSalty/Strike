package com.strike.camera

import android.opengl.EGL14
import android.graphics.SurfaceTexture
import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsumerTest {
    @Test
    fun livePacingLeavesRecorderAndDetectorFramesUnchanged() {
        val recorder = consumer {}
        val detector = Consumer("detector", Surface(null as SurfaceTexture?), CameraView.ALL, Frame(64, 64))
        val live = Consumer("live", Surface(null as SurfaceTexture?), CameraView.ALL, Frame(64, 64), frameRateFps = 12)
        assertTrue(live.wantsFrame(0L))
        assertFalse(live.wantsFrame(1_000_000L))
        for (timestampNs in listOf(0L, 1_000_000L, 1_000_000L, 2_000_000L)) {
            assertTrue(recorder.wantsFrame(timestampNs))
            assertTrue(detector.wantsFrame(timestampNs))
        }
    }

    @Test
    fun threeConsecutiveBadSurfacesFailOnlyThatConsumer() {
        var recorderFailures = 0
        var detectorFailures = 0
        val recorder = consumer { recorderFailures++ }
        val detector = consumer { detectorFailures++ }
        repeat(2) { recorder.rejected(EGL14.EGL_BAD_SURFACE) }
        detector.accepted()
        assertEquals(0, recorderFailures)

        recorder.rejected(EGL14.EGL_BAD_SURFACE)
        assertEquals(1, recorderFailures)
        assertEquals(0, detectorFailures)
    }

    @Test
    fun aSuccessfulFrameClearsTransientSurfaceFailures() {
        var failures = 0
        val consumer = consumer { failures++ }
        repeat(2) { consumer.rejected(EGL14.EGL_BAD_SURFACE) }
        consumer.accepted()
        repeat(2) { consumer.rejected(EGL14.EGL_BAD_SURFACE) }
        assertEquals(0, failures)
        consumer.rejected(EGL14.EGL_BAD_SURFACE)
        assertEquals(1, failures)
    }

    @Test
    fun anotherGraphicsErrorDoesNotCountAsAConsecutiveBadSurface() {
        var failures = 0
        val consumer = consumer { failures++ }
        repeat(2) { consumer.rejected(EGL14.EGL_BAD_SURFACE) }
        consumer.rejected(EGL14.EGL_BAD_ALLOC)
        consumer.rejected(EGL14.EGL_BAD_SURFACE)
        assertEquals(0, failures)
    }

    @Test
    fun aFailedConsumerSignalsOnlyOnceEvenIfMoreFramesArrive() {
        var failures = 0
        val consumer = consumer { failures++ }
        repeat(3) { consumer.rejected(EGL14.EGL_BAD_SURFACE) }
        consumer.accepted()
        consumer.unusable()
        repeat(10) { consumer.rejected(EGL14.EGL_BAD_SURFACE) }
        assertEquals(1, failures)
    }

    @Test
    fun anInvalidInputSurfaceFailsImmediately() {
        var failures = 0
        val consumer = consumer { failures++ }
        consumer.unusable()
        assertEquals(1, failures)
    }

    @Test
    fun aReplacementConsumerStartsWithoutTheOldFailures() {
        var failures = 0
        val old = consumer { failures++ }
        repeat(3) { old.rejected(EGL14.EGL_BAD_SURFACE) }
        val replacement = consumer { failures++ }
        replacement.accepted()
        repeat(2) { replacement.rejected(EGL14.EGL_BAD_SURFACE) }
        assertEquals(1, failures)
    }

    private fun consumer(failed: () -> Unit) =
        Consumer("recorder", Surface(null as SurfaceTexture?), CameraView.ALL, Frame(64, 64), failed)
}
