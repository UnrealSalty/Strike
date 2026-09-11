package com.strike.online

import android.content.Context
import com.strike.server.HttpServer
import java.io.File
import java.security.MessageDigest

internal class ZrokMethod(
    private val context: Context,
    private val settings: OnlineSettings
) : RemoteMethod {
    private val home = File(context.filesDir, "zrok")
    private val identity = File(home, ".zrok/environment.json")
    private val enableTried = File(home, "enable-tried")
    private val reserved = File(home, "reserved")

    @Volatile private var reachable = false

    @Volatile override var problem: String? = null
        private set

    override val connecting = "Connecting to zrok"

    override fun prepare() {
        problem = null
        if (!home.isDirectory && !home.mkdirs()) {
            problem = "Cannot open zrok's storage"
            return
        }
        if (!identity.isFile && !enable()) return
        if (reserved.readIfPresent() != settings.name(ZROK)) reserve()
    }

    override fun start(): Process {
        reachable = false
        val builder = zrok("share", "reserved", settings.name(ZROK), "--headless", "--force-local",
            "--override-endpoint", target())
        builder.redirectErrorStream(true)
        return builder.start()
    }

    override fun ready(): Boolean = reachable

    override fun failure(line: String): String? {
        if (line.contains(".share.zrok.io")) {
            reachable = true
            return null
        }
        return when {
            line.contains("unauthorized", true) || line.contains("invalid token", true) ->
                "zrok rejected the account token. Update the setup"
            line.contains("not found", true) && line.contains("share", true) ->
                "That zrok share no longer exists. Remove the setup and save it again"
            line.contains("connection refused", true) -> "zrok could not reach Strike's dashboard"
            else -> null
        }
    }

    override fun address(): String? =
        settings.name(ZROK).ifEmpty { null }?.let { "https://$it.share.zrok.io/" }

    // The free plan allows five device enables in total, so one token gets one attempt.
    private fun enable(): Boolean {
        val token = settings.secret(ZROK)
        val fingerprint = fingerprint(token)
        if (enableTried.readIfPresent() == fingerprint) {
            problem = "zrok did not accept the account token. Update the setup"
            return false
        }
        val enabled = runCommand(zrok("enable", token, "--headless", "-d", "strike"), 90_000L)
        if (identity.isFile) return true
        // The command reached zrok, so spend this token's one attempt. A binary that never
        // launched throws out of runCommand above and stays retryable.
        enableTried.writeText(fingerprint)
        problem = zrokProblem(enabled.output, "zrok did not accept the account token")
        return false
    }

    private fun reserve() {
        val made = runCommand(zrok("reserve", "public", target(), "--unique-name",
            settings.name(ZROK), "--backend-mode", "proxy"), 90_000L)
        if (made.code != 0 && !made.output.contains("already exists", true)) {
            problem = zrokProblem(made.output, "zrok could not reserve that name")
            return
        }
        reserved.writeText(settings.name(ZROK))
    }

    private fun target() = "http://127.0.0.1:${HttpServer.PORT}"

    private fun zrok(vararg args: String): ProcessBuilder {
        val binary = File(context.applicationInfo.nativeLibraryDir, "libzrok.so").absolutePath
        val builder = ProcessBuilder(listOf(binary) + args)
        builder.environment()["HOME"] = home.absolutePath
        builder.directory(home)
        return builder
    }
}

private fun File.readIfPresent(): String? = if (isFile) readText().trim() else null

private fun fingerprint(token: String): String =
    MessageDigest.getInstance("SHA-256").digest(token.toByteArray())
        .joinToString("") { "%02x".format(it) }

internal fun zrokShareName(given: String): String {
    val name = given.trim().lowercase()
    require(name.length in 4..32 && name.all { it.isDigit() || it in 'a'..'z' }) {
        "Choose a name of 4 to 32 letters or numbers"
    }
    return name
}

internal fun zrokProblem(output: String, fallback: String): String = when {
    output.contains("unauthorized", true) || output.contains("invalid token", true) ->
        "zrok rejected the account token. Update the setup"
    output.contains("limit", true) -> "This zrok account has no device slots left"
    output.contains("already exists", true) || output.contains("conflict", true) ->
        "That zrok name is taken. Choose another"
    else -> commandProblem(output, fallback)
}
