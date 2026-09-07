package com.strike.server

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class WebSocketTest {

    /** The example key and digest published in RFC 6455 section 1.3. */
    @Test
    fun theHandshakeAnswersWithTheDigestTheStandardPublishes() {
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", acceptKey("dGhlIHNhbXBsZSBub25jZQ=="))
        assertNull(acceptKey(null))
        assertNull(acceptKey(""))
    }

    @Test
    fun theUpgradeCarriesTheThreeHeadersAndEndsTheHead() {
        val response = String(upgradeResponse("s3pPLMBiTxaQ9kYGzzhZRbK+xOo="))
        assertEquals(
            "HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n\r\n",
            response
        )
    }

    @Test
    fun binaryHeadersGrowWithThePayloadAtTheStandardsBoundaries() {
        assertArrayEquals(byteArrayOf(0x82.toByte(), 0), header(0x2, 0))
        assertArrayEquals(byteArrayOf(0x82.toByte(), 125), header(0x2, 125))
        assertArrayEquals(byteArrayOf(0x82.toByte(), 126, 0, 126), header(0x2, 126))
        assertArrayEquals(
            byteArrayOf(0x82.toByte(), 126, 0xFF.toByte(), 0xFF.toByte()),
            header(0x2, 65535)
        )
        assertArrayEquals(
            byteArrayOf(0x82.toByte(), 127, 0, 0, 0, 0, 0, 1, 0, 0),
            header(0x2, 65536)
        )
    }

    @Test
    fun unmaskingIsItsOwnInverse() {
        val mask = byteArrayOf(0x37, 0xFA.toByte(), 0x21, 0x3D)
        val original = "Hello".toByteArray()
        val scrambled = original.copyOf()
        unmask(scrambled, mask)
        assertArrayEquals(byteArrayOf(0x7F, 0x9F.toByte(), 0x4D, 0x51, 0x58), scrambled)
        unmask(scrambled, mask)
        assertArrayEquals(original, scrambled)
    }

    @Test
    fun aSentFrameIsTheHeaderThenExactlyTheRequestedSlice() {
        val out = ByteArrayOutputStream()
        WebSocket(ByteArrayInputStream(ByteArray(0)), out).send("xxHi".toByteArray(), 2, 2)
        assertArrayEquals(byteArrayOf(0x82.toByte(), 2, 'H'.code.toByte(), 'i'.code.toByte()), out.toByteArray())
    }

    @Test
    fun aClientCloseFrameEndsTheStream() {
        val close = byteArrayOf(0x88.toByte(), 0)
        val socket = WebSocket(ByteArrayInputStream(close), ByteArrayOutputStream())
        socket.awaitClose()
        assertEquals(true, socket.isClosed)
    }

    @Test
    fun aMaskedPingIsAnsweredWithTheSameBodyUnmasked() {
        val mask = byteArrayOf(0x37, 0xFA.toByte(), 0x21, 0x3D)
        val body = "Hello".toByteArray().also { unmask(it, mask) }
        val ping = byteArrayOf(0x89.toByte(), (0x80 or 5).toByte()) + mask + body
        val out = ByteArrayOutputStream()

        WebSocket(ByteArrayInputStream(ping), out).awaitClose()

        assertArrayEquals(byteArrayOf(0x8A.toByte(), 5) + "Hello".toByteArray(), out.toByteArray())
    }
}
