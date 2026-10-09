package com.strike.recording

internal class Mp4Boxes(capacity: Int = 4096) {

    private var bytes = ByteArray(capacity)

    var size = 0
        private set

    fun u8(value: Int) {
        room(1)
        bytes[size++] = value.toByte()
    }

    fun u16(value: Int) {
        u8(value ushr 8)
        u8(value)
    }

    fun u32(value: Long) {
        room(4)
        put32(size, value)
        size += 4
    }

    fun u32(value: Int) = u32(value.toLong() and 0xFFFF_FFFFL)

    fun u64(value: Long) {
        u32(value ushr 32)
        u32(value and 0xFFFF_FFFFL)
    }

    fun bytes(source: ByteArray, from: Int = 0, count: Int = source.size - from) {
        room(count)
        System.arraycopy(source, from, bytes, size, count)
        size += count
    }

    fun zeros(count: Int) {
        room(count)
        bytes.fill(0, size, size + count)
        size += count
    }

    fun type(name: String) {
        for (c in name) u8(c.code)
    }

    inline fun box(name: String, body: Mp4Boxes.() -> Unit) {
        val at = size
        u32(0)
        type(name)
        body()
        patch32(at, (size - at).toLong())
    }

    inline fun fullBox(name: String, version: Int, flags: Int, body: Mp4Boxes.() -> Unit) = box(name) {
        u32((version shl 24 or (flags and 0xFFFFFF)).toLong() and 0xFFFF_FFFFL)
        body()
    }

    fun patch32(at: Int, value: Long) = put32(at, value)

    fun toByteArray(): ByteArray = bytes.copyOf(size)

    private fun put32(at: Int, value: Long) {
        bytes[at] = (value ushr 24).toByte()
        bytes[at + 1] = (value ushr 16).toByte()
        bytes[at + 2] = (value ushr 8).toByte()
        bytes[at + 3] = value.toByte()
    }

    private fun room(count: Int) {
        if (size + count <= bytes.size) return
        bytes = bytes.copyOf(maxOf(bytes.size * 2, size + count))
    }
}
