package com.strike.daemon

import com.strike.recording.Sample
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

private const val SAMPLE_RATE = 48_000
private const val CHANNELS = 1
private const val BITRATE_BPS = 64_000
private val CSD = byteArrayOf(0x11, 0x88.toByte())

class AudioIngestTest {

    private val ingest = AudioIngest()

    @After
    fun shut() = ingest.stop()

    @Test
    fun theDaemonReadsWhatTheAppSends() {
        Thread({ ingest.serveForever() }, "ingest").also { it.isDaemon = true }.start()
        val link = DataOutputStream(connect().getOutputStream())

        sendConfig(link)
        val config = await { ingest.config }

        assertNotNull("the header never arrived", config)
        assertEquals(SAMPLE_RATE, config!!.sampleRate)
        assertEquals(CHANNELS, config.channelCount)
        assertEquals(BITRATE_BPS, config.bitrateBps)
        assertEquals(CSD.toList(), config.csd.toList())

        sendFrame(link, timeUs = 1_234_567L, bytes = byteArrayOf(1, 2, 3, 4))
        val frame = await { ingest.take() }

        assertNotNull("the audio frame never arrived", frame)
        assertEquals(1_234_567L, frame!!.timeUs)
        assertEquals(listOf<Byte>(1, 2, 3, 4), frame.bytes.toList())
    }

    @Test
    fun aFrameIsGoneOnceItHasBeenTaken() {
        Thread({ ingest.serveForever() }, "ingest").also { it.isDaemon = true }.start()
        val link = DataOutputStream(connect().getOutputStream())

        sendConfig(link)
        sendFrame(link, timeUs = 1L, bytes = byteArrayOf(9))
        await { ingest.take() }

        assertNull(ingest.take())
    }

    @Test
    fun anAudioHeaderAloneCannotStartAClipTrack() {
        configured()

        assertNull(ingest.takeForClip(1_000L))
    }

    @Test
    fun audioBeforeTheClipBoundaryCannotStartItsTrack() {
        configured()
        frames().add(Sample(byteArrayOf(1), 999L, 0))

        assertNull(ingest.takeForClip(1_000L))
        assertEquals(0, ingest.queuedFrames)
    }

    @Test
    fun aClipReservesItsFirstNonemptyAudioFrameWithItsFormat() {
        val config = configured()
        val first = Sample(byteArrayOf(1, 2, 3), 1_000L, 0)
        val next = Sample(byteArrayOf(4, 5), 1_021L, 0)
        frames().add(Sample(byteArrayOf(9), 999L, 0))
        frames().add(Sample(byteArrayOf(), 1_000L, 0))
        frames().add(first)
        frames().add(next)

        val start = ingest.takeForClip(1_000L)

        assertNotNull(start)
        assertSame(config, start!!.config)
        assertSame(first, start.first)
        assertSame(next, ingest.take())
        assertNull(ingest.take())
    }

    @Test
    fun anAudioDisconnectLeavesNoFrameToStartAClipTrack() {
        Thread({ ingest.serveForever() }, "ingest").also { it.isDaemon = true }.start()
        connect().use { client ->
            val link = DataOutputStream(client.getOutputStream())
            sendConfig(link)
            sendFrame(link, timeUs = 1_000L, bytes = byteArrayOf(1))
            assertNotNull(await { if (ingest.queuedFrames > 0) true else null })
        }

        assertNotNull(await { if (ingest.config == null && ingest.queuedFrames == 0) true else null })
        assertNull(ingest.takeForClip(1_000L))
    }

    @Test
    fun searchingForClipAudioYieldsWhenOldFramesKeepArriving() {
        configured()
        val queued = object : ArrayBlockingQueue<Sample>(200) {
            var consumed = 0
            override fun poll(): Sample? {
                assertTrue("Searching for clip audio did not yield", consumed < 4)
                val first = super.poll() ?: return null
                consumed++
                add(Sample(byteArrayOf(1), 999L, 0))
                return first
            }
        }
        queued.add(Sample(byteArrayOf(1), 999L, 0))
        queued.add(Sample(byteArrayOf(2), 999L, 0))
        set("frames", queued)

        assertNull(ingest.takeForClip(1_000L))

        assertEquals(2, queued.consumed)
        assertEquals(2, ingest.queuedFrames)
    }

