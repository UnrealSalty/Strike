package com.strike.online

import android.content.Context
import com.strike.server.HttpServer
import java.io.File

private const val NODE_NAME = "strike"

internal class TailscaleMethod(
    private val context: Context,
    private val settings: OnlineSettings
) : RemoteMethod {
    private val home = File(context.filesDir, "tailscale")
    private val socket = File(home, "tailscaled.sock")

    @Volatile private var joined = false
    @Volatile private var tailnetAddress: String? = null

    @Volatile override var problem: String? = null
        private set

    override val connecting = "Connecting to Tailscale"

    override fun prepare() {
        problem = if (home.isDirectory || home.mkdirs()) null else "Cannot open Tailscale's storage"
    }

    override fun start(): Process {
        joined = false
        tailnetAddress = null
        socket.delete()
        val builder = ProcessBuilder(binary(), "--tun=userspace-networking",
            "--statedir=${home.absolutePath}", "--socket=${socket.absolutePath}")
        builder.directory(home)
        builder.redirectErrorStream(true)
        return builder.start()
    }

    // Userspace networking has no kernel interface, so serve is what carries the tailnet port
    // to the dashboard on loopback.
    override fun ready(): Boolean {
        if (!joined) {
            if (!socket.exists()) return false
            val port = HttpServer.PORT.toString()
            val up = cli(20_000L, "up", "--auth-key=${settings.secret(TAILSCALE)}",
                "--hostname=$NODE_NAME", "--accept-dns=false", "--accept-routes=false",
                "--timeout=15s")
            if (up.code != 0) {
                problem = tailscaleProblem(up.output)
                return false
            }
            val serve = cli(15_000L, "serve", "--bg", "--tcp", port, "tcp://127.0.0.1:$port")
            if (serve.code != 0) {
                problem = tailscaleProblem(serve.output)
                return false
            }
            joined = true
            problem = null
        }
        if (tailnetAddress == null) {
            val found = cli(10_000L, "ip", "--1")
            if (found.code != 0) return false
            tailnetAddress = found.output.lines().firstOrNull { it.isNotBlank() }?.trim()
        }
        return tailnetAddress != null
    }

    override fun failure(line: String): String? = when {
        line.contains("invalid key", true) || line.contains("unauthorized", true) ->
            "Tailscale rejected the auth key. Update the setup"
        line.contains("key has expired", true) ->
            "The Tailscale auth key has expired. Create a new one"
        line.contains("permission denied", true) -> "Android denied Tailscale access"
        else -> null
    }

    override fun address(): String? =
        tailnetAddress?.let { "http://$it:${HttpServer.PORT}/" }

    private fun binary() = File(context.applicationInfo.nativeLibraryDir, "libtailscale.so").absolutePath

    private fun cli(timeoutMs: Long, vararg args: String): CommandOutput {
        val builder = ProcessBuilder(listOf(binary(), "--socket=${socket.absolutePath}") + args)
        builder.environment()["TS_BE_CLI"] = "1"
        builder.directory(home)
        return runCommand(builder, timeoutMs)
    }
}

internal fun validTailscaleKey(key: String): Boolean =
    key.length in 16..256 && key.startsWith("tskey-") &&
        key.all { it.isLetterOrDigit() || it == '-' || it == '_' }

internal fun tailscaleProblem(output: String): String = when {
    output.contains("invalid key", true) || output.contains("unauthorized", true) ->
        "Tailscale rejected the auth key. Update the setup"
    output.contains("expired", true) -> "The Tailscale auth key has expired. Create a new one"
    output.isBlank() -> "Tailscale did not answer"
    else -> commandProblem(output, "Tailscale did not answer")
}
