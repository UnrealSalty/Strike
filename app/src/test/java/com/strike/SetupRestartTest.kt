package com.strike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import com.strike.daemon.Shell
import dadb.Dadb
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.Executor

class SetupRestartTest {
    @get:Rule val folder = TemporaryFolder()
    private var restarts = 0
    private var retries = 0
    private val pending: File get() = File(folder.root, "setup.pending")

    private var connected = false

    private fun setup(firstRun: Boolean) = SetupRestart(pending, firstRun, { retries++; connected }) { restarts++ }

    @Test fun anApprovalNobodyReportedStillRestartsWhenTheAppReturns() {
        val setup = setup(true)
        setup.permissionsRequested()
        setup.permissionsFinished()
        connected = true
        setup.foreground(true)
        assertEquals(1, restarts)
    }

    @Test fun approvalWaitsForThePermissionDialogToFinishAndTheAppToReturn() {
        val setup = setup(true)
        setup.permissionsRequested()
        setup.foreground(true)
        setup.shellAuthorised()
        assertEquals(0, restarts)
        setup.foreground(false)
        setup.permissionsFinished()
        assertEquals(0, restarts)
        setup.foreground(true)
        assertEquals(1, restarts)
    }

    @Test fun permissionsGrantedBeforeShellApprovalStillRestartOnce() {
        val setup = setup(true)
        setup.permissionsRequested()
        setup.permissionsFinished()
        setup.foreground(true)
        assertEquals(0, restarts)
        setup.shellAuthorised()
        setup.foreground(false)
        setup.foreground(true)
        setup.shellAuthorised()
        assertEquals(1, restarts)
    }

    @Test fun anEstablishedInstallationDoesNotRestartOnConnectionOrResume() {
        val setup = setup(false)
        setup.shellAuthorised()
        setup.foreground(true)
        setup.foreground(false)
        setup.foreground(true)
        setup.shellAuthorised()
        assertEquals(0, restarts)
        assertEquals(0, retries)
    }

    @Test fun runtimePermissionsAloneDoNotRestartAnEstablishedInstallation() {
        val setup = setup(false)
        setup.shellAuthorised()
        setup.permissionsRequested()
        setup.foreground(true)
        assertEquals(0, restarts)
        setup.permissionsFinished()
        assertEquals(0, restarts)
    }

    @Test fun decliningPermissionsDoesNotCauseARestartLoop() {
        val setup = setup(false)
        setup.permissionsRequested()
        setup.shellAuthorised()
        setup.permissionsFinished()
        setup.foreground(true)
        assertEquals(0, restarts)
    }

    @Test fun approvalStillRestartsAfterKeyCreationAndAppRecreation() {
        val earlier = setup(true)
        earlier.permissionsRequested()
        earlier.permissionsFinished()
        assertTrue(pending.isFile)

        val recreated = setup(false)
        recreated.shellAuthorised()
        assertEquals(0, restarts)
        recreated.foreground(true)
        assertEquals(1, restarts)
        assertFalse(pending.exists())

        val reopened = setup(false)
        reopened.foreground(true)
        reopened.shellAuthorised()
        assertEquals(1, restarts)
    }

    @Test fun anApprovalWithoutAnyResumeIsFoundByPolling() {
        val setup = setup(true)
        setup.permissionsRequested()
        setup.foreground(true)
        setup.permissionsFinished()
        assertEquals(3_000L, setup.poll())
        assertEquals(0, restarts)
        connected = true
        setup.poll()
        assertEquals(1, restarts)
        assertEquals(null, setup.poll())
    }

    @Test fun aPermissionDialogThatNeverReportedBackStopsBlockingOnceStrikeStaysInFront() {
        val setup = setup(true)
        setup.permissionsRequested()
        setup.foreground(true)
        setup.shellAuthorised()
        setup.poll()
        assertEquals(0, restarts)
        setup.poll()
        assertEquals(1, restarts)
    }

