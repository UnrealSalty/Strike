package com.strike.core

import android.util.Base64
import com.strike.daemon.CONFIG_PATH
import com.strike.daemon.STRIKE_DIR
import com.strike.daemon.Shell
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

private const val REVALIDATE_MS = 1_000L
private const val TAG = "Config"

/**
 * The settings both processes read. SharedPreferences lives in the app's
 * private directory, which uid 2000 cannot open, so this is a world-readable
 * file where the daemon can reach it. /data/local/tmp is not writable by the
 * app uid either, so writes go out through the shell.
 */
object Config {

    private val lock = Any()
    private var held = JSONObject()
    private var readAtMs = 0L
    private var modifiedAtMs = -1L

    fun getString(key: String, fallback: String): String = values().optString(key, fallback)

    fun getInt(key: String, fallback: Int): Int = values().optInt(key, fallback)

    fun getBool(key: String, fallback: Boolean): Boolean = values().optBoolean(key, fallback)

    /** False when there is no shell, since only uid 2000 can write the file. */
    fun put(shell: Shell, key: String, value: Any): Boolean = synchronized(lock) {
        val merged = JSONObject(values().toString())
        merged.put(key, value)
        val encoded = Base64.encodeToString(merged.toString().toByteArray(), Base64.NO_WRAP)
        if (shell.run(writeLine(encoded)) != 0) return false
        readAtMs = 0L
        modifiedAtMs = -1L
        true
    }

    private fun values(): JSONObject = synchronized(lock) {
        val now = System.currentTimeMillis()
        if (now - readAtMs < REVALIDATE_MS) return held
        readAtMs = now
        val file = File(CONFIG_PATH)
        val modified = file.lastModified()
        if (modified == modifiedAtMs) return held
        modifiedAtMs = modified
        held = read(file)
        held
    }

    private fun read(file: File): JSONObject = try {
        if (file.isFile) JSONObject(file.readText()) else JSONObject()
    } catch (e: JSONException) {
        Logs.w(TAG, "the settings file is not readable json")
        JSONObject()
    } catch (e: IOException) {
        Logs.w(TAG, "cannot read the settings file")
        JSONObject()
    }
}

/**
 * Base64 so no setting value can be read as shell syntax, and rename so a
 * reader in the other process never sees half a file.
 */
internal fun writeLine(base64: String): String =
    "mkdir -p $STRIKE_DIR && chmod 755 $STRIKE_DIR && " +
        "echo $base64 | base64 -d > $CONFIG_PATH.tmp && chmod 644 $CONFIG_PATH.tmp && " +
        "mv -f $CONFIG_PATH.tmp $CONFIG_PATH"
