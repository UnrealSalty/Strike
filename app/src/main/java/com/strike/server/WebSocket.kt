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

    // Read control frames to detect when a viewer disconnects.
    fun awaitClose() {
        try {
            while (!closed) {
                val first = input.read()
                if (first < 0) break
                val second = input.read()
                if (second < 0) break
                val opcode = first and 0x0F
                val length = second and 0x7F
                // Live viewers send only unfragmented, masked control frames of at most 125 bytes.
                if (first and 0xF0 != FINAL_FRAME || second and MASKED == 0 || length > SHORT_MAX ||
                    opcode !in OPCODE_CLOSE..OPCODE_PONG || (opcode == OPCODE_CLOSE && length == 1)) {
                    throw IOException("Invalid live control frame")
                }
                val mask = readFully(4)
                val body = readFully(length)
                unmask(body, mask)
                when (opcode) {
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
