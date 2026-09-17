package com.strike.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class WatchdogTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun repeatedCrashesKeepRetryingUntilTheCameraCanRunAgain() {
        val code = watchdog(List(6) { "137 1" } + "137 300" + "0 300 stop")

        assertEquals(0, code)
        assertEquals("8", file("starts").readText().trim())
        assertEquals(listOf("3", "6", "9", "12", "15", "18", "3"), file("sleeps").readLines())
        assertEquals("stopped by fixture", file("cam.disabled").readText().trim())
        assertFalse(file("cam_watchdog.pid").exists())
    }

    @Test
    fun repeatedShortCleanExitsKeepRetrying() {
        assertEquals(0, watchdog(List(6) { "0 1" } + "0 300 stop"))
        assertEquals("7", file("starts").readText().trim())
        assertEquals(listOf("3", "6", "9", "12", "15", "18"), file("sleeps").readLines())
        assertEquals("stopped by fixture", file("cam.disabled").readText().trim())
    }

    @Test
    fun aHealthyRunResetsTheBackoffAfterRepeatedCrashes() {
        val exits = List(6) { "137 1" } + "137 300" + List(6) { "137 1" } + "0 300 stop"
        assertEquals(0, watchdog(exits))

        assertEquals("14", file("starts").readText().trim())
        val retries = listOf("3", "6", "9", "12", "15", "18")
        assertEquals(retries + "3" + retries, file("sleeps").readLines())
        assertEquals("stopped by fixture", file("cam.disabled").readText().trim())
    }

    @Test
    fun prolongedFailuresNeverDelayRecoveryMoreThanOneMinute() {
        assertEquals(0, watchdog(List(25) { "137 1" } + "0 300 stop"))

        assertEquals("26", file("starts").readText().trim())
        val delays = (1..20).map { (it * 3).toString() } + List(5) { "60" }
        assertEquals(delays, file("sleeps").readLines())
        assertEquals("stopped by fixture", file("cam.disabled").readText().trim())
    }

    @Test
    fun prolongedCameraLockFailuresBackOffWithoutDisablingRecovery() {
        assertEquals(0, watchdog(List(25) { "3 1" } + "0 300 stop"))

        assertEquals("26", file("starts").readText().trim())
        val delays = (1..20).map { (it * 3).toString() } + List(5) { "60" }
        assertEquals(delays, file("sleeps").readLines())
        assertEquals("stopped by fixture", file("cam.disabled").readText().trim())
        assertFalse(file("cam_watchdog.pid").exists())
    }

    @Test
    fun manualStopStillWinsAfterTheBackoffReachesItsCap() {
        assertEquals(0, watchdog(List(20) { "137 1" },
            afterSleep = "if [ \"\$1\" -eq 60 ]; then : > cam.disabled; fi"))

        assertEquals("20", file("starts").readText().trim())
        assertEquals("60", file("sleeps").readLines().last())
        assertTrue(file("cam.disabled").exists())
        assertFalse(file("cam_watchdog.pid").exists())
    }

    @Test
    fun stopDuringBackoffPreventsAnotherLaunch() {
        assertEquals(0, watchdog(listOf("137 1"), afterSleep = ": > cam.disabled"))

        assertEquals("1", file("starts").readText().trim())
        assertFalse(file("cam_watchdog.pid").exists())
    }

    @Test
    fun aDisabledRecorderIsNeverLaunched() {
        assertEquals(0, watchdog(emptyList(), setup = ": > cam.disabled"))
        assertFalse(file("starts").exists())
    }

    @Test
    fun aRunningCameraOwnerIsLeftAlone() {
        cameraOwner(CAM_PROCESS)
        assertEquals(0, watchdog(emptyList(), afterSleep = ": > cam.disabled"))
        assertFalse(file("starts").exists())
        assertEquals(listOf("10"), file("sleeps").readLines())
        assertEquals("777", file("cam.lock").readText())
    }

    @Test
    fun anUnrelatedProcessReusingTheCameraPidCannotBlockRecovery() {
        cameraOwner("unrelated")
        assertEquals(0, watchdog(listOf("0 300 stop")))
        assertEquals("1", file("starts").readText().trim())
        assertFalse(file("sleeps").exists())
        assertEquals("777", file("cam.lock").readText())
    }

    @Test
    fun aSimilarProcessNameDoesNotOwnTheCamera() {
        cameraOwner("${CAM_PROCESS}_other")
        assertEquals(0, watchdog(listOf("0 300 stop")))
        assertEquals("1", file("starts").readText().trim())
        assertFalse(file("sleeps").exists())
    }

    @Test
    fun aRunningCameraIsAdoptedWithoutALockFile() {
        cameraOwner(CAM_PROCESS)
        assertTrue(file("cam.lock").delete())
        assertEquals(0, watchdog(emptyList(), afterSleep = ": > cam.disabled"))
        assertFalse(file("starts").exists())
        assertEquals(listOf("10"), file("sleeps").readLines())
    }

    @Test
    fun aRunningCameraIsAdoptedWithInvalidLockMetadata() {
        cameraOwner(CAM_PROCESS)
        file("cam.lock").writeText("incomplete")
        assertEquals(0, watchdog(emptyList(), afterSleep = ": > cam.disabled"))
        assertFalse(file("starts").exists())
        assertEquals(listOf("10"), file("sleeps").readLines())
        assertEquals("incomplete", file("cam.lock").readText())
    }

    private fun cameraOwner(name: String) {
        file("cam.lock").writeText("777")
        file("camera-name").writeText(name)
    }

    @Test
    fun aDuplicateLaunchStillGuardsTheCameraAndRestartsItAfterItExits() {
        val processes = """
            PID_CHECKS=0
            pidof() {
              PID_CHECKS=${'$'}((PID_CHECKS + 1))
              [ "${'$'}PID_CHECKS" -gt 1 ] && [ ! -f camera-gone ]
            }
        """.trimIndent()
        assertEquals(0, watchdog(listOf("3 0", "0 300 stop"), setup = processes,
            afterSleep = "if [ \"\$1\" -eq 10 ]; then : > camera-gone; fi"))

        assertEquals("2", file("starts").readText().trim())
        assertEquals(listOf("3", "10"), file("sleeps").readLines())
        assertEquals("stopped by fixture", file("cam.disabled").readText().trim())
        assertFalse(file("cam_watchdog.pid").exists())
    }

    @Test
    fun aReplacementWatchdogStopsTheOldOneBeforeAnotherLaunch() {
        assertEquals(0, watchdog(listOf("137 1"), afterSleep = "echo replacement > cam_watchdog.pid"))
        assertEquals("1", file("starts").readText().trim())
        assertEquals("replacement", file("cam_watchdog.pid").readText().trim())
    }

    @Test
    fun missingApkStopsWithAnExplanation() {
        assertEquals(1, watchdog(emptyList(), setup = "pm() { return 1; }; rm -f base.apk"))
        assertFalse(file("starts").exists())
        assertTrue(file("cam.disabled").readText().contains("apk is unavailable"))
    }

    @Test
    fun theLaunchPassesTheCameraJarAndExtractedLibraries() {
        assertEquals(0, watchdog(listOf("0 300 stop")))

        assertTrue(file("classpath").readText().startsWith("/system/framework/bmmcamera.jar:"))
        assertTrue(file("classpath").readText().trim().endsWith("/base.apk"))
        val args = file("arguments").readLines()
        assertTrue(args.contains("-Djava.library.path=/app/lib/arm64:/system/lib64:/vendor/lib64:/product/lib64:/odm/lib64"))
        assertTrue(args.contains("--nice-name=strike_cam"))
        assertTrue(args.contains("com.strike.daemon.CameraDaemon"))
        assertTrue(args.contains("/app/lib/arm64"))
    }

    @Test
    fun writingAScriptPreservesShellMetacharacters() {
        val lines = listOf(
            "#!/system/bin/sh", "echo \"path\\name \$NAME `date`\"",
            "", "-n", "-e", "printf '%s\\n' \"100%\""
        )
        assertEquals(0, execute(writeScriptLine(lines)))
        assertEquals(lines, file("start_cam.sh").readLines())
    }

    @Test
    fun stoppingTargetsTheWatchdogBeforeRemovingItsFiles() {
        file("cam.recovery").writeText("parked recording intent")
        file("cam.recovery.tmp").writeText("pending recording intent")
        file("start_cam.sh").writeText("old watchdog")
        file("cam_watchdog.pid").writeText("999")
        val fakeProcesses = """
            ps() { printf '%s\n' "${'$'}${'$'} sh current" "999 sh $CAM_SCRIPT_PATH" "111 $CAM_PROCESS" "222 unrelated"; }
            kill() {
              if [ -f cam.disabled ]; then printf '%s\n' "${'$'}*" >> killed; fi
            }
        """.trimIndent()

        assertEquals(0, execute(fakeProcesses + "\n" + stopWatchdogLine()))
        assertEquals(listOf("-9 999"), file("killed").readLines())
        assertTrue(file("cam.disabled").exists())
        assertFalse(file("start_cam.sh").exists())
        assertFalse(file("cam.recovery").exists())
        assertFalse(file("cam.recovery.tmp").exists())
        assertFalse(file("cam_watchdog.pid").exists())
    }

    @Test
    fun gracefulShutdownHasTimeToCloseTheClip() {
        val fakeProcesses = """
            ps() { if [ ! -f finished ]; then echo '111 $CAM_PROCESS'; fi; }
            pidof() { if [ ! -f finished ]; then echo 111; else return 1; fi; }
            sleep() { echo waiting >> sleeps; : > finished; }
            kill() { echo "${'$'}*" >> killed; }
        """.trimIndent()

        assertEquals(0, execute(fakeProcesses + "\n" + stopDaemonLine(graceful = true)))
        assertEquals(listOf("waiting"), file("sleeps").readLines())
        assertFalse(file("killed").exists())
    }

    @Test
    fun aStuckDaemonIsKilledOnlyAfterTheGracePeriod() {
        val fakeProcesses = """
            ps() { if [ ! -f finished ]; then echo '111 $CAM_PROCESS'; fi; }
            pidof() { if [ ! -f finished ]; then echo 111; else return 1; fi; }
            sleep() { echo waiting >> sleeps; }
            kill() { echo "${'$'}*" >> killed; : > finished; }
        """.trimIndent()

        assertEquals(0, execute(fakeProcesses + "\n" + stopDaemonLine(graceful = true)))
        assertEquals(20, file("sleeps").readLines().size)
        assertEquals(listOf("-9 111"), file("killed").readLines())
    }

    @Test
    fun anUpdateCannotKillAClipThatIsStillFinishing() {
        val fakeProcesses = """
            ps() { echo '111 $CAM_PROCESS'; }
            pidof() { echo 111; }
            sleep() { echo waiting >> sleeps; }
            kill() { echo "${'$'}*" >> killed; }
        """.trimIndent()

        assertEquals(1, execute(fakeProcesses + "\n" + stopDaemonLine(graceful = true, force = false)))
        assertEquals(20, file("sleeps").readLines().size)
        assertFalse(file("killed").exists())
    }

    @Test
    fun automaticCrashRecoveryKeepsTheParkedIntentForTheNextDaemon() {
        file("cam.recovery").writeText("parked recording intent")
        assertEquals(0, watchdog(listOf("137 300", "0 300 stop")))
        assertEquals("2", file("starts").readText().trim())
        assertEquals("parked recording intent", file("cam.recovery").readText())
    }

    private fun watchdog(exits: List<String>, setup: String = ":", afterSleep: String = ":"): Int {
        file("base.apk").writeText("")
        file("exits").writeText(exits.joinToString("\n"))
        file("uptime").writeText("0")
        val commands = """
            pm() { printf 'package:%s/base.apk\n' "${'$'}PWD"; }
            awk() { cat uptime; }
            pidof() { [ "${'$'}(cat camera-name 2>/dev/null)" = "${'$'}1" ] && echo 777; }
            app_process() {
              echo "${'$'}CLASSPATH" > classpath
              printf '%s\n' "${'$'}@" > arguments
              RUN=${'$'}(cat starts 2>/dev/null || echo 0)
              RUN=${'$'}((RUN + 1))
              echo ${'$'}RUN > starts
              set -- ${'$'}(sed -n "${'$'}{RUN}p" exits)
              if [ "${'$'}#" -lt 2 ]; then echo unexpected launch > cam.disabled; exit 99; fi
              UPTIME=${'$'}(cat uptime)
              echo ${'$'}((UPTIME + ${'$'}2)) > uptime
              if [ "${'$'}{3:-}" = stop ]; then echo stopped by fixture > cam.disabled; fi
              exit ${'$'}1
            }
            sleep() {
              if [ "${'$'}1" -eq 3600 ]; then exec sleep 30; fi
              echo "${'$'}1" >> sleeps
              $afterSleep
            }
            $setup
        """.trimIndent()
        val script = watchdogScript(
            "com.strike", file("base.apk").absolutePath.replace('\\', '/'),
            "/app/lib/arm64", "com.strike.daemon.CameraDaemon"
        ).joinToString("\n")
        return execute(commands + "\n" + script)
    }

    private fun execute(commands: String): Int {
        val shell = if (File("/bin/sh").isFile) File("/bin/sh") else
            File(System.getenv("ProgramFiles") ?: "C:/Program Files", "Git/usr/bin/sh.exe")
        assertTrue("A POSIX shell is required to exercise the watchdog", shell.isFile)
        val mapped = commands.replace(STRIKE_DIR, temporary.root.absolutePath.replace('\\', '/'))
            .replace("/proc/", temporary.root.absolutePath.replace('\\', '/') + "/proc/")
        val script = file("test.sh").also { it.writeText(mapped) }
        val output = file("output")
        val builder = ProcessBuilder(shell.absolutePath, script.name)
            .directory(temporary.root).redirectErrorStream(true).redirectOutput(output)
        builder.environment()["PATH"] = shell.parentFile!!.absolutePath + File.pathSeparator + builder.environment()["PATH"]
        val process = builder.start()
        try {
            assertTrue("Shell did not finish: ${output.readText()}", process.waitFor(20, TimeUnit.SECONDS))
            if (output.length() > 0) println(output.readText())
            return process.exitValue()
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun file(name: String): File = File(temporary.root, name)
}
