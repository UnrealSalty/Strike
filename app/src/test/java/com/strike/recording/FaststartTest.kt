package com.strike.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

private const val MEDIA_BYTES = 4096

// ftyp then mdat puts the first media byte here, which is what a chunk offset points at.
private const val FIRST_CHUNK = 20L

class FaststartTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun theIndexMovesAheadOfTheMediaData() {
        val source = clip(indexLast = true, tables = listOf(stco(listOf(FIRST_CHUNK))))
        val target = File(folder.root, "out.mp4")

        assertTrue(moovToFront(source, target))

        val written = target.readBytes()
        assertTrue(at(written, "moov") < at(written, "mdat"))
        assertEquals(source.length(), target.length())
    }

    @Test
    fun chunkOffsetsShiftByTheSizeOfTheIndex() {
        val table = stco(listOf(FIRST_CHUNK, FIRST_CHUNK + 1024))
        val source = clip(indexLast = true, tables = listOf(table))
        val target = File(folder.root, "out.mp4")

        assertTrue(moovToFront(source, target))

        val moved = moovBytes(listOf(table)).size.toLong()
        assertEquals(
            listOf(FIRST_CHUNK + moved, FIRST_CHUNK + 1024 + moved),
            offsetsIn(target.readBytes(), "stco", wide = false)
        )
    }

    @Test
    fun sixtyFourBitChunkOffsetsAreShiftedToo() {
        val table = co64(listOf(FIRST_CHUNK))
        val source = clip(indexLast = true, tables = listOf(table))
        val target = File(folder.root, "out.mp4")

        assertTrue(moovToFront(source, target))

        val moved = moovBytes(listOf(table)).size.toLong()
        assertEquals(listOf(FIRST_CHUNK + moved), offsetsIn(target.readBytes(), "co64", wide = true))
    }

    @Test
    fun everyTrackKeepsItsOwnChunkOffsets() {
        val tables = listOf(stco(listOf(FIRST_CHUNK)), stco(listOf(FIRST_CHUNK + 2048)))
        val source = clip(indexLast = true, tables = tables)
        val target = File(folder.root, "out.mp4")

        assertTrue(moovToFront(source, target))

        val moved = moovBytes(tables).size.toLong()
        assertEquals(
            listOf(FIRST_CHUNK + moved, FIRST_CHUNK + 2048 + moved),
            offsetsIn(target.readBytes(), "stco", wide = false)
        )
    }

    @Test
    fun aClipThatAlreadyLeadsWithItsIndexIsLeftAlone() {
        val source = clip(indexLast = false, tables = listOf(stco(listOf(FIRST_CHUNK))))
        val target = File(folder.root, "out.mp4")

        assertFalse(moovToFront(source, target))
        assertFalse(target.exists())
    }

    @Test
    fun aChunkOffsetThatWouldOverflowKeepsTheClipAsRecorded() {
        val source = clip(indexLast = true, tables = listOf(stco(listOf(0xFFFF_FFFFL))))
        val target = File(folder.root, "out.mp4")

        assertFalse(moovToFront(source, target))
        assertFalse(target.exists())
    }

    @Test
    fun aChunkOffsetThatMissesTheMediaKeepsTheClipAsRecorded() {
        val source = clip(indexLast = true, tables = listOf(stco(listOf(4L))))
        val target = File(folder.root, "out.mp4")

        assertFalse(moovToFront(source, target))
        assertFalse(target.exists())
    }

    @Test
    fun aChunkOffsetPastTheEndOfTheMediaKeepsTheClipAsRecorded() {
        val past = FIRST_CHUNK + MEDIA_BYTES
        val source = clip(indexLast = true, tables = listOf(stco(listOf(past))))
        val target = File(folder.root, "out.mp4")

        assertFalse(moovToFront(source, target))
        assertFalse(target.exists())
    }

    @Test
    fun somethingThatIsNotAClipIsLeftAlone() {
        val source = folder.newFile("junk.mp4")
        source.writeBytes(byteArrayOf(1, 2, 3))

        assertFalse(moovToFront(source, File(folder.root, "out.mp4")))
    }

    private fun clip(indexLast: Boolean, tables: List<ByteArray>): File {
        val ftyp = box("ftyp", "isom".toByteArray(Charsets.US_ASCII))
        val mdat = box("mdat", ByteArray(MEDIA_BYTES))
        val moov = moovBytes(tables)
        val file = folder.newFile(if (indexLast) "last.mp4" else "first.mp4")
        file.writeBytes(if (indexLast) ftyp + mdat + moov else ftyp + moov + mdat)
        return file
    }
}

private fun moovBytes(tables: List<ByteArray>): ByteArray {
    var traks = ByteArray(0)
    for (table in tables) traks += box("trak", box("mdia", box("minf", box("stbl", table))))
    return box("moov", traks)
}

private fun stco(offsets: List<Long>): ByteArray =
    box("stco", ByteArray(4) + u32(offsets.size.toLong()) + join(offsets.map { u32(it) }))

private fun co64(offsets: List<Long>): ByteArray =
    box("co64", ByteArray(4) + u32(offsets.size.toLong()) + join(offsets.map { u64(it) }))

private fun box(type: String, payload: ByteArray): ByteArray =
    u32((8 + payload.size).toLong()) + type.toByteArray(Charsets.US_ASCII) + payload

private fun join(parts: List<ByteArray>): ByteArray {
    var out = ByteArray(0)
    for (part in parts) out += part
    return out
}

private fun u32(value: Long): ByteArray = byteArrayOf(
    (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte()
)

private fun u64(value: Long): ByteArray = u32(value ushr 32) + u32(value and 0xFFFF_FFFFL)

private fun at(file: ByteArray, type: String): Int {
    val marker = type.toByteArray(Charsets.US_ASCII)
    for (i in 0..file.size - marker.size) {
        if (marker.indices.all { file[i + it] == marker[it] }) return i
    }
    throw AssertionError("no $type box was written")
}

// Media data is all zeros, so a box name only appears where a box really starts.
private fun offsetsIn(file: ByteArray, type: String, wide: Boolean): List<Long> {
    val marker = type.toByteArray(Charsets.US_ASCII)
    val found = ArrayList<Long>()
    val width = if (wide) 8 else 4
    for (i in 0..file.size - marker.size) {
        if (!marker.indices.all { file[i + it] == marker[it] }) continue
        var read = i + marker.size + 4
        val entries = readU32(file, read)
        read += 4
        repeat(entries.toInt()) {
            found.add(if (wide) readU64(file, read) else readU32(file, read))
            read += width
        }
    }
    return found
}

private fun readU32(bytes: ByteArray, at: Int): Long {
    var value = 0L
    for (i in 0 until 4) value = (value shl 8) or (bytes[at + i].toLong() and 0xFF)
    return value
}

private fun readU64(bytes: ByteArray, at: Int): Long {
    var value = 0L
    for (i in 0 until 8) value = (value shl 8) or (bytes[at + i].toLong() and 0xFF)
    return value
}
