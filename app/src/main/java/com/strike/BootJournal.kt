package com.strike

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

internal const val BOOT_LOG_MAX_BYTES = 32 * 1024
private const val BOOT_LOG_LINE_MAX_CHARS = 1024

internal data class BootLogSnapshot(val version: Long, val text: String)

internal class BootJournal(private val file: File) {
    private var version = 0L

    @Synchronized
    @Throws(IOException::class)
    fun append(line: String) {
        val previous = readCompleteLines()
        val record = printableLine(line)
        var start = 0
        while (previous.length - start + record.length > BOOT_LOG_MAX_BYTES) {
            start = previous.indexOf('\n', start) + 1
        }
        val bytes = (previous.substring(start) + record).toByteArray(Charsets.US_ASCII)
        val pending = File(file.path + ".tmp")
        try {
            FileOutputStream(pending).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            Files.move(pending.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            version++
        } finally {
            pending.delete()
        }
    }

    @Synchronized
    @Throws(IOException::class)
    fun snapshot(afterVersion: Long): BootLogSnapshot? {
        if (afterVersion == version) return null
        return BootLogSnapshot(version, readCompleteLines())
    }

    private fun readCompleteLines(): String {
        if (!file.exists()) return ""
        return RandomAccessFile(file, "r").use { input ->
            val length = input.length()
            val offset = (length - BOOT_LOG_MAX_BYTES).coerceAtLeast(0L)
            val startsOnBoundary = if (offset == 0L) true else {
                input.seek(offset - 1L)
                input.read() == '\n'.code
            }
            input.seek(offset)
            val tail = ByteArray((length - offset).toInt())
            input.readFully(tail)
            val text = tail.toString(Charsets.US_ASCII)
            var start = if (startsOnBoundary) 0 else text.indexOf('\n') + 1
            if (!startsOnBoundary && start == 0) return@use ""
            buildString(text.length) {
                while (start < text.length) {
                    val end = text.indexOf('\n', start)
                    if (end < 0) break
                    append(printableLine(text.substring(start, end)))
                    start = end + 1
                }
            }
        }
    }

    private fun printableLine(line: String): String = buildString {
        for (index in 0 until minOf(line.length, BOOT_LOG_LINE_MAX_CHARS)) {
            val character = line[index]
            append(if (character in ' '..'~') character else ' ')
        }
        append('\n')
    }
}
