package com.strike.daemon

import android.content.AttributionSource
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process

// app_process needs a shell package context for BYD SDK calls.
object DaemonContext {

    fun get(): Context? {
        val system = system() ?: return null
        val opened = try {
            val pkg = if (Process.myUid() == 2000) "com.android.shell" else "com.strike"
            system.createPackageContext(
                pkg,
                Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY
            )
        } catch (e: PackageManager.NameNotFoundException) {
            system
        } catch (e: RuntimeException) {
            system
        }
        asShell(opened)
        return opened
    }

    // BYD checks ContextImpl's package fields as well as the wrapper.
    private fun asShell(context: Context) {
        if (Process.myUid() != 2000) return
        var target: Context? = context
        while (target is android.content.ContextWrapper) {
            target = target.baseContext
        }
        if (target == null || target.javaClass.name != "android.app.ContextImpl") return
        for (name in arrayOf("mPackageName", "mOpPackageName", "mBasePackageName")) {
            try {
                val field = target.javaClass.getDeclaredField(name)
                field.isAccessible = true
                field.set(target, "com.android.shell")
            } catch (e: ReflectiveOperationException) {
                // This firmware has no such field.
            }
        }
        // API 31+ caches an AttributionSource holding the old package. Rebuild it for the
        // shell package, or system services reject uid 2000 as "android". Nulling it instead
        // works on API 31 but breaks createPackageContext on API 34+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val field = target.javaClass.getDeclaredField("mAttributionSource")
                field.isAccessible = true
                field.set(target, AttributionSource.Builder(Process.myUid())
                    .setPackageName("com.android.shell").build())
            } catch (e: ReflectiveOperationException) {
                // This firmware has no such field.
            }
        }
    }

    private fun system(): Context? = try {
        val cls = Class.forName("android.app.ActivityThread")
        var thread = cls.getMethod("currentActivityThread").invoke(null)
        if (thread == null) {
            val ctor = cls.getDeclaredConstructor()
            ctor.isAccessible = true
            thread = ctor.newInstance()
            val field = cls.getDeclaredField("sCurrentActivityThread")
            field.isAccessible = true
            field.set(null, thread)
        }
        cls.getMethod("getSystemContext").invoke(thread) as? Context
    } catch (e: ReflectiveOperationException) {
        null
    } catch (e: RuntimeException) {
        null
    }
}
