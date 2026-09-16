package com.strike.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class WatchdogRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val installation = "10123:1000"
    private val original = "#!/system/bin/sh\nINSTALLATION='$installation'\nexit 0\n"

    @Test
    fun missingWatchdogRestartsUsingTheCurrentInstallation() {
        file("start_cam.sh").writeText(original)
        assertEquals(0, recover())
        assertEquals(listOf("launched"), file("launches").readLines())
        assertTrue(file("classpath").readText().trim().endsWith("/new/base.apk"))
        assertTrue(file("arguments").readLines().contains("/new/lib/arm64"))
        assertTrue(file("cam.log").readText().contains("recorder watchdog was missing"))
        assertFalse(file("cam.disabled").exists())
    }

    @Test
    fun aLiveWatchdogIsNotRewrittenOrDuplicated() {
        file("start_cam.sh").writeText(original)
        owner("sh", scriptPath())
        assertEquals(1, recover())
        assertUntouched()
    }

    @Test
    fun anAbsoluteShellPathAlsoIdentifiesTheWatchdog() {
        file("start_cam.sh").writeText(original)
        owner("/system/bin/sh", scriptPath())
        assertEquals(1, recover())
        assertUntouched()
    }

    @Test
    fun aReusedPidCannotHideAMissingWatchdog() {
        file("start_cam.sh").writeText(original)
        owner("unrelated")
        assertEquals(0, recover())
        assertTrue(file("launches").exists())
        assertFalse(file("killed").exists())
    }

    @Test
    fun aCommandMentioningTheScriptIsNotItsWatchdog() {
        file("start_cam.sh").writeText(original)
        owner("sh", "-c", "echo ${scriptPath()}")
        assertEquals(0, recover())
        assertTrue(file("launches").exists())
    }

    @Test
    fun aMissingProcessDoesNotKeepItsPidAlive() {
        file("start_cam.sh").writeText(original)
        file("cam_watchdog.pid").writeText("999999")
        assertEquals(0, recover())
        assertTrue(file("launches").exists())
    }

    @Test
    fun aCameraWithoutItsWatchdogIsLeftRunning() {
        file("start_cam.sh").writeText(original)
        file("cam.lock").writeText("1234")
        assertEquals(0, recover())
        assertEquals("1234", file("cam.lock").readText())
        assertFalse(file("killed").exists())
    }

    @Test
    fun manualStopIsNeverCleared() {
        file("start_cam.sh").writeText(original)
        file("cam.disabled").writeText("stopped from the app")
        assertEquals(1, recover())
        assertUntouched()
        assertEquals("stopped from the app", file("cam.disabled").readText())
    }

    @Test
    fun aRecorderThatWasNeverStartedStaysOff() {
        assertEquals(1, recover())
        assertFalse(file("start_cam.sh").exists())
        assertFalse(file("launches").exists())
        assertFalse(file("cam.log").exists())
    }

    @Test
    fun anEarlierInstallationCannotRestoreRecording() {
        val old = original.replace(installation, "10123:900")
        file("start_cam.sh").writeText(old)
        assertEquals(1, recover())
        assertEquals(old, file("start_cam.sh").readText())
        assertFalse(file("launches").exists())
    }

    @Test
    fun aLegacyScriptNeedsAnExplicitStartBeforeRecovery() {
        val old = "#!/system/bin/sh\nexit 0\n"
        file("start_cam.sh").writeText(old)
        assertEquals(1, recover())
        assertEquals(old, file("start_cam.sh").readText())
        assertFalse(file("launches").exists())
    }

    @Test
    fun stopAfterTheFirstCheckPreventsRewritingTheWatchdog() {
        file("start_cam.sh").writeText(original)
        val before = watchdogRecoveryGuard(installation) + "\necho stopped > cam.disabled\n"
        assertEquals(1, recover(before))
        assertUntouched()
    }

    @Test
    fun stopDuringScriptWritingPreventsLaunch() {
        file("start_cam.sh").writeText(original)
        assertEquals(1, recover("chmod() { echo stopped > cam.disabled; command chmod \"\$@\"; }\n"))
        assertEquals("stopped\n", file("cam.disabled").readText())
        assertFalse(file("launches").exists())
        assertFalse(file("cam.log").exists())
    }

    private fun assertUntouched() {
        assertEquals(original, file("start_cam.sh").readText())
        assertFalse(file("launches").exists())
        assertFalse(file("cam.log").exists())
    }

    private fun owner(vararg arguments: String) {
        file("cam_watchdog.pid").writeText("1234")
        val cmdline = file("proc/1234/cmdline")
        cmdline.parentFile!!.mkdirs()
        cmdline.writeBytes((arguments.joinToString("\u0000") + "\u0000").toByteArray())
    }

    private fun recover(before: String = ""): Int {
        val shell = if (File("/bin/sh").isFile) File("/bin/sh") else
            File(System.getenv("ProgramFiles") ?: "C:/Program Files", "Git/usr/bin/sh.exe")
        assertTrue("A POSIX shell is required", shell.isFile)
        val setup = """
            pm() { echo package:/new/base.apk; }
            app_process() {
                printf '%s\n' "${'$'}CLASSPATH" > classpath
                printf '%s\n' "${'$'}@" > arguments
                exit 3
            }
            nohup() { echo launched >> launches; shift; . "${'$'}1"; }
            sleep() { if [ "${'$'}1" -eq 3600 ]; then exec sleep 30; fi; }
        """.trimIndent()
        val script = watchdogScript("com.strike", "/new/base.apk", "/new/lib/arm64",
            "com.strike.daemon.CameraDaemon", installation)
        val commands = setup + "\n" + before + recoverWatchdogLine(installation, script) + "\nwait\n"
        val mapped = commands.replace(STRIKE_DIR, temporary.root.absolutePath.replace('\\', '/'))
            .replace("/proc/", temporary.root.absolutePath.replace('\\', '/') + "/proc/")
        file("test.sh").writeText(mapped)
        val output = file("output")
        val builder = ProcessBuilder(shell.absolutePath, "test.sh").directory(temporary.root)
            .redirectErrorStream(true).redirectOutput(output)
        builder.environment()["PATH"] = shell.parentFile!!.absolutePath + File.pathSeparator + builder.environment()["PATH"]
        val process = builder.start()
        try {
            assertTrue("Shell did not finish: ${output.readText()}", process.waitFor(20, TimeUnit.SECONDS))
            return process.exitValue()
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun scriptPath(): String = file("start_cam.sh").absolutePath.replace('\\', '/')
    private fun file(name: String): File = File(temporary.root, name)
}