    @Test fun aPermissionDialogStillOpenKeepsBlockingThePoll() {
        val setup = setup(true)
        connected = true
        setup.permissionsRequested()
        setup.foreground(true)
        setup.foreground(false)
        repeat(3) { setup.poll() }
        assertEquals(0, restarts)
    }

    @Test fun pollingBacksOffAndNeverRunsForAnEstablishedInstallation() {
        assertEquals(null, setup(false).poll())
        assertEquals(0, retries)
        val fresh = setup(true)
        repeat(19) { assertEquals(3_000L, fresh.poll()) }
        assertEquals(30_000L, fresh.poll())
    }

    @Test fun returningFromThePromptRetriesOncePerReturn() {
        val setup = setup(true)
        setup.foreground(true)
        setup.foreground(true)
        assertEquals(1, retries)
        setup.foreground(false)
        setup.foreground(true)
        assertEquals(2, retries)
        assertEquals(0, restarts)
        setup.shellAuthorised()
        setup.foreground(false)
        setup.foreground(true)
        assertEquals(2, retries)
        assertEquals(1, restarts)
    }

    @Test fun finishingRuntimePermissionsWithoutUsbApprovalKeepsTheRestartPending() {
        val setup = setup(true)
        setup.permissionsRequested()
        setup.permissionsFinished()
        setup.foreground(true)
        assertEquals(0, restarts)
        assertTrue(pending.exists())
    }

    @Test fun approvalAfterAllConnectionTimeoutsRestartsWithoutAnotherDashboardRequest() {
        val tasks = ArrayDeque<Runnable>()
        var nowMs = 0L
        var accepted = false
        val transport = Proxy.newProxyInstance(
            Dadb::class.java.classLoader, arrayOf(Dadb::class.java)
        ) { _, method, _ -> throw AssertionError("Unexpected ADB call: ${method.name}") } as Dadb
        lateinit var setup: SetupRestart
        val shell = Shell(
            open = { if (accepted) transport else null },
            connector = Executor { tasks.addLast(it) },
            nowMs = { nowMs },
            onAuthorised = { setup.shellAuthorised() }
        )
        setup = SetupRestart(pending, true, { shell.retry() }) { restarts++ }
        setup.permissionsRequested()
        setup.foreground(true)
        setup.foreground(false)
        repeat(5) {
            tasks.removeFirst().run()
            nowMs += 60_000L
            assertFalse(shell.isAuthorised())
        }
        assertTrue(tasks.isEmpty())
        assertFalse(shell.isPending)
        assertEquals(0, restarts)

        setup.permissionsFinished()
        accepted = true
        setup.foreground(true)
        assertEquals(0, restarts)
        assertEquals(1, tasks.size)
        tasks.removeFirst().run()
        assertEquals(1, restarts)
        assertFalse(pending.exists())
        assertTrue(shell.isAuthorised())
        setup.foreground(true)
        assertEquals(1, restarts)
    }

    @Test fun returningFromUsbApprovalBeforeTheOldHandshakeFinishesStillRestarts() {
        val tasks = ArrayDeque<Runnable>()
        var attempts = 0
        val transport = Proxy.newProxyInstance(
            Dadb::class.java.classLoader, arrayOf(Dadb::class.java)
        ) { _, method, _ -> throw AssertionError("Unexpected ADB call: ${method.name}") } as Dadb
        lateinit var setup: SetupRestart
        val shell = Shell(
            open = { if (++attempts == 1) null else transport },
            connector = Executor { tasks.addLast(it) },
            onAuthorised = { setup.shellAuthorised() }
        )
        setup = SetupRestart(pending, true, { shell.retry() }) { restarts++ }
        setup.permissionsRequested()
        setup.foreground(true)
        setup.foreground(false)
        setup.permissionsFinished()
        setup.foreground(true)
        tasks.removeFirst().run()
        assertEquals(1, tasks.size)
        tasks.removeFirst().run()
        assertEquals(1, restarts)
        assertFalse(pending.exists())
        assertTrue(tasks.isEmpty())
    }
}
