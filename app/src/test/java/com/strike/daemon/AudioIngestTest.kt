package com.strike.daemon

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket

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
