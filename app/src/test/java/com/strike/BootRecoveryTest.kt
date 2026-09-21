package com.strike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BootRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun bootDoesNotStartAnUnconfiguredInstallation() {
        assertFalse(canRecoverAfterBoot(temporary.root))
        configured()
        assertTrue(canRecoverAfterBoot(temporary.root))
    }

    @Test fun missingDashboardHandoverOrEitherAdbKeyPreventsAutomaticSetup() {
        for (name in listOf("dashboard.migrated", "dashboard.identity", "adbkey", "adbkey.pub")) {
            configured()
            assertTrue(File(temporary.root, name).delete())
            assertFalse("Missing $name must keep boot startup off", canRecoverAfterBoot(temporary.root))
        }
    }

    @Test fun pendingSetupWaitsForTheUserToFinish() {
        configured()
        val pending = File(temporary.root, "setup.pending").also { it.writeText("") }
        assertFalse(canRecoverAfterBoot(temporary.root))
        assertTrue(pending.delete())
        assertTrue(canRecoverAfterBoot(temporary.root))
    }

    @Test fun successfulDashboardRestorationFinishesWithoutAnotherJob() {
        val service = BootRecovery()
        val completions = mutableListOf<Boolean>()
        var active: (() -> Boolean)? = null
        var answer: ((Boolean) -> Unit)? = null
        service.recover({ current, completed -> active = current; answer = completed }, completions::add)
        assertTrue(completions.isEmpty())
        assertTrue(active!!())

        answer!!(true)
        assertFalse(active!!())
        answer!!(false)
        assertEquals(listOf(false), completions)
    }

    @Test fun anUnavailableShellReschedulesUntilAConnectionSucceeds() {
        val service = BootRecovery()
        val completions = mutableListOf<Boolean>()
        repeat(6) {
            service.recover({ _, completed -> completed(false) }, completions::add)
        }
        service.recover({ _, completed -> completed(true) }, completions::add)
        assertEquals(List(6) { true } + false, completions)
    }

    @Test fun aStoppedAttemptCannotContinueOrCompleteItsReplacement() {
        val service = BootRecovery()
        val active = mutableListOf<() -> Boolean>()
        val answers = mutableListOf<(Boolean) -> Unit>()
        val completions = mutableListOf<Boolean>()
        service.recover({ current, completed -> active.add(current); answers.add(completed) }, completions::add)
        assertTrue(active[0]())
        assertTrue(service.onStopJob(null))
        assertFalse(active[0]())
        service.recover({ current, completed -> active.add(current); answers.add(completed) }, completions::add)
        assertFalse(active[0]())
        assertTrue(active[1]())

        answers[0](true)
        answers[0](false)
        assertTrue(completions.isEmpty())
        assertTrue(active[1]())
        answers[1](true)
        assertFalse(active[1]())
        assertEquals(listOf(false), completions)
    }

    @Test fun replacingAnAttemptCancelsItsWorkWithoutStoppingTheNewAttempt() {
        val service = BootRecovery()
        val active = mutableListOf<() -> Boolean>()
        val answers = mutableListOf<(Boolean) -> Unit>()
        val completions = mutableListOf<Boolean>()
        repeat(2) {
            service.recover({ current, completed -> active.add(current); answers.add(completed) }, completions::add)
        }
        assertFalse(active[0]())
        assertTrue(active[1]())

        answers[0](true)
        assertTrue(completions.isEmpty())
        assertTrue(active[1]())
        answers[1](false)
        assertFalse(active[1]())
        assertEquals(listOf(true), completions)
    }

    @Test fun destroyedServiceCancelsTheOutstandingConnection() {
        val service = BootRecovery()
        val completions = mutableListOf<Boolean>()
        var active: (() -> Boolean)? = null
        var answer: ((Boolean) -> Unit)? = null
        service.recover({ current, completed -> active = current; answer = completed }, completions::add)
        assertTrue(active!!())
        service.onDestroy()
        assertFalse(active!!())

        answer!!(false)
        answer!!(true)
        assertTrue(completions.isEmpty())
    }

    private fun configured() {
        for (name in listOf("dashboard.migrated", "dashboard.identity", "adbkey", "adbkey.pub")) {
            File(temporary.root, name).writeText("")
        }
    }
}
