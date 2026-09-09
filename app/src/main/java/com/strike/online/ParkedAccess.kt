package com.strike.online

import android.content.Context
import android.os.Looper
import android.os.PowerManager
import com.strike.daemon.DaemonContext
import com.strike.vehicle.BydPermissions
import kotlin.system.exitProcess

// BYD's background exemption must be set for the app UID by shell UID 2000.
object ParkedAccess {
    @JvmStatic
    fun main(args: Array<String>) {
        var exitCode = 1
        try {
            Looper.prepareMainLooper()
            val context = DaemonContext.get() ?: return
            val uid = context.packageManager.getApplicationInfo("com.strike", 0).uid
            val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!power.isIgnoringBatteryOptimizations("com.strike")) {
                ProcessBuilder("dumpsys", "deviceidle", "whitelist", "+com.strike")
                    .redirectErrorStream(true).start().let {
                        it.inputStream.bufferedReader().use { output -> output.readText() }
                        it.waitFor()
                    }
            }
            val android = if (power.isIgnoringBatteryOptimizations("com.strike")) "allowed" else "rejected"
            val byd = exemptBydBackground(uid) { BydPermissions(context).getSystemService(it) }
            println("parked-access android=$android byd=$byd")
            exitCode = 0
        } catch (e: Exception) {
            System.err.println("Parked access setup failed: ${e.javaClass.simpleName}")
        } finally {
            exitProcess(exitCode)
        }
    }
}

internal fun exemptBydBackground(uid: Int, service: (String) -> Any?): String {
    var rejected = false
    for ((name, method) in listOf("byd_datacached" to "setAppStartupData", "bg_datacache" to "setAppOpsData")) {
        try {
            val manager = service(name) ?: continue
            val reply = manager.javaClass.getMethod(method, String::class.java, Int::class.javaPrimitiveType)
                .invoke(manager, uid.toString(), 0)
            if (reply == null || reply == true || (reply is Number && reply.toInt() == 0)) return "allowed"
            rejected = true
        } catch (e: ReflectiveOperationException) {
            rejected = true
        } catch (e: RuntimeException) {
            rejected = true
        }
    }
    return if (rejected) "rejected" else "unavailable"
}
