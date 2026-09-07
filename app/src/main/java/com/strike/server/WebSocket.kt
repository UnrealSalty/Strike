package com.strike.server

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64

/** RFC 6455 fixes this string; the client checks the digest against it. */
private const val ACCEPT_SALT = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

private const val OPCODE_BINARY = 0x2
private const val OPCODE_CLOSE = 0x8
private const val OPCODE_PING = 0x9
private const val OPCODE_PONG = 0xA
private const val FINAL_FRAME = 0x80
private const val MASKED = 0x80
private const val LENGTH_16_BIT = 126
private const val LENGTH_64_BIT = 127
private const val SHORT_MAX = 125

/**
 * Enough of RFC 6455 to push H.264 at a browser: the handshake, binary frames
 * out, and control frames in. Chrome 58 in the head unit has no WebCodecs, so
 * MSE is fed from here and the frames have to be well formed or it stalls
 * silently rather than erroring.
 */
class WebSocket(private val input: InputStream, private val output: OutputStream) {

    @Volatile
    private var closed = false

    val isClosed: Boolean
        get() = closed

    /** One frame per call, since a partial write leaves the stream unreadable. */
    @Synchronized
    fun send(payload: ByteArray, offset: Int, length: Int) {
        if (closed) return
        try {
            output.write(header(OPCODE_BINARY, length))
            output.write(payload, offset, length)
            output.flush()
        } catch (e: IOException) {
            closed = true
        }
    }

    /**
     * Reads until the client goes away. A browser sends nothing but control
     * frames on a one-way stream, so this exists to notice the close.
     */
    fun awaitClose() {
        try {
            while (!closed) {
                val first = input.read()
                if (first < 0) break
                val second = input.read()
                if (second < 0) break
                val length = payloadLength(second and 0x7F)
                val mask = if ((second and MASKED) != 0) readFully(4) else null
                val body = readFully(length)
                if (mask != null) unmask(body, mask)
                when (first and 0x0F) {
                    OPCODE_CLOSE -> break
                    OPCODE_PING -> pong(body)
                    else -> Unit
                }
            }
        } catch (e: IOException) {
            // A dropped viewer is the normal way this ends.
        }
        closed = true
    }

    private fun pong(body: ByteArray) {
        synchronized(this) {
            output.write(header(OPCODE_PONG, body.size))
            output.write(body)
            output.flush()
        }
    }

    private fun payloadLength(stated: Int): Int = when (stated) {
        LENGTH_16_BIT -> {
            val bytes = readFully(2)
            ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
        }
        LENGTH_64_BIT -> {
            var value = 0L
            for (byte in readFully(8)) value = (value shl 8) or (byte.toLong() and 0xFF)
            if (value > Int.MAX_VALUE) throw IOException("frame too large")
            value.toInt()
        }
        else -> stated
    }

    private fun readFully(length: Int): ByteArray {
        val bytes = ByteArray(length)
        var filled = 0
        while (filled < length) {
            val count = input.read(bytes, filled, length - filled)
            if (count < 0) throw IOException("closed mid frame")
            filled += count
        }
        return bytes
    }
}

internal fun header(opcode: Int, length: Int): ByteArray = when {
    length <= SHORT_MAX -> byteArrayOf((FINAL_FRAME or opcode).toByte(), length.toByte())
    length <= 0xFFFF -> byteArrayOf(
        (FINAL_FRAME or opcode).toByte(),
        LENGTH_16_BIT.toByte(),
        (length shr 8).toByte(),
        length.toByte()
    )
    else -> byteArrayOf(
        (FINAL_FRAME or opcode).toByte(),
        LENGTH_64_BIT.toByte(),
        0, 0, 0, 0,
        (length shr 24).toByte(),
        (length shr 16).toByte(),
        (length shr 8).toByte(),
        length.toByte()
    )
}

internal fun unmask(body: ByteArray, mask: ByteArray) {
    for (i in body.indices) body[i] = (body[i].toInt() xor mask[i % 4].toInt()).toByte()
}

/** Null when the request is not a WebSocket upgrade this server will take. */
internal fun acceptKey(clientKey: String?): String? {
    if (clientKey == null || clientKey.isEmpty()) return null
    val digest = MessageDigest.getInstance("SHA-1").digest((clientKey + ACCEPT_SALT).toByteArray())
    return Base64.getEncoder().encodeToString(digest)
}

internal fun upgradeResponse(accept: String): ByteArray = (
    "HTTP/1.1 101 Switching Protocols\r\n" +
        "Upgrade: websocket\r\n" +
        "Connection: Upgrade\r\n" +
        "Sec-WebSocket-Accept: $accept\r\n\r\n"
    ).toByteArray()
