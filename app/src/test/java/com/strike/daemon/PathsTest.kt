package com.strike.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class PathsTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val relocated = strikeDir(diLink5 = true)

    @Test
    fun settingsFollowStrikeToTheRelocatedFolder() {
        file("data/local/tmp/strike/config.json").writeText("{\"mode\":\"parked\"}")

        assertEquals(0, setUp())
        assertEquals("{\"mode\":\"parked\"}", file("$relocated/config.json").readText())
    }

    @Test
    fun settingsSavedInTheRelocatedFolderAreNeverOverwritten() {
        file("data/local/tmp/strike/config.json").writeText("{\"mode\":\"old\"}")
        file("$relocated/config.json").writeText("{\"mode\":\"current\"}")

        assertEquals(0, setUp())
        assertEquals("{\"mode\":\"current\"}", file("$relocated/config.json").readText())
    }

    @Test
    fun aFreshUnitWithNoOldSettingsStillPrepares() {
        assertEquals(0, setUp())
        assertFalse(file("$relocated/config.json").exists())
    }

    private fun setUp(): Int {
        val root = temporary.root.absolutePath.replace('\\', '/')
        file(relocated).mkdirs()
        val commands = strikeDirSetup(relocated).replace("/data/", "$root/data/")
        val shell = if (File("/bin/sh").isFile) File("/bin/sh") else
            File(System.getenv("ProgramFiles") ?: "C:/Program Files", "Git/usr/bin/sh.exe")
        assertTrue("A POSIX shell is required to exercise the folder setup", shell.isFile)
        val script = file("test.sh").also { it.writeText(commands) }
        val process = ProcessBuilder(shell.absolutePath, script.name)
            .directory(temporary.root).redirectErrorStream(true).start()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS))
        return process.exitValue()
    }

    private fun file(path: String): File =
        File(temporary.root, path.removePrefix("/")).also { it.parentFile!!.mkdirs() }
}
