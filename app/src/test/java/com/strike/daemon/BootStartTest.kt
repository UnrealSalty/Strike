package com.strike.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class BootStartTest {
    @get:Rule val temporary = TemporaryFolder()
    private val shell: File
        get() = if (File("/bin/sh").isFile) File("/bin/sh") else
            File(System.getenv("ProgramFiles") ?: "C:/Program Files", "Git/usr/bin/sh.exe")

    @Test
    fun anEmptyListBecomesStrikeAlone() {
        for (empty in listOf(null, "", "null")) {
            assertEquals(BOOT_START_RESTORED, enable(empty))
            assertEquals("com.strike/.BootStart", services())
            assertEquals("1", file("accessibility_enabled").readText())
        }
    }

    @Test
    fun otherServicesAreKept() {
        assertEquals(BOOT_START_RESTORED, enable("com.other/.Reader"))
        assertEquals("com.other/.Reader:com.strike/.BootStart", services())
    }

    @Test
    fun anAlreadyEnabledServiceIsNotAddedTwice() {
        for (present in listOf("com.strike/.BootStart", "com.other/.Reader:com.strike/com.strike.BootStart")) {
            assertEquals(0, enable(present))
            assertEquals(present, services())
            assertEquals("1", file("accessibility_enabled").readText())
        }
    }

    @Test
    fun aSimilarlyNamedServiceDoesNotCount() {
        assertEquals(BOOT_START_RESTORED, enable("com.strike/.BootStartOld"))
        assertEquals("com.strike/.BootStartOld:com.strike/.BootStart", services())
    }

    @Test
    fun aWriteThatDoesNotStickFails() {
        assertEquals(1, enable("com.other/.Reader", writable = false))
        assertEquals(1, enable(null, readable = false))
    }

    private fun services(): String = file("enabled_accessibility_services").readText()

    private fun enable(current: String?, writable: Boolean = true, readable: Boolean = true): Int {
        assertTrue("A POSIX shell is required", shell.isFile)
        file("enabled_accessibility_services").delete()
        file("accessibility_enabled").delete()
        if (current != null) file("enabled_accessibility_services").writeText(current)
        val fixture = """
            timeout() {
                [ "${'$'}1" = -s ] && [ "${'$'}2" = KILL ] && [ "${'$'}3" = 5 ] || exit 98
                shift 3
                "${'$'}@"
            }
            settings() {
                [ "${'$'}2" = secure ] || exit 97
                case "${'$'}1" in
                    get) ${if (readable) "" else "return 1;"}
                        if [ -f "${'$'}3" ]; then IFS= read -r line < "${'$'}3"; echo "${'$'}line"; else echo null; fi;;
                    put) ${if (writable) "printf '%s' \"${'$'}4\" > \"${'$'}3\"" else ":"};;
                    *) exit 96;;
                esac
            }
        """.trimIndent()
        file("enable.sh").writeText(fixture + "\n" + bootStartLine())
        val output = file("output")
        val process = ProcessBuilder(shell.absolutePath, "enable.sh").directory(temporary.root)
            .redirectErrorStream(true).redirectOutput(output).start()
        try {
            assertTrue("Enable did not finish: ${output.readText()}", process.waitFor(10, TimeUnit.SECONDS))
            return process.exitValue()
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun file(name: String): File = File(temporary.root, name)
}
