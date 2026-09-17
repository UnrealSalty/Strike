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
    private var ownerArguments: List<String>? = null
    private val shell: File
        get() = if (File("/bin/sh").isFile) File("/bin/sh") else
            File(System.getenv("ProgramFiles") ?: "C:/Program Files", "Git/usr/bin/sh.exe")

    @Test
    fun missingWatchdogRestartsUsingTheCurrentInstallation() {
        file("start_cam.sh").writeText(original)
        assertEquals(0, recover())
        assertEquals(listOf("launched"), file("launches").readLines())
        assertTrue(file("classpath").readText().trim().endsWith("/new/base.apk"))
        assertTrue(file("arguments").readLines().contains("/new/lib/arm64"))
        assertTrue(file("cam.log").readText().contains("watchdog restarted"))
        assertFalse(file("cam.disabled").exists())
    }

    @Test
    fun aLiveWatchdogIsNotRewrittenOrDuplicated() {
        file("start_cam.sh").writeText(original)
        ownerArguments = listOf("sh", scriptPath())
        assertEquals(1, recover())
        assertUntouched()
    }

    @Test
    fun anAbsoluteShellPathAlsoIdentifiesTheWatchdog() {
        file("start_cam.sh").writeText(original)
        ownerArguments = listOf("/system/bin/sh", scriptPath())
        assertEquals(1, recover())
        assertUntouched()
    }

    @Test
    fun shellOptionsDoNotHideTheRunningWatchdog() {
        file("start_cam.sh").writeText(original)
        ownerArguments = listOf("/system/bin/sh", "-x", scriptPath())
        assertEquals(1, recover())
        assertUntouched()
    }

    @Test
    fun aReusedPidCannotHideAMissingWatchdog() {
        file("start_cam.sh").writeText(original)
        ownerArguments = listOf("unrelated")
        assertEquals(0, recover())
        assertTrue(file("launches").exists())
    }

    @Test
    fun aCommandMentioningTheScriptIsNotItsWatchdog() {
        file("start_cam.sh").writeText(original)
        ownerArguments = listOf("sh", "-c", "echo ${scriptPath()}")
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
    fun maintenanceLeavesALiveCameraAloneAndRecoversAfterItExits() {
        file("start_cam.sh").writeText(original)
        file("cam.lock").writeText("incomplete")
        file("camera-alive").writeText("")
        repeat(3) {
            assertEquals(1, recover())
            assertUntouched()
        }
        assertEquals("incomplete", file("cam.lock").readText())
        assertTrue(file("camera-alive").delete())
        assertEquals(0, recover())
        assertEquals(listOf("launched"), file("launches").readLines())
        assertTrue(file("cam.log").readText().contains("watchdog restarted"))
    }

    @Test
    fun anImmediatelyExitingWatchdogIsNotReportedAsRecovered() {
        file("start_cam.sh").writeText(original)
        val exits = listOf("#!/system/bin/sh", "INSTALLATION='$installation'", "exit 0")
        assertEquals(2, recover(watchdog = exits))
        assertFalse(file("cam.log").exists())
        assertFalse(file("cam.disabled").exists())
        assertEquals(0, recover())
        assertTrue(file("cam.log").readText().contains("watchdog restarted"))
    }

    @Test
    fun aFailedNohupLaunchCanBeRetriedWithoutFalseSuccess() {
        file("start_cam.sh").writeText(original)
        assertEquals(2, recover("nohup() { return 127; }\n"))
        assertFalse(file("launches").exists())
        assertFalse(file("cam.log").exists())
        assertFalse(file("cam.disabled").exists())
        assertEquals(0, recover())
        assertEquals(listOf("launched"), file("launches").readLines())
    }

    @Test
    fun failedScriptPublicationKeepsThePreviousScript() {
        file("start_cam.sh").writeText(original)
        assertEquals(2, recover("chmod() { return 1; }\n"))
        assertUntouched()
        assertTrue(temporary.root.listFiles()!!.none { it.name.endsWith(".tmp") })
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

    @Test
    fun stopDuringLaunchVerificationIsNotReportedAsRecovery() {
        file("start_cam.sh").writeText(original)
        assertEquals(1, recover("sleep() { echo stopped > cam.disabled; command sleep \"\$@\"; }\n"))
        assertEquals("stopped\n", file("cam.disabled").readText())
        assertTrue(!file("cam.log").exists() || !file("cam.log").readText().contains("watchdog restarted"))
    }

    private fun assertUntouched() {
        assertEquals(original, file("start_cam.sh").readText())
        assertFalse(file("launches").exists())
        assertFalse(file("cam.log").exists())
    }

    private fun recover(before: String = "", watchdog: List<String>? = null): Int {
        assertTrue("A POSIX shell is required", shell.isFile)
        file("owned-pids").writeText("")
        file("capture.sh").writeText("""
            echo ${'$'}${'$'} >> owned-pids
            printf '%s\n' "${'$'}CLASSPATH" > classpath
            printf '%s\n' "${'$'}@" > arguments
            exec sleep 30
        """.trimIndent())
        val fixture = """
            echo ${'$'}${'$'} >> owned-pids
            mkdir -p "proc/${'$'}${'$'}"
            printf '%s\000' sh "${'$'}0" > "proc/${'$'}${'$'}/cmdline"
            echo launched >> launches
            pm() { echo package:/new/base.apk; }
            app_process() { exec sh capture.sh "${'$'}@"; }
            pidof() { [ -f camera-alive ]; }
            sleep() {
                command sleep "${'$'}1" &
                SLEEP_PID=${'$'}!
                echo "${'$'}SLEEP_PID" >> owned-pids
                wait "${'$'}SLEEP_PID"
            }
        """.trimIndent().lines()
        val lines = watchdog ?: watchdogScript("com.strike", "/new/base.apk", "/new/lib/arm64",
            "com.strike.daemon.CameraDaemon", installation)
        val tracked = lines.flatMap { line ->
            if (line.trim() == "ROTATE_PID=\$!") listOf(line, "echo \"\$ROTATE_PID\" >> owned-pids")
            else listOf(line)
        }
        val owner = ownerArguments?.let { args ->
            "sleep 30 & OWNER=\$!; echo \"\$OWNER\" >> owned-pids; " +
                "echo \"\$OWNER\" > cam_watchdog.pid; mkdir -p \"proc/\$OWNER\"; " +
                "printf '%s\\000' ${args.joinToString(" ") { quote(it) }} > \"proc/\$OWNER/cmdline\"\n"
        } ?: ""
        val commands = "pidof() { [ -f camera-alive ]; }\n" + owner + before +
            recoverWatchdogLine(installation, fixture + tracked)
        try {
            return execute(commands)
        } finally {
            execute("while read PID; do case \"\$PID\" in ''|*[!0-9]*) continue;; esac; " +
                "kill -9 \"\$PID\" 2>/dev/null; done < owned-pids; exit 0")
        }
    }

    private fun execute(commands: String): Int {
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

    private fun quote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"
    private fun scriptPath(): String = file("start_cam.sh").absolutePath.replace('\\', '/')
    private fun file(name: String): File = File(temporary.root, name)
}
