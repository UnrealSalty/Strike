package com.strike.update

import android.content.Context
import com.strike.core.Logs
import com.strike.daemon.STRIKE_DIR
import com.strike.daemon.Shell
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

private const val RECEIPT = "$STRIKE_DIR/update-result"

internal class UpdateInstall(
    private val context: Context,
    private val shell: Shell,
    private val pauseRecorder: ((Boolean) -> Unit) -> Unit,
    private val resumeRecorder: () -> Unit
) {
    private val journal = File(context.filesDir, "update-install.json")
    val pending: Boolean get() = journal.isFile

    fun start(apk: File, release: Release) {
        check(shell.isAuthorised()) { "Connect shell access in Daemons before installing" }
        val code = verifyUpdateApk(context, apk, release)
        val id = UUID.randomUUID().toString().replace("-", "")
        val directory = "$STRIKE_DIR/update-$id"
        val script = File(context.cacheDir, "update-install.sh")
        try {
            check(shell.check("mkdir -p $directory && chmod 700 $directory")) { "Cannot prepare the installer" }
            check(shell.push(apk, "$directory/Strike.apk")) { "Cannot transfer the APK to the installer" }
            check(shell.read("sha256sum $directory/Strike.apk")?.substringBefore(' ') == release.sha256) {
                "The transferred APK is damaged"
            }
            script.writeText(installScript(id))
            check(shell.push(script, "$directory/install.sh")) { "Cannot prepare the install command" }
            pauseRecorder { resume ->
                val plan = JSONObject().put("id", id).put("versionCode", code).put("resume", resume)
                    .put("startedAtMs", System.currentTimeMillis())
                val pending = File(journal.path + ".tmp")
                pending.outputStream().use { it.write(plan.toString().toByteArray()); it.fd.sync() }
                Files.move(pending.toPath(), journal.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE)
            }
        } catch (e: Exception) {
            if (journal.isFile) finish(JSONObject(journal.readText())) else clean(id)
            throw e
        } finally {
            if (script.exists() && !script.delete()) Logs.w("Updates", "Could not remove the cached install command")
        }
        // A lost ADB reply cannot tell us whether the detached installer started.
        shell.check("nohup sh $directory/install.sh > /dev/null 2>&1 < /dev/null &")
    }

    fun outcome(): Boolean? {
        if (!journal.isFile) return false
        if (!shell.isAuthorised()) return null
        val plan = JSONObject(journal.readText())
        val id = plan.getString("id")
        require(id.matches(Regex("[0-9a-f]{32}"))) { "The install record is unreadable" }
        val receipt = File(RECEIPT).let { if (it.isFile) it.readText().trim() else "" }
        if (receipt.startsWith("$id:")) {
            val installed = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
            val success = receipt == "$id:0" && installed >= plan.getLong("versionCode")
            finish(plan)
            return success
        }
        if (System.currentTimeMillis() - plan.getLong("startedAtMs") < 10_000L) return null
        val running = shell.read("if kill -0 \$(cat $STRIKE_DIR/update-$id/pid 2>/dev/null) 2>/dev/null; " +
            "then echo running; else echo stopped; fi") ?: return null
        if (running.trim() == "running") return null
        finish(plan)
        return false
    }

    private fun finish(plan: JSONObject) {
        if (plan.getBoolean("resume")) resumeRecorder()
        clean(plan.getString("id"))
        Files.deleteIfExists(journal.toPath())
    }

    private fun clean(id: String) {
        require(id.matches(Regex("[0-9a-f]{32}")))
        val directory = "$STRIKE_DIR/update-$id"
        if (!shell.check("rm -f $directory/Strike.apk $directory/install.sh $directory/pid; rmdir $directory")) {
            Logs.w("Updates", "Could not remove the installer staging files")
        }
    }
}

// The shell survives package replacement and restarts the app on either install outcome.
internal fun installScript(id: String): String {
    require(id.matches(Regex("[0-9a-f]{32}")))
    val directory = "$STRIKE_DIR/update-$id"
    return """
        #!/system/bin/sh
        umask 077
        echo ${'$'}${'$'} > $directory/pid
        pm install -r $directory/Strike.apk > /dev/null 2>&1
        CODE=${'$'}?
        printf '$id:%s\n' "${'$'}CODE" > $RECEIPT.tmp
        chmod 644 $RECEIPT.tmp
        mv -f $RECEIPT.tmp $RECEIPT
        am start -n com.strike/.MainActivity > /dev/null 2>&1
    """.trimIndent() + "\n"
}
