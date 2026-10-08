package com.strike

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AutostartTest {
    @get:Rule val temporary = TemporaryFolder()
    private var installedAtMs = 1_000L
    private val autostart: Autostart
        get() = Autostart(File(temporary.root, "autostart.confirmed")) { installedAtMs }

    @Test
    fun aFreshInstallAsksForAutostart() {
        assertTrue(autostart.needsCheck())
    }

    @Test
    fun aConfirmedInstallStopsAsking() {
        autostart.confirm()
        assertFalse(autostart.needsCheck())
    }

    @Test
    fun anUpdateAfterConfirmingAsksAgain() {
        autostart.confirm()
        installedAtMs = 2_000L
        assertTrue(autostart.needsCheck())
    }

    @Test
    fun anUnreadableConfirmationAsks() {
        File(temporary.root, "autostart.confirmed").writeText("garbled")
        assertTrue(autostart.needsCheck())
    }
}
