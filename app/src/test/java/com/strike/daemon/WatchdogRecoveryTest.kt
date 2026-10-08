package com.strike.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
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

    @Test
    fun aStoppedRecorderDoesNotMistakeAReusedPidForItsWatchdog() {
        file("cam.disabled").writeText("stopped from the app")

        assertEquals(1, running(listOf("unrelated")))

        assertEquals("stopped from the app", file("cam.disabled").readText())
        assertFalse(file("start_cam.sh").exists())
        assertFalse(file("launches").exists())
    }

    @Test
    fun mentioningTheScriptDoesNotKeepAnInactiveRecorderRunning() {
        assertEquals(1, running(listOf("sh", "-c", "echo ${scriptPath()}")))
        assertFalse(file("start_cam.sh").exists())
    }

    @Test
    fun theWatchdogIdentityKeepsTheRecorderRunningWhileItsCameraStarts() {
        assertEquals(0, running(listOf("/system/bin/sh", "-x", scriptPath())))
        assertFalse(file("camera-alive").exists())
    }

    @Test
    fun missingOrMalformedWatchdogPidsDoNotKeepTheRecorderRunning() {
        assertEquals(1, running())
        for (pid in listOf("", "not-a-pid", "123 456", "-1", "999999")) {
            assertEquals("Invalid watchdog pid $pid", 1, running(pid = pid))
        }
        assertFalse(file("launches").exists())
    }

    @Test
    fun aLiveCameraKeepsTheRecorderRunningWithoutAWatchdogPid() {
        file("camera-alive").writeText("")

        assertEquals(0, running())

        assertFalse(file("cam_watchdog.pid").exists())
        assertFalse(file("launches").exists())
    }

    @Test
    fun androidRecoveryDoesNotRestartAHealthyCamera() {
        file("start_cam.sh").writeText(original)
        file("camera-alive").writeText("1234")

        assertEquals(0, requestRevival())

        assertEquals(listOf("broadcast", "--user", "0", "--include-stopped-packages", "--receiver-foreground",
            "-n", "com.strike/.RecorderRevival", "-a", "com.strike.RECORDER_REVIVAL_WAKE"),
            file("broadcast-arguments").readLines())
        assertEquals("1234", file("camera-alive").readText())
        assertUntouched()
    }

    @Test
    fun androidRecoveryWakesTheInstalledAppsUser() {
        file("start_cam.sh").writeText(original)

        assertEquals(0, requestRevival(user = 10))

        assertEquals(listOf("broadcast", "--user", "10", "--include-stopped-packages", "--receiver-foreground",
            "-n", "com.strike/.RecorderRevival", "-a", "com.strike.RECORDER_REVIVAL_WAKE"),
            file("broadcast-arguments").readLines())
        assertUntouched()
    }

    @Test
    fun manualStopPreventsAndroidRecovery() {
        file("start_cam.sh").writeText(original)
        file("cam.disabled").writeText("stopped from the app")

        assertEquals(1, requestRevival())

        assertFalse(file("broadcast-arguments").exists())
        assertEquals("stopped from the app", file("cam.disabled").readText())
        assertUntouched()
    }

    @Test
    fun aMissingScriptPreventsAndroidRecovery() {
        assertEquals(1, requestRevival())
        assertFalse(file("broadcast-arguments").exists())
        assertFalse(file("start_cam.sh").exists())
    }

    @Test
    fun anEarlierInstallationCannotRequestAndroidRecovery() {
        val old = original.replace(installation, "10123:900")
        file("start_cam.sh").writeText(old)

        assertEquals(1, requestRevival())

        assertFalse(file("broadcast-arguments").exists())
        assertEquals(old, file("start_cam.sh").readText())
    }

    @Test
    fun stopBeforeQueuedAndroidRecoveryIsCheckedAgain() {
        file("start_cam.sh").writeText(original)
        val before = recorderDesiredGuard(installation) + "\necho stopped > cam.disabled\n"

        assertEquals(1, requestRevival(before))

        assertFalse(file("broadcast-arguments").exists())
        assertEquals("stopped\n", file("cam.disabled").readText())
        assertUntouched()
    }

    @Test
    fun aRejectedAndroidRecoveryRequestIsReportedAsFailure() {
        file("start_cam.sh").writeText(original)

        assertEquals(2, requestRevival(exitCode = 20))

        assertTrue(file("broadcast-arguments").exists())
        assertUntouched()
    }

    private fun requestRevival(before: String = "", exitCode: Int = 0, user: Int = 0): Int {
        val fixture = """
            timeout() {
                [ "${'$'}1" = -s ] && [ "${'$'}2" = KILL ] && [ "${'$'}3" = 5 ] || exit 98
                shift 3
                "${'$'}@"
            }
            am() {
                printf '%s\n' "${'$'}@" > broadcast-arguments
                return $exitCode
            }
        """.trimIndent()
        return execute(fixture + "\n" + before + recorderRevivalLine(installation, user))
    }

    private fun running(arguments: List<String>? = null, pid: String? = null): Int {
        val owner = when {
            arguments != null -> "echo \"\$\$\" > cam_watchdog.pid; mkdir -p \"proc/\$\$\"; " +
                "printf '%s\\000' ${arguments.joinToString(" ") { quote(it) }} > \"proc/\$\$/cmdline\"\n"
            pid != null -> "printf '%s\\n' ${quote(pid)} > cam_watchdog.pid\n"
            else -> ""
        }
        return execute("pidof() { [ -f camera-alive ]; }\n" + owner + recorderRunningLine())
    }

    private fun assertUntouched() {
        assertEquals(original, file("start_cam.sh").readText())
        assertFalse(file("launches").exists())
        assertFalse(file("cam.log").exists())
    }

    private fun recover(before: String = "", watchdog: List<String>? = null): Int {
        assertTrue("A POSIX shell is required", shell.isFile)
        val apk = file("new/base.apk")
        apk.parentFile!!.mkdirs()
        apk.writeText("")
        file("owned-pids").writeText("")
        if (file("capture.ready").exists()) assertTrue(file("capture.ready").delete())
        file("capture.sh").writeText("""
            echo ${'$'}${'$'} >> owned-pids
            printf '%s\n' "${'$'}CLASSPATH" > classpath
            printf '%s\n' "${'$'}@" > arguments
            echo ready > capture.ready
            exec sleep 30
        """.trimIndent())
        val fixture = """
            echo ${'$'}${'$'} >> owned-pids
            mkdir -p "proc/${'$'}${'$'}"
            printf '%s\000' sh "${'$'}0" > "proc/${'$'}${'$'}/cmdline"
            echo launched >> launches
            cmd() {
                [ "$1" = package ] || exit 98
                shift
                pm "$@"
            }
            timeout() {
                [ "$1" = -s ] && [ "$2" = KILL ] && [ "$3" -gt 0 ] || exit 98
                shift 3
                "$@"
            }
            pm() {
                if [ "${'$'}1" = path ]; then printf 'package:%s/new/base.apk\n' "${'$'}PWD"
                else echo package:com.strike; fi
            }
            app_process() { exec sh capture.sh "${'$'}@"; }
            pidof() { [ -f camera-alive ]; }
            sleep() {
                command sleep "${'$'}1" &
                SLEEP_PID=${'$'}!
                echo "${'$'}SLEEP_PID" >> owned-pids
                wait "${'$'}SLEEP_PID"
            }
        """.trimIndent().lines()
        val lines = watchdog ?: watchdogScript("com.strike", apk.absolutePath.replace('\\', '/'), "/new/lib/arm64",
            "com.strike.daemon.CameraDaemon", installation)
        val tracked = lines.flatMap { line ->
            if (line.trim() == "MAINTENANCE_PID=\$!") listOf(line, "echo \"\$MAINTENANCE_PID\" >> owned-pids")
            else listOf(line)
        }
        val owner = ownerArguments?.let { args ->
            "sleep 30 & OWNER=\$!; echo \"\$OWNER\" >> owned-pids; " +
                "echo \"\$OWNER\" > cam_watchdog.pid; mkdir -p \"proc/\$OWNER\"; " +
                "printf '%s\\000' ${args.joinToString(" ") { quote(it) }} > \"proc/\$OWNER/cmdline\"\n"
        } ?: ""
        val commands = "pidof() { [ -f camera-alive ]; }\n" + owner + before +
            recoverWatchdogLine(installation, fixture + tracked)
        temporary.root.toPath().fileSystem.newWatchService().use { changes ->
            temporary.root.toPath().register(changes, ENTRY_CREATE)
            try {
                val code = execute(commands)
                if (code == 0 && watchdog == null) {
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                    while (!file("capture.ready").isFile) {
                        val remaining = deadline - System.nanoTime()
                        assertTrue("Camera fixture did not publish its arguments", remaining > 0)
                        val changed = changes.poll(remaining, TimeUnit.NANOSECONDS)
                            ?: throw AssertionError("Camera fixture did not publish its arguments")
                        changed.pollEvents()
                        assertTrue("Camera fixture directory disappeared", changed.reset())
                    }
                }
                return code
            } finally {
                execute("while read PID; do case \"\$PID\" in ''|*[!0-9]*) continue;; esac; " +
                    "kill -9 \"\$PID\" 2>/dev/null; done < owned-pids; exit 0")
            }
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
