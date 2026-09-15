package com.strike.camera

import android.media.MediaFormat
import com.strike.daemon.PacketRelay
import com.strike.recording.Encoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveStreamerTest {

    @Test
    fun noSpsMeansNoConfig() {
        assertNull(annexB(null, byteArrayOf(8, 1)))
        assertNull(annexB(byteArrayOf(), byteArrayOf(8, 1)))
    }

    @Test
    fun aRawSpsGetsAStartCode() {
        val out = annexB(byteArrayOf(0x67, 0x42), null)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x67, 0x42), out)
    }

    @Test
    fun spsAndPpsAreBothOnTheWire() {
        val out = annexB(byteArrayOf(0x67, 0x42), byteArrayOf(0x68, 0xCE.toByte()))!!
        assertEquals(12, out.size)
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0, 0, 0, 1, 0x68, 0xCE.toByte()),
            out
        )
    }

    @Test
    fun anAnnexBSpsIsNotPrefixedAgain() {
        val sps = byteArrayOf(0, 0, 0, 1, 0x67, 0x42)
        assertArrayEquals(sps, annexB(sps, null))
    }

    @Test
    fun aBlockedGraphicsOwnerKeepsTheLiveEncoderUntilDetachmentFinishes() {
        val live = LiveStreamer(PacketRelay())
        val bus = frameBusForTest()
        val encoder = encoder()
        val release = CountDownLatch(1)
        val owner = heldThread(release)
        set(bus, "thread", owner)
        set(live, "bus", bus)
        set(live, "encoder", encoder)
        set(live, "isStreaming", true)
        try {
            assertFalse(live.stop())
            assertFalse(live.isStreaming)
            assertSame(bus, field(live, "bus"))
            assertSame(encoder, field(live, "encoder"))
            assertEquals(true, field(encoder, "running"))
        } finally {
            release.countDown()
            owner.join(2_000L)
        }
        assertTrue(live.stop())
        assertNull(field(live, "bus"))
        assertNull(field(live, "encoder"))
    }

    @Test
    fun aBlockedLiveDrainIsRetainedAndCanFinishOnARetry() {
        val live = LiveStreamer(PacketRelay())
        val encoder = encoder()
        val release = CountDownLatch(1)
        val drain = heldThread(release)
        set(encoder, "drain", drain)
        set(live, "encoder", encoder)
        set(live, "isStreaming", true)
        try {
            assertFalse(live.stop())
            assertFalse(live.isStreaming)
            assertSame(encoder, field(live, "encoder"))
            assertSame(drain, field(encoder, "drain"))
        } finally {
            release.countDown()
            drain.join(2_000L)
        }
        assertTrue(live.stop())
        assertNull(field(live, "encoder"))
        assertNull(field(encoder, "drain"))
    }

    @Test
    fun stoppingAnIdleLiveStreamIsIdempotent() {
        val live = LiveStreamer(PacketRelay())
        assertTrue(live.stop())
        assertTrue(live.stop())
        assertFalse(live.isStreaming)
    }

    private fun encoder() = Encoder(64, 64, 12, 1_000_000, MediaFormat.MIMETYPE_VIDEO_AVC) {}.also {
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
