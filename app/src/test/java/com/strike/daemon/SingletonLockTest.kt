package com.strike.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException

class SingletonLockTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun aKernelOwnerWithNoPidCannotBeReplaced() {
        val path = temporary.newFile("camera.lock")
        rejectsLockedFile(path, "")
    }

    @Test
    fun aKernelOwnerWithAStalePidCannotBeReplaced() {
        val path = temporary.newFile("camera.lock")
        rejectsLockedFile(path, "999999999")
    }

    @Test
    fun anUnlockedStalePidFileCanBeReused() {
        val path = temporary.newFile("camera.lock")
        path.writeText("999999999")
        val owner = SingletonLock(path)
        try {
            assertTrue(owner.take())
        } finally {
            owner.release()
        }

        assertTrue(path.isFile)
        assertEquals(android.os.Process.myPid().toString(), path.readText())
        val next = SingletonLock(path)
        try {
            assertTrue(next.take())
        } finally {
            next.release()
        }
        assertTrue(path.isFile)
    }

    @Test
    fun releasingAFailedContenderPreservesTheActiveOwnersFile() {
        val path = temporary.newFile("camera.lock")
        val owner = SingletonLock(path)
        val contender = SingletonLock(path)
        try {
            assertTrue(owner.take())
            assertFalse(contender.take())
            contender.release()
            contender.release()
            assertTrue(path.isFile)
            assertFalse(contender.take())
        } finally {
            contender.release()
            owner.release()
        }
        assertEquals(android.os.Process.myPid().toString(), path.readText())
        try {
            assertTrue(contender.take())
        } finally {
            contender.release()
        }
    }

    @Test
    fun takingTheSameLockTwiceDoesNotLoseItsHandle() {
        val path = temporary.newFile("camera.lock")
        val owner = SingletonLock(path)
        try {
            assertTrue(owner.take())
            assertTrue(owner.take())
        } finally {
            owner.release()
        }
        RandomAccessFile(path, "rw").use { handle ->
            val held = handle.channel.tryLock()
            assertTrue(held != null)
            held!!.release()
        }
    }

    @Test
    fun aFailedOpenCanBeRetriedWhenTheDirectoryAppears() {
        val path = File(temporary.root, "missing/camera.lock")
        val owner = SingletonLock(path)
        assertFalse(owner.take())
        owner.release()
        assertTrue(path.parentFile!!.mkdirs())
        try {
            assertTrue(owner.take())
        } finally {
            owner.release()
        }
        assertTrue(path.isFile)
    }

    private fun rejectsLockedFile(path: File, pid: String) {
        RandomAccessFile(path, "rw").use { handle ->
            handle.writeBytes(pid)
            handle.channel.lock().use {
                val contender = SingletonLock(path)
                assertFalse(contender.take())
                contender.release()
                assertTrue(path.isFile)
                handle.seek(0)
                assertEquals(pid, handle.readLine() ?: "")
                val stillLocked = try {
                    handle.channel.tryLock()?.also { it.release() }
                    false
                } catch (e: OverlappingFileLockException) {
                    true
                }
                assertTrue(stillLocked)
            }
        }
        val next = SingletonLock(path)
        try {
            assertTrue(next.take())
        } finally {
            next.release()
        }
    }
}
