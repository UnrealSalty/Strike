package com.strike

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BootAccessTest {
    @get:Rule val temporary = TemporaryFolder()

    private val names = listOf("adbkey", "adbkey.pub", "dashboard.identity", "dashboard.migrated")

    private fun configured(directory: File) {
        check(directory.isDirectory || directory.mkdirs())
        File(directory, "adbkey").writeBytes(byteArrayOf(0, 1, 127, -1, 10, 13))
        File(directory, "adbkey.pub").writeText("existing-public-key\n")
        File(directory, "dashboard.identity").writeText("existing-dashboard")
        File(directory, "dashboard.migrated").writeText("existing-dashboard")
    }

    @Test fun completedSetupMakesTheSameKeysAndDashboardAvailableBeforeUnlock() {
        val source = temporary.newFolder("credential")
        val destination = File(temporary.root, "device")
        configured(source)
        val originals = names.associateWith { File(source, it).readBytes() }

        assertTrue(prepareBootAccess(source, destination))
        assertTrue(canRecoverAfterBoot(destination))
        for ((name, bytes) in originals) {
            assertArrayEquals(name, bytes, File(destination, name).readBytes())
            assertArrayEquals("Original $name", bytes, File(source, name).readBytes())
        }
    }

    @Test fun missingSetupFilesNeverCreatesAReplacementKeyOrIdentity() {
        for (missing in names) {
            val source = temporary.newFolder("source-$missing")
            val destination = File(temporary.root, "destination-$missing")
            configured(source)
            assertTrue(File(source, missing).delete())

            assertFalse(prepareBootAccess(source, destination))
            assertFalse(canRecoverAfterBoot(destination))
            assertFalse(File(source, missing).exists())
            assertFalse(File(destination, "dashboard.migrated").exists())
        }
    }

    @Test fun incompleteSetupRevokesAnOlderBootCopyWithoutDeletingItsKeys() {
        val source = temporary.newFolder("credential")
        val destination = temporary.newFolder("device")
        configured(source)
        configured(destination)
        val existingKey = File(destination, "adbkey").readBytes()
        assertTrue(File(source, "dashboard.identity").delete())

        assertFalse(prepareBootAccess(source, destination))
        assertFalse(canRecoverAfterBoot(destination))
        assertFalse(File(destination, "dashboard.migrated").exists())
        assertArrayEquals(existingKey, File(destination, "adbkey").readBytes())
    }

    @Test fun pendingApprovalCannotEnableAutomaticStartup() {
        val source = temporary.newFolder("credential")
        val destination = temporary.newFolder("device")
        configured(source)
        configured(destination)
        val pending = File(source, "setup.pending").also { it.writeText("") }

        assertFalse(prepareBootAccess(source, destination))
        assertFalse(canRecoverAfterBoot(destination))
        assertTrue(pending.exists())
        assertTrue(pending.delete())
        assertTrue(prepareBootAccess(source, destination))
        assertTrue(canRecoverAfterBoot(destination))
    }

    @Test fun interruptedPreparationCanBeCompletedFromTheOriginalSetup() {
        val source = temporary.newFolder("credential")
        val destination = temporary.newFolder("device")
        configured(source)
        File(destination, "adbkey").writeText("partial old key")
        File(destination, "dashboard.identity").writeText("partial old identity")
        assertFalse(canRecoverAfterBoot(destination))

        assertTrue(prepareBootAccess(source, destination))
        assertTrue(canRecoverAfterBoot(destination))
        for (name in names) assertArrayEquals(File(source, name).readBytes(), File(destination, name).readBytes())
    }

    @Test fun aFailedCopyCannotLeaveStartupEnabled() {
        val source = temporary.newFolder("credential")
        val destination = temporary.newFolder("device")
        configured(source)
        configured(destination)
        val blocked = File(destination, "adbkey")
        assertTrue(blocked.delete())
        assertTrue(blocked.mkdir())
        File(blocked, "occupied").writeText("occupied")

        assertFalse(prepareBootAccess(source, destination))
        assertFalse(canRecoverAfterBoot(destination))
        assertFalse(File(destination, "dashboard.migrated").exists())
        assertArrayEquals(byteArrayOf(0, 1, 127, -1, 10, 13), File(source, "adbkey").readBytes())
    }

    @Test fun anUnavailableDestinationDoesNotDamageTheConfiguredInstallation() {
        val source = temporary.newFolder("credential")
        val destination = temporary.newFile("device")
        configured(source)
        destination.writeText("not a directory")

        assertFalse(prepareBootAccess(source, destination))
        assertEquals("not a directory", destination.readText())
        assertTrue(canRecoverAfterBoot(source))
    }

    @Test fun unchangedAccessDoesNotRewriteFilesOnEveryDashboardConnection() {
        val source = temporary.newFolder("credential")
        val destination = temporary.newFolder("device")
        configured(source)
        assertTrue(prepareBootAccess(source, destination))
        for (name in names) assertTrue(File(destination, name).setLastModified(1_600_000_000_000L))
        val timestamps = names.associateWith { File(destination, it).lastModified() }

        repeat(3) { assertTrue(prepareBootAccess(source, destination)) }
        for ((name, timestamp) in timestamps) assertEquals(name, timestamp, File(destination, name).lastModified())
        assertTrue(canRecoverAfterBoot(destination))
    }

    @Test fun aChangedKeyWithTheSameLengthAndTimestampStillReplacesTheBootCopy() {
        val source = temporary.newFolder("credential")
        val destination = temporary.newFolder("device")
        configured(source)
        assertTrue(prepareBootAccess(source, destination))
        val privateKey = File(source, "adbkey")
        val timestamp = privateKey.lastModified()
        val replacement = byteArrayOf(1, 2, 126, -2, 11, 12)
        privateKey.writeBytes(replacement)
        assertTrue(privateKey.setLastModified(timestamp))

        assertTrue(prepareBootAccess(source, destination))
        assertArrayEquals(replacement, File(destination, "adbkey").readBytes())
        assertTrue(canRecoverAfterBoot(destination))
    }

    @Test fun onlyTheBootCredentialsLeaveCredentialProtectedStorage() {
        val source = temporary.newFolder("credential")
        val destination = temporary.newFolder("device")
        configured(source)
        val privateFiles = listOf("pin.json", "browser-access.json", "online.json", "preferences.json", "last-crash.txt")
        for (name in privateFiles) File(source, name).writeText("private $name")

        assertTrue(prepareBootAccess(source, destination))
        for (name in privateFiles) {
            assertFalse(name, File(destination, name).exists())
            assertEquals("private $name", File(source, name).readText())
        }
        assertEquals(names.toSet(), destination.list()!!.toSet())
    }
}
