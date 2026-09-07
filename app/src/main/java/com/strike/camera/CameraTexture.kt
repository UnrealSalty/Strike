package com.strike.camera

import android.hardware.HardwareBuffer
import com.strike.daemon.DaemonLog
import java.io.File

private const val TAG = "Texture"
private const val LIB = "strike"

// Both JNI calls require a current EGL context on the calling thread.
object CameraTexture {

    @JvmStatic
    external fun bind(buffer: HardwareBuffer, texture: Int): Boolean

    @JvmStatic
    external fun report(): String

    @Volatile
    var isLoaded = false
        private set

    private var tried = false

    // app_process receives the extracted library path explicitly.
    @Synchronized
    fun load(dir: String?): Boolean {
        if (tried) return isLoaded
        tried = true
        val byName = runCatching { System.loadLibrary(LIB) }
        if (byName.isSuccess) {
            isLoaded = true
            return true
        }
        val file = if (dir.isNullOrEmpty()) null else File(dir, "lib$LIB.so")
        if (file != null && file.isFile) {
            val byPath = runCatching { System.load(file.path) }
            if (byPath.isSuccess) {
                isLoaded = true
                DaemonLog.d(TAG, "loaded lib$LIB.so from $dir")
                return true
            }
        }
        DaemonLog.e(TAG, "lib$LIB.so did not load: ${byName.exceptionOrNull()?.message}")
        return false
    }
}
