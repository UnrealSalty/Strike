package com.strike.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class RecorderRevivalProbeTest {
    @get:Rule val temporary = TemporaryFolder()
    private val installation = "10123:1000"
    private val script = "#!/system/bin/sh\nINSTALLATION='$installation'\n"
    private val shell: File
        get() = if (File("/bin/sh").isFile) File("/bin/sh") else
            File(System.getenv("ProgramFiles") ?: "C:/Program Files", "Git/usr/bin/sh.exe")

    @Test
    fun aMissingRecoveryJobIsDetectedWhileTheCameraIsRunning() {
        file("start_cam.sh").writeText(script)
        file("camera-alive").writeText("1234")

        assertEquals(0, probe(23, missing()))

        assertEquals(listOf("jobscheduler", "get-job-state", "--user", "0", "com.strike", "2"),
            file("query").readLines())
        assertEquals(script, file("start_cam.sh").readText())
        assertEquals("1234", file("camera-alive").readText())
    }

    @Test
    fun aRegisteredJobIsNotMissingWhenExecutionIsBlocked() {
        file("start_cam.sh").writeText(script)
        for (state in listOf("waiting", "pending", "active", "user-stopped", "no-component")) {
            assertEquals(state, 1, probe(0, state))
        }
    }

    @Test
    fun shellFailuresDoNotMasqueradeAsMissingJobs() {
        file("start_cam.sh").writeText(script)
        for (code in listOf(1, 20, 24, 124, 137, 255)) {
            assertEquals("Exit $code", 1, probe(code, missing()))
        }
        for (output in listOf("", "Security exception", "Can't find service: jobscheduler",
                "unknown(u0a123/jid2)", "Could not find job 1 in package com.strike / user 0")) {
            assertEquals(output, 1, probe(23, output))
        }
    }

    @Test
    fun theInstalledAppsUserIsQueried() {
        file("start_cam.sh").writeText(script)

        assertEquals(0, probe(23, missing(10), uid = 1_010_123))
        assertEquals(listOf("jobscheduler", "get-job-state", "--user", "10", "com.strike", "2"),
            file("query").readLines())
        assertEquals(1, probe(23, missing(0), uid = 1_010_123))
    }

    @Test
    fun manualStopPreventsTheQuery() {
        file("start_cam.sh").writeText(script)
        file("cam.disabled").writeText("stopped")

        assertEquals(1, probe(23, missing()))

        assertFalse(file("query").exists())
        assertEquals("stopped", file("cam.disabled").readText())
    }

    @Test
    fun noStartIntentOrAnOldInstallationPreventsTheQuery() {
        assertEquals(1, probe(23, missing()))
        file("start_cam.sh").writeText(script.replace(installation, "10123:900"))
        assertEquals(1, probe(23, missing()))
        assertFalse(file("query").exists())
    }

    @Test
    fun stopDuringTheQueryPreventsTheRecoveryBroadcast() {
        file("start_cam.sh").writeText(script)

        assertEquals(1, probe(23, missing(), beforeReply = "echo stopped > cam.disabled",
            afterProbe = recorderRevivalLine(installation, 0)))

        assertTrue(file("query").exists())
        assertFalse(file("broadcast").exists())
        assertEquals("stopped\n", file("cam.disabled").readText())
    }

    @Test
    fun reinstallDuringTheQueryPreventsTheRecoveryBroadcast() {
        file("start_cam.sh").writeText(script)

        assertEquals(1, probe(23, missing(),
            beforeReply = "printf '%s' ${quote(script.replace(installation, "10123:2000"))} > start_cam.sh",
            afterProbe = recorderRevivalLine(installation, 0)))

        assertTrue(file("query").exists())
        assertFalse(file("broadcast").exists())
    }

    private fun missing(user: Int = 0): String =
        "unknown(u${user}a123/jid2)\nCould not find job 2 in package com.strike / user $user"

    private fun probe(code: Int, reply: String, uid: Int = 10123, beforeReply: String = "",
                      afterProbe: String? = null): Int {
        assertTrue("A POSIX shell is required", shell.isFile)
        val command = recorderRecoveryMissingLine(installation, uid)
        val fixture = """
            timeout() {
                [ "${'$'}1" = -s ] && [ "${'$'}2" = KILL ] && [ "${'$'}3" = 5 ] || exit 98
                shift 3
                "${'$'}@"
            }
            cmd() {
                printf '%s\n' "${'$'}@" > query
                $beforeReply
                printf '%s\n' ${quote(reply)}
                return $code
            }
            am() { echo broadcast > broadcast; }
        """.trimIndent()
        val execute = if (afterProbe == null) command else "($command) && $afterProbe"
        file("probe.sh").writeText((fixture + "\n" + execute)
            .replace(STRIKE_DIR, temporary.root.absolutePath.replace('\\', '/')))
        val output = file("output")
        val builder = ProcessBuilder(shell.absolutePath, "probe.sh").directory(temporary.root)
            .redirectErrorStream(true).redirectOutput(output)
        builder.environment()["PATH"] = shell.parentFile!!.absolutePath + File.pathSeparator + builder.environment()["PATH"]
        val process = builder.start()
        try {
            assertTrue("Probe did not finish: ${output.readText()}", process.waitFor(10, TimeUnit.SECONDS))
            return process.exitValue()
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"
    private fun file(name: String): File = File(temporary.root, name)
}