    @Test
    fun anAudioReconnectCannotPairAnOldFrameWithTheNewFormat() {
        configured()
        val replacement = AudioConfig(44_100, CHANNELS, BITRATE_BPS, CSD)
        val queued = object : ArrayBlockingQueue<Sample>(1) {
            override fun poll(): Sample? = super.poll()?.also { set("config", replacement) }
        }
        queued.add(Sample(byteArrayOf(1), 1_000L, 0))
        set("frames", queued)

        assertNull(ingest.takeForClip(1_000L))
        assertSame(replacement, ingest.config)
    }

    @Test
    fun delayedAudioCanStartTheClipWithoutDiscardingThePreviousClipsTail() {
        configured()
        val old = Sample(byteArrayOf(1), 980L, 0)
        val next = Sample(byteArrayOf(2), 1_021L, 0)
        val written = ArrayList<Sample>()
        val queued = object : ArrayBlockingQueue<Sample>(2) {
            var waits = 0
            override fun poll(timeout: Long, unit: TimeUnit): Sample? {
                assertTrue(timeout > 0L)
                waits++
                return super.poll() ?: next
            }
        }
        queued.add(old)
        set("frames", queued)

        val start = ingest.takeForClip(1_000L, 1_000L, written::add)

        assertSame(next, start!!.first)
        assertEquals(listOf(old), written)
        assertEquals(2, queued.waits)
    }

    @Test
    fun aSilentMicrophoneReturnsWithoutInventingAnAudioSample() {
        configured()
        val queued = object : ArrayBlockingQueue<Sample>(1) {
            var waits = 0
            override fun poll(timeout: Long, unit: TimeUnit): Sample? {
                waits++
                assertTrue(unit.toMillis(timeout) <= 1_000L)
                return null
            }
        }
        set("frames", queued)

        assertNull(ingest.takeForClip(1_000L, 1_000L))
        assertEquals(1, queued.waits)
    }

    private fun configured() = AudioConfig(SAMPLE_RATE, CHANNELS, BITRATE_BPS, CSD).also {
        set("config", it)
    }

    @Suppress("UNCHECKED_CAST")
    private fun frames(): ArrayBlockingQueue<Sample> =
        AudioIngest::class.java.getDeclaredField("frames").also {
            it.isAccessible = true
        }.get(ingest) as ArrayBlockingQueue<Sample>

    private fun set(name: String, value: Any) {
        AudioIngest::class.java.getDeclaredField(name).also {
            it.isAccessible = true
        }.set(ingest, value)
    }

    private fun connect(): Socket {
        var last: Exception? = null
        for (attempt in 0 until 50) {
            try {
                return Socket().also { it.connect(InetSocketAddress("127.0.0.1", AUDIO_PORT), 200) }
            } catch (e: Exception) {
                last = e
                Thread.sleep(20)
            }
        }
        throw AssertionError("the daemon never opened the audio port", last)
    }

    private fun <T> await(read: () -> T?): T? {
        for (attempt in 0 until 100) {
            read()?.let { return it }
            Thread.sleep(20)
        }
        return null
    }

    private fun sendConfig(link: DataOutputStream) {
        link.writeByte(AUDIO_KIND_CONFIG)
        link.writeLong(0)
        link.writeInt(CSD.size)
        link.write(CSD)
        link.writeInt(SAMPLE_RATE)
        link.writeInt(CHANNELS)
        link.writeInt(BITRATE_BPS)
        link.flush()
    }

    private fun sendFrame(link: DataOutputStream, timeUs: Long, bytes: ByteArray) {
        link.writeByte(AUDIO_KIND_FRAME)
        link.writeLong(timeUs)
        link.writeInt(bytes.size)
        link.write(bytes)
        link.flush()
    }
}
