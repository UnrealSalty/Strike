package com.strike.update

import android.content.Context
import com.strike.core.Logs
import com.strike.daemon.Shell
import com.strike.online.hasInternet
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val DAY_MS = 86_400_000L
private const val MANUAL_WAIT_MS = 15_000L

class Updates internal constructor(
    private val current: String,
    private val cache: File,
    private val apk: File,
    private val connected: () -> Boolean,
    private val fetch: (String, Release?) -> ReleaseReply,
    private val download: (Release, File, (Long) -> Unit) -> Unit,
    private val verify: (File, Release) -> Unit,
    private val install: (File, Release) -> Unit,
    private val installPending: () -> Boolean,
    private val installOutcome: () -> Boolean?,
    private val worker: Executor = Executor { Thread(it, "updates").start() },
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val pause: (Long) -> Unit = Thread::sleep,
    private val isCurrentApk: () -> Boolean = { true }
) {
    constructor(context: Context, shell: Shell, pauseRecorder: ((Boolean) -> Unit) -> Unit,
                resumeRecorder: () -> Unit) : this(context, UpdateInstall(context, shell, pauseRecorder, resumeRecorder))

    private constructor(context: Context, installer: UpdateInstall) : this(
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "",
        File(context.filesDir, "updates.json"), File(context.cacheDir, "update.apk"),
        { hasInternet(context) }, ::fetchRelease, ::downloadRelease,
        { apk, release -> verifyUpdateApk(context, apk, release); Unit },
        installer::start, { installer.pending }, installer::outcome, isCurrentApk = { installer.isCurrentApk }
    )

    private var release: Release? = null
    private var etag = ""
    private var attemptedAtMs = 0L
    private var checkedAtMs = 0L
    private var phase = "idle"
    private var working = false
    private var idle = CountDownLatch(0)
    private var ready = false
    private var received = 0L
    private var message = ""
    private var failed = false

    init {
        try {
            if (cache.isFile) {
                val saved = JSONObject(cache.readText())
                attemptedAtMs = saved.optLong("attemptedAtMs")
                checkedAtMs = saved.optLong("checkedAtMs")
                etag = saved.optString("etag")
                release = saved.optJSONObject("release")?.let(::savedRelease)
            }
        } catch (e: Exception) {
            Logs.w("Updates", "Could not read the last update check")
        }
        if (installPending()) phase = "installing" else discardApk()
    }

    @Synchronized
    fun check(manual: Boolean = false): Boolean {
        if (manual && !working && installPending()) {
            work("installing", ::awaitInstall)
            return true
        }
        if (working || isInstalling()) return false
        val now = nowMs()
        val elapsed = now - attemptedAtMs
        val wait = if (manual) MANUAL_WAIT_MS else DAY_MS
        if (attemptedAtMs > 0 && elapsed >= 0 && elapsed < wait) return false
        if (!connected()) {
            if (manual) {
                message = "No internet connection"
                failed = true
            }
            return false
        }
        attemptedAtMs = now
        work("checking") {
            save()
            val reply = fetch(etag, release)
            synchronized(this) {
                if (reply.release != release) {
                    ready = false
                    received = 0L
                }
                release = reply.release
                etag = reply.etag
                checkedAtMs = nowMs()
                message = if (release == null) "No published release with Strike.apk" else ""
                save()
            }
        }
        return true
    }

    @Synchronized
    fun download(): Boolean {
        if (working || isInstalling() || ready) return false
        val offered = release?.takeIf { compareVersions(it.version, current) > 0 } ?: return false
        ready = false
        received = 0L
        work("downloading") {
            if (apk.parentFile!!.usableSpace < offered.bytes + 32L * 1024 * 1024) {
                throw IOException("Not enough internal storage for the update")
            }
            try {
                download(offered, apk) { synchronized(this) { received = it } }
                verify(apk, offered)
                synchronized(this) { ready = true }
            } finally {
                if (!ready) discardApk()
            }
        }
        return true
    }

    @Synchronized
    fun install(): Boolean {
        if (working || isInstalling() || !ready) return false
        val offered = release ?: return false
        work("installing") {
            try {
                install(apk, offered)
            } catch (e: IllegalArgumentException) {
                synchronized(this) { ready = false }
                discardApk()
                throw e
            }
            awaitInstall()
        }
        return true
    }

    @Synchronized
    fun resume() {
        if (working) return
        if (installPending()) work("installing", ::awaitInstall) else check()
    }

    @Synchronized
    fun isInstalling(): Boolean = phase == "installing" || installPending()

    fun awaitIdle(timeoutMs: Long): Boolean = synchronized(this) { idle }.await(timeoutMs, TimeUnit.MILLISECONDS)

    @Synchronized
    fun status(full: Boolean = true): JSONObject {
        val offered = release
        val newer = offered != null && compareVersions(offered.version, current) > 0
        val payload = JSONObject().put("current", current).put("latest", offered?.version ?: JSONObject.NULL)
            .put("phase", phase).put("available", newer).put("ready", ready)
            .put("checkedAtMs", if (checkedAtMs > 0) checkedAtMs else JSONObject.NULL)
            .put("receivedBytes", received).put("totalBytes", offered?.bytes ?: 0)
            .put("message", message).put("failed", failed).put("busy", working)
            .put("checkAfterMs", if (nowMs() < attemptedAtMs) 0 else
                maxOf(0, MANUAL_WAIT_MS - (nowMs() - attemptedAtMs)))
        if (full) payload.put("notes", offered?.notes ?: "")
        return payload
    }

    private fun work(next: String, action: () -> Unit) {
        val finished = CountDownLatch(1)
        idle = finished
        working = true
        phase = next
        message = ""
        failed = false
        worker.execute {
            try {
                action()
            } catch (e: Exception) {
                synchronized(this) {
                    failed = true
                    message = when (e) {
                        is JSONException -> "GitHub returned unreadable release information"
                        is java.net.SocketTimeoutException -> "The update connection timed out"
                        is java.net.UnknownHostException -> "Cannot reach GitHub"
                        is javax.net.ssl.SSLException -> "Cannot establish a secure connection to GitHub"
                        is java.io.FileNotFoundException, is java.nio.file.FileSystemException ->
                            "Cannot access the downloaded update. Download it again"
                        is IOException, is IllegalArgumentException, is IllegalStateException ->
                            e.message ?: "The update could not finish"
                        else -> "The update could not finish"
                    }
                    Logs.w("Updates", message)
                }
            } finally {
                synchronized(this) {
                    working = false
                    phase = if (installPending()) "installing" else "idle"
                }
                finished.countDown()
            }
        }
    }

    private fun awaitInstall() {
        val deadline = nowMs() + 180_000
        while (nowMs() < deadline) {
            if (!isCurrentApk()) return
            val success = installOutcome()
            if (success != null) {
                synchronized(this) {
                    ready = false
                    failed = !success
                    message = if (success) "Update installed" else "Android could not install the update"
                }
                discardApk()
                return
            }
            pause(1000)
        }
        synchronized(this) { message = "Installation is still pending. Retry to check its progress" }
    }

    private fun save() {
        val saved = JSONObject().put("attemptedAtMs", attemptedAtMs).put("checkedAtMs", checkedAtMs)
            .put("etag", etag).put("release", release?.json() ?: JSONObject.NULL)
        val pending = File(cache.path + ".tmp")
        pending.writeText(saved.toString())
        Files.move(pending.toPath(), cache.toPath(), StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE)
    }

    private fun discardApk() {
        if (apk.exists() && !apk.delete()) Logs.w("Updates", "Could not remove the cached APK")
    }
}
