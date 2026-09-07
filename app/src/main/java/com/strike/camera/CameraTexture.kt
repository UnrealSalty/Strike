package com.strike.camera

import android.hardware.HardwareBuffer
import com.strike.daemon.DaemonLog
import java.io.File

private const val TAG = "Texture"
private const val LIB = "strike"

/**
 * The camera hands out gralloc buffers and GL samples them as external
 * textures, but the EGL calls between the two are not in the Java SDK. This is
 * the only native code in Strike and it does nothing else.
 *
 * Both calls need a current EGL context on the calling thread.
 */
object CameraTexture {

    @JvmStatic
    external fun bind(buffer: HardwareBuffer, texture: Int): Boolean

    @JvmStatic
    external fun report(): String

    @Volatile
    var isLoaded = false
        private set

    private var tried = false

    /**
     * The daemon is app_process and gets the library directory on its command
     * line, because java.library.path has already failed on one firmware.
     */
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
