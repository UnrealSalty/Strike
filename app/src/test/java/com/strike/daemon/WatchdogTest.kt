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
    fun repeatedCrashesStopUntilTheOwnerRetries() {
        val code = watchdog(List(5) { "137 1" })

        assertEquals(1, code)
        assertEquals("5", file("starts").readText().trim())
        assertEquals(listOf("3", "6", "9", "12"), file("sleeps").readLines())
        assertTrue(file("cam.disabled").readText().contains("exit 137"))
        assertFalse(file("cam_watchdog.pid").exists())
    }

    @Test
    fun repeatedShortCleanExitsAlsoStop() {
        assertEquals(1, watchdog(List(5) { "0 1" }))
        assertEquals("5", file("starts").readText().trim())
        assertTrue(file("cam.disabled").exists())
    }

    @Test
    fun aHealthyRunAllowsRecoveryFromLaterCrashes() {
        val exits = List(4) { "137 1" } + "137 300" + List(4) { "137 1" } + "3 0"
        assertEquals(0, watchdog(exits))

        assertEquals("10", file("starts").readText().trim())
        assertFalse(file("cam.disabled").exists())
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
        assertEquals(
            0,
            watchdog(emptyList(), setup = "echo \$\$ > cam.lock", afterSleep = ": > cam.disabled")
        )
        assertFalse(file("starts").exists())
        assertEquals(listOf("10"), file("sleeps").readLines())
    }

    @Test
    fun losingTheCameraLockRaceStopsTheExtraWatchdog() {
        assertEquals(0, watchdog(listOf("3 0")))
        assertEquals("1", file("starts").readText().trim())
        assertFalse(file("cam.disabled").exists())
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
        assertEquals(0, watchdog(listOf("3 0")))

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
        val lines = listOf("#!/system/bin/sh", "echo \"path\\name \$NAME `date`\"")
        assertEquals(0, execute(writeScriptLine(lines)))
        assertEquals(lines, file("start_cam.sh").readLines())
    }

    @Test
    fun stoppingTargetsTheWatchdogBeforeRemovingItsFiles() {
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

    private fun watchdog(exits: List<String>, setup: String = ":", afterSleep: String = ":"): Int {
        file("base.apk").writeText("")
        file("exits").writeText(exits.joinToString("\n"))
        file("uptime").writeText("0")
        val commands = """
            pm() { printf 'package:%s/base.apk\n' "${'$'}PWD"; }
            awk() { cat uptime; }
            app_process() {
              echo "${'$'}CLASSPATH" > classpath
              printf '%s\n' "${'$'}@" > arguments
              RUN=${'$'}(cat starts 2>/dev/null || echo 0)
              RUN=${'$'}((RUN + 1))
              echo ${'$'}RUN > starts
              set -- ${'$'}(sed -n "${'$'}{RUN}p" exits)
              UPTIME=${'$'}(cat uptime)
              echo ${'$'}((UPTIME + ${'$'}{2:-0})) > uptime
              exit ${'$'}{1:-3}
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
