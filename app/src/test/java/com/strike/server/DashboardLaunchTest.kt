package com.strike.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class DashboardLaunchTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun packageServiceFailureUsesTheInstalledApkAndRecovers() {
        assertEquals(0, watchdog(listOf("1 1", "0 1"), setup = ": > unavailable",
            afterSleep = "rm -f unavailable"))
        assertEquals(2, file("classpaths").readLines().size)
        assertEquals(listOf("3"), file("sleeps").readLines())
    }

    @Test
    fun moreThanFiveFailedStartsStillReachTheNextHealthyRun() {
        assertEquals(0, watchdog(List(6) { "1 1" } + "0 300"))
        assertEquals(7, file("classpaths").readLines().size)
        assertEquals(listOf("3", "6", "9", "12", "15", "18"), file("sleeps").readLines())
    }

    @Test
    fun prolongedFailuresNeverDelayRecoveryMoreThanOneMinute() {
        assertEquals(0, watchdog(List(25) { "1 1" } + "0 300"))
        assertEquals(26, file("classpaths").readLines().size)
        assertEquals((1..20).map { (it * 3).toString() } + List(5) { "60" },
            file("sleeps").readLines())
    }

    @Test
    fun aHealthyRunResetsCrashBackoff() {
        assertEquals(0, watchdog(listOf("1 1", "1 1", "1 300", "1 1", "0 1")))
        assertEquals(listOf("3", "6", "3", "6"), file("sleeps").readLines())
    }

    @Test
    fun anIntentionalExitDoesNotRestartTheDashboard() {
        assertEquals(0, watchdog(listOf("0 1")))
        assertEquals(1, file("classpaths").readLines().size)
        assertFalse(file("sleeps").exists())
    }

    @Test
    fun aDuplicateDashboardDoesNotLeaveAnotherWatchdog() {
        assertEquals(0, watchdog(listOf("3 1")))
        assertEquals(1, file("classpaths").readLines().size)
        assertFalse(file("sleeps").exists())
    }

    @Test
    fun reloadWaitsForPackageStateWithoutSpinning() {
        assertEquals(0, watchdog(listOf("42 1", "42 1", "0 1")))
        assertEquals(listOf("3", "3"), file("sleeps").readLines())
    }

    @Test
    fun aNewApkIsPickedUpAndKeptIfPackageServiceThenFails() {
        assertEquals(0, watchdog(listOf("1 1", "1 1", "0 1"), afterSleep = """
            if [ "$(cat starts)" -eq 1 ]; then
                mkdir -p updated
                : > updated/base.apk
                printf '%s/updated/base.apk\n' "${'$'}PWD" > installed
            else
                : > unavailable
            fi
        """.trimIndent()))
        val paths = file("classpaths").readLines()
        assertTrue(paths[0].endsWith("/initial/base.apk"))
        assertTrue(paths[1].endsWith("/updated/base.apk"))
        assertEquals(paths[1], paths[2])
    }

    @Test
    fun noApkDuringPackageServiceFailureWaitsInsteadOfExiting() {
        assertEquals(0, watchdog(listOf("0 1"),
            setup = "rm -f initial/base.apk; : > unavailable",
            afterSleep = ": > initial/base.apk; rm -f unavailable"))
        assertEquals(1, file("classpaths").readLines().size)
        assertEquals(listOf("3"), file("sleeps").readLines())
    }

    @Test
    fun anInstalledPackageWithTemporarilyMissingFilesStillRetries() {
        assertEquals(0, watchdog(listOf("0 1"), setup = "rm -f initial/base.apk",
            afterSleep = ": > initial/base.apk"))
        assertEquals(listOf("3"), file("sleeps").readLines())
    }

    @Test
    fun confirmedUninstallStopsTheWatchdog() {
        assertEquals(0, watchdog(emptyList(), setup = """
            rm -f initial/base.apk
            pm() { return 0; }
        """.trimIndent()))
        assertFalse(file("classpaths").exists())
        assertFalse(file("sleeps").exists())
    }

    @Test
    fun aSimilarPackageDoesNotKeepAnUninstalledDashboardAlive() {
        assertEquals(0, watchdog(emptyList(), setup = """
            rm -f initial/base.apk
            pm() { [ "$1" = list ] && echo package:com.strike.other; }
        """.trimIndent()))
        assertFalse(file("classpaths").exists())
    }

    @Test
    fun fallbackApkAndDashboardDirectoryCanContainShellCharacters() {
        val name = "initial quoted ' \$value `date`"
        assertEquals(0, watchdog(listOf("0 1"), setup = ": > unavailable", initialDirectory = name))
        assertTrue(file("classpaths").readText().trim().endsWith("/$name/base.apk"))
        assertTrue(file("arguments").readLines().last().endsWith("/panel ' \$value"))
    }

    private fun watchdog(
        exits: List<String>,
        setup: String = ":",
        afterSleep: String = ":",
        initialDirectory: String = "initial"
    ): Int {
        val apk = file("$initialDirectory/base.apk")
        assertTrue(apk.parentFile!!.mkdirs())
        apk.writeText("")
        val directory = file("panel ' \$value")
        assertTrue(directory.mkdir())
        file("installed").writeText(apk.absolutePath.replace('\\', '/'))
        file("exits").writeText(exits.joinToString("\n"))
        file("clock").writeText("0")
        val commands = """
            pm() {
                [ -f unavailable ] && return 20
                if [ "$1" = path ]; then
                    printf 'package:%s\n' "$(cat installed)"
                else
                    printf 'package:com.strike\n'
                fi
            }
            date() { cat clock; }
            app_process() {
                printf '%s\n' "${'$'}CLASSPATH" >> classpaths
                printf '%s\n' "$@" > arguments
                run=$(cat starts 2>/dev/null || echo 0)
                run=$((run + 1))
                echo "${'$'}run" > starts
                set -- $(sed -n "${'$'}{run}p" exits)
                [ "$#" -eq 2 ] || exit 99
                echo "$(( $(cat clock) + $2 ))" > clock
                return "$1"
            }
            sleep() {
                echo "$1" >> sleeps
                $afterSleep
            }
            $setup
        """.trimIndent()
        val script = commands + "\n" + dashboardScript(
            directory.absolutePath.replace('\\', '/'), apk.absolutePath.replace('\\', '/')
        )
        val shell = if (File("/bin/sh").isFile) File("/bin/sh") else
            File(System.getenv("ProgramFiles") ?: "C:/Program Files", "Git/usr/bin/sh.exe")
        assertTrue("A POSIX shell is required to exercise the dashboard watchdog", shell.isFile)
        file("test.sh").writeText(script)
        val output = file("output")
        val builder = ProcessBuilder(shell.absolutePath, "test.sh")
            .directory(temporary.root).redirectErrorStream(true).redirectOutput(output)
        builder.environment()["PATH"] = shell.parentFile!!.absolutePath + File.pathSeparator +
            builder.environment()["PATH"]
        val process = builder.start()
        try {
            assertTrue("Shell did not finish: ${output.readText()}", process.waitFor(20, TimeUnit.SECONDS))
            assertEquals("", output.readText())
            return process.exitValue()
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun file(name: String): File = File(temporary.root, name)
}
