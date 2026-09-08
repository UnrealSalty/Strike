package com.strike.update

import com.strike.server.api.UpdatesApi
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.Executor

class UpdatesTest {
    @get:Rule val folder = TemporaryFolder()
    private val tasks = ArrayDeque<Runnable>()
    private var now = 1_000_000_000L
    private var online = true
    private var offered: Release? = Release("v0.2", "Changes",
        "https://github.com/UnrealSalty/Strike/releases/download/v0.2/Strike.apk", 3, "a".repeat(64))
    private var fetches = 0
    private var downloads = 0
    private var installs = 0
    private var pending = false
    private var success: Boolean? = true
    private var fetchError: Exception? = null
    private var verifyError: Exception? = null
    private var onDownload: () -> Unit = {}
    private var onVerify: () -> Unit = {}
    private var lastEtag = ""
    private val apk get() = File(folder.root, "update.apk")

    private fun updates(current: String = "0.1") = Updates(current,
        File(folder.root, "updates.json"), apk, { online },
        { etag, _ ->
            fetches++
            lastEtag = etag
            fetchError?.let { throw it }
            ReleaseReply(offered, "etag-1")
        },
        { _, file, progress ->
            downloads++
            file.writeBytes(byteArrayOf(1, 2, 3))
            progress(3)
            onDownload()
        },
        { _, _ -> onVerify(); verifyError?.let { throw it } },
        { _, _ -> verifyError?.let { throw it }; installs++; pending = true }, { pending },
        { success.also { if (it != null) pending = false } },
        Executor { tasks.addLast(it) }, { now }, { now += it })

    private fun run() { tasks.removeFirst().run() }

    private fun available(updates: Updates) { assertTrue(updates.check()); run() }

    @Test
    fun startupChecksOnceADayAcrossAppRestartsWithoutDownloading() {
        val first = updates()
        first.resume()
        first.resume()
        run()
        assertTrue(tasks.isEmpty())
        assertEquals(1, fetches)
        assertEquals(0, downloads)
        val reopened = updates()
        reopened.resume()
        assertTrue(tasks.isEmpty())
        now += 86_400_000
        reopened.resume()
        run()
        assertEquals(2, fetches)
        assertEquals("etag-1", lastEtag)
        assertEquals(0, downloads)
    }

    @Test
    fun failedChecksAreThrottledAcrossRestartsButManualRetryBypassesTheDay() {
        fetchError = IOException("Disconnected")
        available(updates())
        val reopened = updates()
        assertFalse(reopened.check())
        assertFalse(reopened.check(manual = true))
        now += 15_000
        fetchError = null
        assertTrue(reopened.check(manual = true))
        run()
        assertTrue(reopened.status().getBoolean("available"))
        assertFalse(reopened.status().getBoolean("failed"))
        assertEquals(2, fetches)
    }

    @Test
    fun anOfflineStartupDoesNotUseUpTheNextConnectedCheck() {
        val updates = updates()
        online = false
        updates.resume()
        assertTrue(tasks.isEmpty())
        assertFalse(updates.check(manual = true))
        assertEquals("No internet connection", updates.status().getString("message"))
        online = true
        updates.resume()
        run()
        assertEquals(1, fetches)
    }

    @Test
    fun settingTheClockBackDoesNotBlockChecksIndefinitely() {
        val updates = updates()
        available(updates)
        now -= 3_600_000
        assertTrue(updates.check())
        run()
        assertEquals(2, fetches)
    }

    @Test
    fun equalAndOlderVersionsCannotBeDownloadedOrInstalled() {
        for (current in listOf("0.2.0", "0.3")) {
            now += 86_400_000
            val updates = updates(current)
            available(updates)
            assertFalse(updates.status().getBoolean("available"))
            assertFalse(updates.download())
            assertFalse(updates.install())
        }
        assertEquals(0, downloads)
        assertEquals(0, installs)
    }

