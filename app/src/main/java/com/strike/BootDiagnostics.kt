package com.strike

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.UUID

internal object BootDiagnostics {
    val source: String = UUID.randomUUID().toString()
    private var journal: BootJournal? = null
    private var bootId = "unavailable"
    private var failed = false

    @Synchronized
    fun start(context: Context) {
        if (journal != null) return
        journal = BootJournal(File(context.createDeviceProtectedStorageContext().filesDir, "boot.log"))
        bootId = try { File("/proc/sys/kernel/random/boot_id").readText().trim() }
            catch (e: IOException) { "unavailable" }
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        val debug = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        record("app started version=$version debug=$debug revivalEnabled=${RecorderRevival.isEnabled(context)} " +
            "sdk=${Build.VERSION.SDK_INT} android=${Build.VERSION.RELEASE} " +
            "model=${Build.MODEL} hardware=${Build.HARDWARE} build=${Build.DISPLAY}")
        environment(context, "startup")
    }

    fun environment(context: Context, stage: String) {
        val unlocked = context.getSystemService(UserManager::class.java).isUserUnlocked
        val services = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val bootStartListed = services.orEmpty().split(':').any { it == "com.strike/.BootStart" || it == "com.strike/com.strike.BootStart" }
        record("$stage unlocked=$unlocked bootStartListed=$bootStartListed " +
            "deviceAccess=${access(context.createDeviceProtectedStorageContext().filesDir)}" +
            if (unlocked) " userAccess=${access(context.filesDir)}" else "")
        val names = listOf("ro.boot.bootreason", "sys.boot.reason", "persist.sys.boot.reason",
            "sys.boot_completed", "ro.crypto.type", "init.svc.adbd", "service.adb.tcp.port")
        record("$stage " + names.joinToString(" ") { "$it=${property(it)}" })
        val rebootNames = listOf("sys.boot.reason.last", "persist.sys.rebootreason",
            "persist.sys.cloud_reboot_reson", "persist.sys.cloud_reboot_time")
        record("$stage " + rebootNames.joinToString(" ") { "$it=${property(it)}" })
    }

    @Synchronized
    fun record(message: String) {
        val current = journal ?: return
        try {
            current.append("${System.currentTimeMillis()} debug BootTrace " +
                "boot=$bootId elapsedMs=${SystemClock.elapsedRealtime()} pid=${Process.myPid()} $message")
            failed = false
        } catch (e: IOException) {
            unavailable(e)
        }
    }

    @Synchronized
    fun snapshot(afterVersion: Long): BootLogSnapshot? = try {
        journal?.snapshot(afterVersion)
    } catch (e: IOException) {
        unavailable(e)
        null
    }

    private fun unavailable(error: IOException) {
        if (!failed) Log.w("Strike/Boot", "Could not save or read boot diagnostics", error)
        failed = true
    }

    private fun access(directory: File): String =
        listOf("dashboard.migrated", "dashboard.identity", "adbkey", "adbkey.pub", "setup.pending")
            .joinToString(",") { "$it:${File(directory, it).isFile}" }

    private fun property(name: String): String = try {
        (Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
            .invoke(null, name) as? String).orEmpty().take(96).ifEmpty { "unset" }
    } catch (e: ReflectiveOperationException) { "unavailable" }
}
