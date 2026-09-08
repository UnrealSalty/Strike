package com.strike.daemon

import dadb.AdbShellResponse
import dadb.Dadb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ShellTest {

    private val tasks = ArrayDeque<Runnable>()
    private var nowMs = 0L
    private var closed = 0
    private var command: (String) -> AdbShellResponse = { AdbShellResponse("shell reply", "", 0) }
    private val transport = Proxy.newProxyInstance(
        Dadb::class.java.classLoader, arrayOf(Dadb::class.java)
    ) { _, method, args ->
        when (method.name) {
            "shell" -> command(args[0] as String)
            "close" -> { closed++; null }
            else -> throw AssertionError("Unexpected ADB call: ${method.name}")
        }
    } as Dadb
    private var open: () -> Dadb? = { transport }
    private var authorisations = 0
    private val shell = Shell(
        open = { open() },
        connector = Executor { tasks.addLast(it) },
        nowMs = { nowMs },
        onAuthorised = { authorisations++ }
    )

    @Test
    fun lateApprovalKeepsTheAuthorisedTransport() {
        assertFalse(shell.isAuthorised())
        assertTrue(shell.isPending)

        repeat(10) {
            nowMs += 2_000
            assertFalse(shell.isAuthorised())
            assertNull(shell.read("echo ready"))
        }
        assertEquals(1, tasks.size)
        tasks.removeFirst().run()

        assertTrue(shell.isAuthorised())
        assertFalse(shell.isPending)
        assertEquals("shell reply", shell.read("echo ready"))
        assertTrue(tasks.isEmpty())
        assertEquals(0, closed)
        assertEquals(1, authorisations)
    }

    @Test
    fun refusedConnectionRecoversAfterApprovalAndBackoff() {
        open = { null }
        assertFalse(shell.isAuthorised())
        tasks.removeFirst().run()
        assertFalse(shell.isAuthorised())
        assertTrue(shell.isPending)

        open = { transport }
        nowMs = 4_999
        assertFalse(shell.isAuthorised())
        assertTrue(tasks.isEmpty())
        nowMs = 5_000
        assertFalse(shell.isAuthorised())
        tasks.removeFirst().run()

        assertTrue(shell.isAuthorised())
        assertEquals("shell reply", shell.read("echo ready"))
    }

    @Test
    fun repeatedFailuresStopUntilConnectIsPressed() {
        open = { null }
        repeat(5) {
            assertFalse(shell.isAuthorised())
            tasks.removeFirst().run()
            assertFalse(shell.isAuthorised())
            nowMs += 60_000
        }
        assertFalse(shell.isPending)

        open = { transport }
        repeat(10) {
            nowMs += 60_000
            assertFalse(shell.isAuthorised())
            assertNull(shell.read("echo ready"))
        }
        assertTrue(tasks.isEmpty())

        assertFalse(shell.retry())
        tasks.removeFirst().run()
        assertTrue(shell.isAuthorised())
        assertFalse(shell.isPending)
        assertEquals("shell reply", shell.read("echo ready"))
    }

    @Test
    fun connectDoesNotDiscardPendingApprovalOrAnAuthorisedConnection() {
        assertFalse(shell.retry())
        repeat(5) {
            nowMs += 5_000
            assertFalse(shell.retry())
        }
        assertEquals(1, tasks.size)
        tasks.removeFirst().run()

        assertTrue(shell.retry())
        assertTrue(shell.retry())
        assertEquals("shell reply", shell.read("echo ready"))
        assertEquals(0, closed)
        assertTrue(tasks.isEmpty())
    }

    @Test
    fun failedHandshakeCanRecoverOnTheSameShell() {
        open = { throw IOException("authorization pending") }
        assertFalse(shell.isAuthorised())
        tasks.removeFirst().run()
        assertFalse(shell.isAuthorised())

        open = { transport }
        nowMs += 5_000
        assertFalse(shell.isAuthorised())
        tasks.removeFirst().run()

        assertTrue(shell.isAuthorised())
        assertEquals("shell reply", shell.read("echo ready"))
    }

    @Test
    fun losingTheTransportClearsAuthorisationAndAllowsRecovery() {
        authorise()
        command = { throw IOException("connection reset") }
        assertNull(shell.read("echo ready"))
        assertEquals(1, closed)
        assertFalse(shell.isAuthorised())
        assertTrue(shell.isPending)

        command = { AdbShellResponse("reconnected", "", 0) }
        tasks.removeFirst().run()

        assertTrue(shell.isAuthorised())
        assertEquals("reconnected", shell.read("echo ready"))
    }

    @Test
    fun aNonzeroCommandExitDoesNotRevokeAuthorisation() {
        authorise()
        command = { AdbShellResponse("", "missing file", 1) }
        assertFalse(shell.check("test -f /missing"))
        assertNull(shell.read("cat /missing"))
        assertEquals(1, shell.run("cat /missing"))
        assertTrue(shell.isAuthorised())
        assertEquals(0, closed)
    }

    @Test
    fun aBusyCommandDoesNotBlockStatusOrCloseTheConnectionOnRetry() {
        authorise()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        command = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            AdbShellResponse("finished", "", 0)
        }
        try {
            val reply = workers.submit<String?> { shell.read("slow command") }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue(workers.submit<Boolean> { shell.isAuthorised() && shell.retry() }
                .get(2, TimeUnit.SECONDS))
            assertEquals(0, closed)
            release.countDown()
            assertEquals("finished", reply.get(2, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            workers.shutdownNow()
        }
    }

    private fun authorise() {
        assertFalse(shell.isAuthorised())
        tasks.removeFirst().run()
        assertTrue(shell.isAuthorised())
    }
}