    @Test
    fun checksDoNotOverlapDownloadsAndValidationMustFinishBeforeInstallation() {
        val updates = updates()
        available(updates)
        onDownload = {
            assertEquals("downloading", updates.status().getString("phase"))
            assertEquals(3, updates.status().getInt("receivedBytes"))
            assertFalse(updates.install())
            assertFalse(updates.download())
            assertFalse(updates.check(manual = true))
        }
        onVerify = { assertFalse(updates.status().getBoolean("ready")) }
        assertTrue(updates.download())
        run()
        assertTrue(updates.status().getBoolean("ready"))
        assertEquals(0, installs)
    }

    @Test
    fun failedValidationRemovesTheDownloadAndNeverStartsInstallation() {
        val updates = updates()
        available(updates)
        verifyError = IllegalArgumentException("The signing key differs")
        updates.download()
        run()
        assertFalse(apk.exists())
        assertFalse(updates.status().getBoolean("ready"))
        assertTrue(updates.status().getBoolean("failed"))
        assertFalse(updates.install())
        assertEquals(0, installs)
    }

    @Test
    fun changingTheApkAfterDownloadPreventsInstallation() {
        val updates = updates()
        available(updates)
        updates.download()
        run()
        verifyError = IllegalArgumentException("The APK changed")
        assertTrue(updates.install())
        run()
        assertFalse(updates.isInstalling())
        assertTrue(updates.status().getBoolean("failed"))
        assertFalse(updates.status().getBoolean("ready"))
        assertFalse(apk.exists())
        assertEquals(0, installs)
    }

    @Test
    fun replacingTheReleaseInvalidatesAnAlreadyDownloadedApk() {
        val updates = updates()
        available(updates)
        updates.download()
        run()
        offered = offered!!.copy(sha256 = "b".repeat(64))
        now += 15_000
        updates.check(manual = true)
        run()
        assertFalse(updates.status().getBoolean("ready"))
        assertFalse(updates.install())
    }

    @Test
    fun aFailedCheckKeepsTheLastKnownRelease() {
        val updates = updates()
        available(updates)
        now += 15_000
        fetchError = IOException("Disconnected")
        updates.check(manual = true)
        run()
        assertEquals("v0.2", updates.status().getString("latest"))
        assertTrue(updates.status().getBoolean("failed"))
        assertEquals(1_000_000_000L, updates.status().getLong("checkedAtMs"))
    }

    @Test
    fun theInstallApiRequiresConfirmationAndCannotStartTwice() {
        val updates = updates()
        available(updates)
        updates.download()
        run()
        val api = UpdatesApi(updates)
        assertEquals(400, api.update("action=install").status)
        assertEquals(0, installs)
        assertEquals(200, api.update("action=install&confirmed=true").status)
        assertTrue(updates.isInstalling())
        assertFalse(updates.install())
        run()
        assertEquals(1, installs)
        assertFalse(updates.isInstalling())
        assertEquals("Update installed", updates.status().getString("message"))
        assertFalse(apk.exists())
    }

    @Test
    fun aPendingInstallationResumesWithoutStartingAnotherInstallerOrGithubCheck() {
        pending = true
        success = false
        val updates = updates()
        assertTrue(updates.isInstalling())
        updates.resume()
        run()
        assertFalse(updates.isInstalling())
        assertEquals(0, fetches)
        assertEquals(0, installs)
        assertTrue(updates.status().getBoolean("failed"))
        assertEquals("Android could not install the update", updates.status().getString("message"))
    }

    @Test
    fun aSlowInstallationDoesNotLeaveAWorkerPollingForever() {
        pending = true
        success = null
        val updates = updates()
        updates.resume()
        run()
        assertTrue(tasks.isEmpty())
        assertTrue(updates.isInstalling())
        assertFalse(updates.check())
        success = true
        assertTrue(updates.check(manual = true))
        run()
        assertFalse(updates.isInstalling())
        assertEquals(0, installs)
    }
}
