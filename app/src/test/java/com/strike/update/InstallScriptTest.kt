package com.strike.update

import com.strike.daemon.STRIKE_DIR
import com.strike.core.Pin
import com.strike.core.PinCheck
import com.strike.online.BrowserAccess
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class InstallScriptTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun bothInstallOutcomesLeaveAReceiptAndReopenStrike() {
        for (code in listOf(0, 1)) {
            val root = folder.newFolder().absolutePath.replace('\\', '/')
            val id = "a".repeat(32)
            File(root, "update-$id").mkdir()
            val pinFile = File(root, "pin.json")
            Pin(pinFile, File(root, "reset")).set("2580")
            val accessFile = File(root, "browser-access.json")
            val access = BrowserAccess(accessFile)
            val accessCode = access.code()!!
            val session = access.login(accessCode).token
            val fakeAndroid = """
                pm() { printf '%s\n' "${'$'}*" > "$root/pm-args"; return $code; }
                am() {
                    test -f "$root/update-result" || exit 2
                    printf '%s\n' "${'$'}*" > "$root/am-args"
                }
            """.trimIndent()
            val script = File(root, "test.sh")
            script.writeText(fakeAndroid + "\n" + installScript(id).replace(STRIKE_DIR, root))
            val shell = if (File("/bin/sh").isFile) File("/bin/sh") else
                File(System.getenv("ProgramFiles") ?: "C:/Program Files", "Git/usr/bin/sh.exe")
            val output = File(root, "output")
            val builder = ProcessBuilder(shell.absolutePath, script.name)
                .directory(File(root)).redirectErrorStream(true).redirectOutput(output)
            builder.environment()["PATH"] = shell.parentFile!!.absolutePath + File.pathSeparator + builder.environment()["PATH"]
            val process = builder.start()
            try {
                assertTrue(process.waitFor(10, TimeUnit.SECONDS))
                assertEquals(output.readText(), 0, process.exitValue())
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
            assertEquals("$id:$code", File(root, "update-result").readText().trim())
            assertEquals("install -r $root/update-$id/Strike.apk", File(root, "pm-args").readText().trim())
            assertEquals("start -n com.strike/.MainActivity", File(root, "am-args").readText().trim())
            assertEquals(PinCheck.OK, Pin(pinFile, File(root, "reset")).check("2580"))
            val reloaded = BrowserAccess(accessFile)
            assertEquals(accessCode, reloaded.code())
            assertTrue(reloaded.allows(session))
        }
    }
}
