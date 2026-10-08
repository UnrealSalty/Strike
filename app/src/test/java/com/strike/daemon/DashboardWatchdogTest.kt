package com.strike.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class DashboardWatchdogTest {
    @get:Rule val temporary = TemporaryFolder()
    private val installation = "10123:1000"

    @Test
    fun aLostAppAndDashboardCanRestartWithoutReplacingTheCamera() {
        prepare()
        file("camera-alive").writeText("777")

        assertEquals(0, recover())

        assertEquals(listOf("started"), file("dashboard-starts").readLines())
        assertEquals("777", file("camera-alive").readText())
        assertFalse(file("cam.disabled").exists())
        assertEquals(1, file("cam.log").readLines().size)
    }

    @Test
    fun aHealthyDashboardIsLeftAlone() {
        prepare()
        file("dashboard-alive").writeText("888")
        assertEquals(0, recover())
        assertFalse(file("dashboard-starts").exists())
        assertFalse(file("cam.log").exists())
    }

    @Test
    fun manualStopPreventsDashboardRecovery() {
        prepare()
        file("cam.disabled").writeText("stopped")
        assertEquals(1, recover())
        assertFalse(file("dashboard-starts").exists())
        assertEquals("stopped", file("cam.disabled").readText())
    }

    @Test
    fun stopDuringTheMissingDashboardCheckPreventsLaunch() {
        prepare()
        assertEquals(1, recover("pidof() { echo stopped > cam.disabled; return 1; }"))
        assertFalse(file("dashboard-starts").exists())
        assertFalse(file("cam.log").exists())
    }

    @Test
    fun anotherInstallationCannotUseTheSavedDashboardIdentity() {
        prepare()
        file("start_cam.sh").writeText("INSTALLATION='10123:999'\n")
        assertEquals(1, recover())
        assertFalse(file("dashboard-starts").exists())
    }

    @Test
    fun missingRecorderOrDashboardScriptsDoNotCreateNewState() {
        prepare()
        assertTrue(file("start_cam.sh").delete())
        assertEquals(1, recover())
        assertFalse(file("dashboard-starts").exists())
        prepare()
        assertTrue(file("dashboard-10123-1000/start.sh").delete())
        assertEquals(1, recover())
        assertFalse(file("dashboard-starts").exists())
    }

    @Test
    fun aReplacedWatchdogCannotLaunchTheDashboard() {
        prepare()
        assertEquals(1, recover("echo replacement > cam_watchdog.pid"))
        assertFalse(file("dashboard-starts").exists())
    }

    @Test
    fun failedDashboardStartsRetryWithoutRepeatingTheWarning() {
        prepare()
        assertEquals(0, execute(fixture() + "\n" + listOf(
            "recover_dashboard", "wait \"\$DASHBOARD_START_PID\"",
            "recover_dashboard", "wait \"\$DASHBOARD_START_PID\""
        ).joinToString("\n")))
        assertEquals(listOf("started", "started"), file("dashboard-starts").readLines())
        assertEquals(1, file("cam.log").readLines().size)
    }

    @Test
    fun theReusedCameraPathChecksOncePerMinuteAndKeepsItsOwner() {
        prepare()
        val fixture = """
            pidof() { [ "${'$'}1" = strike_cam ]; }
            app_process() { echo unexpected > camera-starts; exit 99; }
            sleep() {
                if [ -n "${'$'}{LOG_TICKS+x}" ]; then
                    [ "${'$'}1" = 60 ] || exit 98
                    while [ ! -f minute ]; do command sleep 0.01; done
                    rm minute
                else
                    [ "${'$'}1" = 10 ] || exit 98
                    : > minute
                    for attempt in 1 2 3 4 5 6 7 8 9 10; do
                        [ -f dashboard-starts ] && break
                        command sleep 0.1
                    done
                    echo stopped > cam.disabled
                fi
            }
        """.trimIndent()

        assertEquals(0, execute(fixture + "\n" + fullScript()))

        assertEquals(listOf("started"), file("dashboard-starts").readLines())
        assertFalse(file("camera-starts").exists())
        assertEquals(1, file("cam.log").readLines().size)
        assertMaintenanceStopped()
    }

    @Test
    fun repeatedCameraCrashesCannotKeepResettingTheDashboardRecoveryInterval() {
        prepare()
        file("base.apk").writeText("")
        val fixture = """
            pidof() { return 1; }
            timeout() { return 137; }
            app_process() { echo failed >> camera-starts; return 1; }
            RETRIES=0
            sleep() {
                if [ -n "${'$'}{LOG_TICKS+x}" ]; then
                    [ "${'$'}1" = 60 ] || exit 98
                    while [ ! -f minute ]; do command sleep 0.01; done
                    rm minute
                else
                    RETRIES=${'$'}((RETRIES + 1))
                    if [ "${'$'}RETRIES" -eq 3 ]; then
                        : > minute
                        for attempt in 1 2 3 4 5 6 7 8 9 10; do
                            [ -f dashboard-starts ] && break
                            command sleep 0.1
                        done
                        echo stopped > cam.disabled
                    fi
                fi
            }
        """.trimIndent()

        assertEquals(0, execute(fixture + "\n" + fullScript()))

        assertEquals(3, file("camera-starts").readLines().size)
        assertEquals(listOf("started"), file("dashboard-starts").readLines())
        assertEquals(1, file("cam.log").readLines().count { it.contains("dashboard missing") })
        assertMaintenanceStopped()
    }

    @Test
    fun stopBeforeTheFirstMaintenanceIntervalPreventsAnyDashboardLaunch() {
        prepare()
        val fixture = """
            pidof() { [ "${'$'}1" = strike_cam ]; }
            sleep() {
                if [ -n "${'$'}{LOG_TICKS+x}" ]; then exec sleep 30
                else echo stopped > cam.disabled; fi
            }
        """.trimIndent()

        assertEquals(0, execute(fixture + "\n" + fullScript()))

        assertFalse(file("dashboard-starts").exists())
        assertFalse(file("cam.log").exists())
        assertMaintenanceStopped()
    }

    private fun fullScript(): String = watchdogScript(
        "com.strike", file("base.apk").absolutePath.replace('\\', '/'), "/unused/lib",
        "com.strike.daemon.CameraDaemon", installation
    ).joinToString("\n").replace(
        "MAINTENANCE_PID=\$!", "MAINTENANCE_PID=\$!\necho \"\$MAINTENANCE_PID\" > maintenance.pid"
    )

    private fun assertMaintenanceStopped() {
        assertFalse(file("cam_watchdog.pid").exists())
        assertEquals(0, execute("! kill -0 \"\$(cat maintenance.pid)\" 2>/dev/null"))
    }

    private fun prepare() {
        file("start_cam.sh").writeText("INSTALLATION='$installation'\n")
        val launcher = file("dashboard-10123-1000/start.sh")
        launcher.parentFile!!.mkdirs()
        launcher.writeText("echo started >> dashboard-starts\n")
    }

    private fun fixture(): String = "echo \$\$ > cam_watchdog.pid\n" +
        "pidof() { [ \"\$1\" = strike_dashboard ] && [ -f dashboard-alive ]; }\n" +
        dashboardRecoveryLines(installation).joinToString("\n")

    private fun recover(before: String = ""): Int = execute(fixture() + "\n" + before + "\n" + """
        STATUS=0
        recover_dashboard || STATUS=${'$'}?
        [ -z "${'$'}DASHBOARD_START_PID" ] || wait "${'$'}DASHBOARD_START_PID"
        exit ${'$'}STATUS
    """.trimIndent())

    private fun execute(commands: String): Int {
        val shell = if (File("/bin/sh").isFile) File("/bin/sh") else
            File(System.getenv("ProgramFiles") ?: "C:/Program Files", "Git/usr/bin/sh.exe")
        assertTrue("A POSIX shell is required", shell.isFile)
        val mapped = commands.replace(STRIKE_DIR, temporary.root.absolutePath.replace('\\', '/'))
        file("test.sh").writeText(mapped)
        val output = file("output")
        val builder = ProcessBuilder(shell.absolutePath, "test.sh").directory(temporary.root)
            .redirectErrorStream(true).redirectOutput(output)
        builder.environment()["PATH"] = shell.parentFile!!.absolutePath + File.pathSeparator + builder.environment()["PATH"]
        val process = builder.start()
        try {
            assertTrue("Shell did not finish: ${output.readText()}", process.waitFor(60, TimeUnit.SECONDS))
            return process.exitValue()
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun file(name: String) = File(temporary.root, name)
}
