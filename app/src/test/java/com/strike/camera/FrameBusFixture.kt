package com.strike.camera

import android.opengl.EGL14

@Synchronized
internal fun frameBusForTest(): FrameBus {
    // AGP removes the EGL sentinel initializers from its mock Android jar.
    for (name in listOf("EGL_NO_DISPLAY", "EGL_NO_CONTEXT", "EGL_NO_SURFACE")) {
        val field = EGL14::class.java.getField(name)
        if (field.get(null) == null) {
            val constructor = field.type.getDeclaredConstructor().also { it.isAccessible = true }
            field.set(null, constructor.newInstance())
        }
    }
    return FrameBus(64, 64)
}
