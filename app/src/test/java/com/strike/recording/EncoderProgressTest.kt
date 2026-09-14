package com.strike.recording

import android.media.MediaFormat
import android.os.SystemClock
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EncoderProgressTest {
    @Test
    fun startupAllowsTheExistingFormatGracePeriod() {
        assertFalse(encoderStalled(1_000L, 0L, 25_999L))
        assertTrue(encoderStalled(1_000L, 0L, 26_000L))
    }

    @Test
    fun aStartedEncoderMustKeepProducingVideo() {
        assertFalse(encoderStalled(1_000L, 3_000L, 12_999L))
        assertTrue(encoderStalled(1_000L, 3_000L, 13_000L))
    }

    @Test
    fun freshOutputPreventsAHealthyLongRunningEncoderFromRestarting() {
        val hoursLater = 12 * 60 * 60 * 1_000L
        assertFalse(encoderStalled(1_000L, hoursLater - 67L, hoursLater))
    }

    @Test
    fun outputResumingBeforeTheDeadlineKeepsTheSessionAlive() {
        assertFalse(encoderStalled(1_000L, 4_000L, 13_999L))
        assertFalse(encoderStalled(1_000L, 13_999L, 14_000L))
    }

    @Test
    fun anInitialLateFrameStartsTheShorterOngoingOutputDeadline() {
        assertFalse(encoderStalled(1_000L, 25_000L, 34_999L))
        assertTrue(encoderStalled(1_000L, 25_000L, 35_000L))
    }

    @Test
    fun aRecorderWithASlowStartingEncoderRemainsHealthyDuringStartupGrace() {
        val encoder = encoder(startedAgoMs = 24_000L)
        assertFalse(encoder.isDead)
        assertTrue(recorder(encoder).isRecording)
    }

    @Test
    fun aRecorderWithNoInitialVideoBecomesUnhealthyAfterStartupGrace() {
        val encoder = encoder(startedAgoMs = 25_000L)
        val recorder = recorder(encoder)
        assertFalse(recorder.isRecording)
        assertTrue(encoder.isDead)
    }

    @Test
    fun aSilentEncoderMakesAnOtherwiseRunningRecorderUnhealthy() {
        val encoder = encoder(startedAgoMs = 60_000L, outputAgoMs = 10_000L)
        val recorder = recorder(encoder)
        assertFalse(recorder.isRecording)
        assertTrue(encoder.isDead)
    }

    @Test
    fun aLongRunningRecorderWithFreshOutputStaysHealthy() {
        val encoder = encoder(startedAgoMs = 12 * 60 * 60 * 1_000L, outputAgoMs = 67L)
        assertTrue(recorder(encoder).isRecording)
        assertFalse(encoder.isDead)
    }

    @Test
    fun anExitedDrainMakesTheRecorderUnhealthyEvenWithRecentOutput() {
        val encoder = encoder(startedAgoMs = 5_000L, outputAgoMs = 67L)
        set(encoder, "dead", true)
        assertFalse(recorder(encoder).isRecording)
    }

    @Test
    fun aFrameArrivingAfterAStallWasDetectedDoesNotUndoPendingRecovery() {
        val encoder = encoder(startedAgoMs = 60_000L, outputAgoMs = 10_000L)
        val recorder = recorder(encoder)
        assertFalse(recorder.isRecording)
        set(encoder, "lastOutputAtMs", SystemClock.elapsedRealtime() - 1L)
        assertFalse(recorder.isRecording)
    }

    @Test
    fun anExplicitlyStoppedEncoderDoesNotReportASilenceFailure() {
        val encoder = encoder(startedAgoMs = 60_000L, outputAgoMs = 10_000L)
        encoder.stop()
        assertFalse(encoder.isDead)
    }

    private fun encoder(startedAgoMs: Long, outputAgoMs: Long? = null): Encoder {
        val encoder = Encoder(64, 64, 15, 1_000_000, MediaFormat.MIMETYPE_VIDEO_AVC) {}
        val now = SystemClock.elapsedRealtime()
        set(encoder, "startedAtMs", now - startedAgoMs)
        set(encoder, "lastOutputAtMs", outputAgoMs?.let { now - it } ?: 0L)
        set(encoder, "running", true)
        return encoder
    }

    private fun recorder(encoder: Encoder): Recorder =
        Recorder(File("."), RecordingMode.DRIVE, null) {}.also {
            set(it, "encoder", encoder)
            set(it, "running", true)
        }

    private fun set(target: Any, name: String, value: Any) {
        target.javaClass.getDeclaredField(name).also { it.isAccessible = true }.set(target, value)
    }
}
