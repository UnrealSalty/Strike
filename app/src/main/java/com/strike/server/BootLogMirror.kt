package com.strike.server

import com.strike.BOOT_LOG_MAX_BYTES
import java.io.File
import java.io.IOException

internal class BootLogMirror(private val file: File) {
    private var savedSource: String? = null
    private var savedVersion = -1L

    @Synchronized
    @Throws(IOException::class)
    fun save(source: String, version: Long, text: String): Long {
        if (!source.matches(SOURCE) || version < 0L) throw IOException("Invalid boot log revision")
        if (text.length > BOOT_LOG_MAX_BYTES || text.any { it != '\n' && it !in ' '..'~' }) {
            throw IOException("Invalid boot log text")
        }
        if (source == savedSource && version <= savedVersion) return savedVersion
        atomicDashboardWrite(file, text)
        savedSource = source
        savedVersion = version
        return savedVersion
    }

    private companion object {
        val SOURCE = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}
