package com.strike.daemon

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process

/**
 * app_process has no Application. Overdrive's deterrent and rail writes both
 * need a shell package context at uid 2000; getSystemContext alone is not it.
 */
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

    /**
     * BYD getInstance checks ContextImpl's package fields, not just the wrapper.
     * Overdrive writes these on uid 2000 or the rail votes silently miss.
     */
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
