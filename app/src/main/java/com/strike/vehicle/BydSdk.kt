package com.strike.vehicle

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import com.strike.core.Logs
import dalvik.system.DexClassLoader
import java.io.File

private const val TAG = "BydSdk"

/** The OEM app whose apk carries the bydauto classes on DiLink 5. */
private const val OEM_PACKAGE = "com.byd.data.collect"
private const val OEM_APK = "/system/app/BydDataCollect/BydDataCollect.apk"

/**
 * On DiLink 3 and 4 the bydauto classes sit on the boot classpath. On DiLink 5
 * they ship inside the OEM data collector apk instead, so nothing resolves them
 * until that apk is on a classloader. Verified in Overdrive's Dilink5SdkInjector.
 */
object BydSdk {

    private var loader: ClassLoader? = null
    private var searched = false

    fun deviceClass(name: String, context: Context): Class<*>? {
        try {
            return Class.forName(name)
        } catch (e: ClassNotFoundException) {
            Logs.d(TAG, "$name is not on the app classpath, trying the OEM apk")
        }
        val oem = oemLoader(context) ?: return null
        return try {
            Class.forName(name, true, oem)
        } catch (e: ClassNotFoundException) {
            Logs.w(TAG, "$name is not in the OEM apk either")
            null
        }
    }

    private fun oemLoader(context: Context): ClassLoader? {
        loader?.let { return it }
        if (searched) return null
        searched = true
        val apk = oemApk(context)
        if (apk == null) {
            Logs.w(TAG, "$OEM_PACKAGE is not installed, so the car's SDK cannot be loaded")
            return null
        }
        val opened = DexClassLoader(apk, context.codeCacheDir.path, null, context.classLoader)
        loader = opened
        Logs.d(TAG, "loaded the car's SDK from $apk")
        return opened
    }

    private fun oemApk(context: Context): String? {
        val installed = try {
            context.packageManager.getApplicationInfo(OEM_PACKAGE, 0).sourceDir
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
        if (installed != null && File(installed).isFile) return installed
        return if (File(OEM_APK).isFile) OEM_APK else null
    }
}

/**
 * The BYD permissions are the OEM's own, so an app that is not platform signed
 * is refused by the SDK's internal check even after declaring them. The check
 * asks the Context it was handed, which is this one.
 */
class BydPermissions(base: Context) : ContextWrapper(base) {

    override fun getApplicationContext(): Context = this

    override fun checkSelfPermission(permission: String): Int = PackageManager.PERMISSION_GRANTED

    override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
        PackageManager.PERMISSION_GRANTED

    override fun checkCallingPermission(permission: String): Int = PackageManager.PERMISSION_GRANTED

    override fun checkCallingOrSelfPermission(permission: String): Int =
        PackageManager.PERMISSION_GRANTED

    override fun enforcePermission(permission: String, pid: Int, uid: Int, message: String?) {}

    override fun enforceCallingPermission(permission: String, message: String?) {}

    override fun enforceCallingOrSelfPermission(permission: String, message: String?) {}
}
