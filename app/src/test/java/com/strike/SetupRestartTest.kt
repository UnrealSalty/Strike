package com.strike

import org.junit.Assert.assertEquals
import org.junit.Test

class SetupRestartTest {
    private var restarts = 0

    @Test fun approvalWaitsForThePermissionDialogToFinishAndTheAppToReturn() {
        val setup = SetupRestart(true) { restarts++ }
        setup.permissionsRequested()
        setup.foreground(true)
        setup.shellAuthorised()
        assertEquals(0, restarts)
        setup.foreground(false)
        setup.permissionsFinished(true)
        assertEquals(0, restarts)
        setup.foreground(true)
        assertEquals(1, restarts)
    }

    @Test fun permissionsGrantedBeforeShellApprovalStillRestartOnce() {
        val setup = SetupRestart(true) { restarts++ }
        setup.permissionsRequested()
        setup.permissionsFinished(true)
        setup.foreground(true)
        assertEquals(0, restarts)
        setup.shellAuthorised()
        setup.foreground(false)
        setup.foreground(true)
        setup.shellAuthorised()
        assertEquals(1, restarts)
    }

    @Test fun anEstablishedInstallationDoesNotRestartOnConnectionOrResume() {
        val setup = SetupRestart(false) { restarts++ }
        setup.shellAuthorised()
        setup.foreground(true)
        setup.foreground(false)
        setup.foreground(true)
        setup.shellAuthorised()
        assertEquals(0, restarts)
    }

    @Test fun grantingAMissingPermissionRefreshesAnEstablishedInstallation() {
        val setup = SetupRestart(false) { restarts++ }
        setup.shellAuthorised()
        setup.permissionsRequested()
        setup.foreground(true)
        assertEquals(0, restarts)
        setup.permissionsFinished(true)
        assertEquals(1, restarts)
    }

    @Test fun decliningPermissionsDoesNotCauseARestartLoop() {
        val setup = SetupRestart(false) { restarts++ }
        setup.permissionsRequested()
        setup.shellAuthorised()
        setup.permissionsFinished(false)
        setup.foreground(true)
        assertEquals(0, restarts)
    }
}
