package com.strike.update

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.security.MessageDigest

class ReleaseDownloadTest {
    @get:Rule val folder = TemporaryFolder()
    private val bytes = ByteArray(150_000) { (it % 251).toByte() }
    private val release = Release("v0.2", "", "", bytes.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })

    @Test
    fun aVerifiedDownloadPreservesEveryByteAndReportsProgress() {
        val file = folder.newFile()
        val progress = mutableListOf<Long>()
        writeDownload(bytes.inputStream(), file, release, progress::add)
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(release.sha256, fileDigest(file))
        assertEquals(bytes.size.toLong(), progress.last())
        assertTrue(progress.zipWithNext().all { (a, b) -> a < b })
    }

    @Test
    fun truncationAndCorruptionNeverBecomeAnAcceptedDownload() {
        for (broken in listOf(bytes.copyOf(bytes.size - 1), bytes.copyOf().also { it[100] = 0 })) {
            assertThrows(IOException::class.java) {
                writeDownload(broken.inputStream(), folder.newFile(), release) {}
            }
        }
    }

    @Test
    fun theExpectedSizeIsAHardWriteLimit() {
        val file = folder.newFile()
        assertThrows(IOException::class.java) {
            writeDownload((bytes + bytes).inputStream(), file, release) {}
        }
        assertTrue(file.length() <= release.bytes)
    }
}
