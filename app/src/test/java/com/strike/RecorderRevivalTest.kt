package com.strike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class RecorderRevivalTest {
    @Test fun desiredRecorderKeepsAnAlarmAfterItsApplicationProcessIsRecreated() {
        var enabled = false
        var pending = false
        fun controller() = RevivalAlarm({ enabled }, { enabled = it },
            { pending = true }, { pending = false })
        controller().setEnabled(true)
        assertTrue(enabled)
        assertTrue(pending)

        pending = false
        assertTrue(controller().fired())
        assertTrue(pending)
        assertTrue(enabled)
    }

    @Test fun repeatedHealthyStateDoesNotPostponeTheExistingAlarm() {
        var enabled = false
        var scheduled = 0
        val alarm = RevivalAlarm({ enabled }, { enabled = it }, { scheduled++ }, {})
        repeat(20) { alarm.setEnabled(true); alarm.restore() }
        assertEquals(1, scheduled)
        assertTrue(alarm.fired())
        assertEquals(2, scheduled)
    }

    @Test fun manualStopCancelsWakeupsAndStaleBroadcastDoesNotRestoreThem() {
        var enabled = true
        var pending = false
        val alarm = RevivalAlarm({ enabled }, { enabled = it },
            { pending = true }, { pending = false })
        alarm.restore()
        assertTrue(pending)
        alarm.setEnabled(false)
        assertFalse(pending)
        assertFalse(alarm.fired())
        alarm.restore()
        assertFalse(pending)
    }

    @Test fun pausedUpdateHasNoWakeupsUntilTheAuthoritativeStateResumesIt() {
        var enabled = true
        var pending = false
        val alarm = RevivalAlarm({ enabled }, { enabled = it },
            { pending = true }, { pending = false })
        alarm.restore()
        alarm.setEnabled(false)
        assertFalse(alarm.fired())
        assertFalse(pending)
        alarm.setEnabled(true)
        assertTrue(pending)
    }

    @Test fun staleStoppedReplyCannotCancelAnAlarmArmedByAStartCommand() {
        var enabled = false
        var pending = false
        val alarm = RevivalAlarm({ enabled }, { enabled = it },
            { pending = true }, { pending = false })
        val requestRevision = alarm.revision()
        alarm.setEnabled(true)
        assertFalse(alarm.reconcile(false, requestRevision))
        assertTrue(enabled)
        assertTrue(pending)
    }

    @Test fun staleRunningReplyCannotRearmAnAlarmCancelledByStop() {
        var enabled = true
        var pending = false
        val alarm = RevivalAlarm({ enabled }, { enabled = it },
            { pending = true }, { pending = false })
        alarm.restore()
        val requestRevision = alarm.revision()
        alarm.setEnabled(false)
        assertFalse(alarm.reconcile(true, requestRevision))
        assertFalse(enabled)
        assertFalse(pending)
    }

    @Test fun repeatedStopStillOverridesAnEarlierRunningReply() {
        var enabled = false
        var pending = false
        val alarm = RevivalAlarm({ enabled }, { enabled = it },
            { pending = true }, { pending = false })
        val requestRevision = alarm.revision()
        alarm.setEnabled(false)
        assertFalse(alarm.reconcile(true, requestRevision))
        assertFalse(enabled)
        assertFalse(pending)
    }

    @Test fun currentDashboardReplyCanRestoreMissingRecoveryState() {
        var enabled = false
        var pending = false
        val alarm = RevivalAlarm({ enabled }, { enabled = it },
            { pending = true }, { pending = false })
        assertTrue(alarm.reconcile(true, alarm.revision()))
        assertTrue(enabled)
        assertTrue(pending)
        assertTrue(alarm.reconcile(false, alarm.revision()))
        assertFalse(enabled)
        assertFalse(pending)
    }

    @Test fun failedSchedulingDoesNotPretendTheAlarmWasArmed() {
        var fail = true
        var scheduled = 0
        val alarm = RevivalAlarm({ true }, {}, {
            if (fail) throw IllegalStateException("Alarm service unavailable")
            scheduled++
        }, {})
        assertThrows(IllegalStateException::class.java) { alarm.restore() }
        fail = false
        alarm.restore()
        assertEquals(1, scheduled)
    }

    @Test fun duplicateWakeupsShareOneRecoveryAttempt() {
        val service = RecorderRecoveryService()
        var attempts = 0
        var completed: ((Boolean) -> Unit)? = null
        repeat(20) {
            service.recover({ _, answer -> attempts++; completed = answer }, { true }) {}
        }
        assertEquals(1, attempts)
        completed!!(true)
        assertTrue(service.recover({ _, _ -> attempts++ }, { true }) {})
        assertEquals(2, attempts)
    }

    @Test fun manualStopCancelsWorkBeforeItCanRestartARecorder() {
        val service = RecorderRecoveryService()
        var enabled = true
        var active: (() -> Boolean)? = null
        service.recover({ current, _ -> active = current }, { enabled }) {}
        assertTrue(active!!())
        enabled = false
        assertFalse(active!!())
        service.cancelAttempt()
        assertFalse(service.recover({ _, _ -> error("Stopped recorder restarted") }, { enabled }) {})
    }

    @Test fun timedOutOrStoppedAttemptCannotFinishItsReplacement() {
        val service = RecorderRecoveryService()
        val active = mutableListOf<() -> Boolean>()
        val answers = mutableListOf<(Boolean) -> Unit>()
        val outcomes = mutableListOf<Boolean>()
        service.recover({ current, completed -> active.add(current); answers.add(completed) },
            { true }, outcomes::add)
        service.cancelAttempt()
        service.recover({ current, completed -> active.add(current); answers.add(completed) },
            { true }, outcomes::add)
        assertFalse(active[0]())
        assertTrue(active[1]())
        answers[0](true)
        assertTrue(outcomes.isEmpty())
        assertTrue(active[1]())
        answers[1](true)
        assertEquals(listOf(true), outcomes)
        assertFalse(active[1]())
    }
}
