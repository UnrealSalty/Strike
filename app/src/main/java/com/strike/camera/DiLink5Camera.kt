package com.strike.camera

import android.os.Process
import android.view.Surface
import com.strike.daemon.DaemonContext
import com.strike.daemon.DaemonLog
import java.io.File

internal object DiLink5Camera {
    private val avm = DiLink5Avm()
    private var child = 0

    @Synchronized
    fun open(surface: Surface, frameRateFps: Int, nativeDir: String?): Boolean {
        close()
        if (!CameraTexture.isLoaded || nativeDir.isNullOrEmpty()) return failed("The camera capture library is unavailable")
        val executable = File(nativeDir, "libstrike_capture.so")
        if (!executable.isFile || !executable.canExecute()) return failed("The DiLink 5 capture program is unavailable")
        DaemonContext.get()?.let { avm.open(it) }
        val socket = "strike_fast_cam_${Process.myPid()}"
        try {
            child = nativeLaunch(executable.path, socket)
            if (child <= 0) return failed("The DiLink 5 capture program could not start")
            if (!nativeStart(surface, socket, frameRateFps)) return failed("The DiLink 5 cameras did not connect")
            return true
        } catch (e: LinkageError) {
            return failed("The DiLink 5 capture library could not load")
        }
    }

    @Synchronized
    fun close() {
        if (child > 0) {
            nativeStop()
            nativeTerminate(child)
            child = 0
        }
        avm.close()
    }

    private fun failed(reason: String): Boolean {
        close()
        DaemonLog.e("Camera", reason)
        return false
    }

    @JvmStatic private external fun nativeLaunch(executable: String, socketName: String): Int
    @JvmStatic private external fun nativeTerminate(pid: Int)
    @JvmStatic private external fun nativeStart(surface: Surface, socketName: String, frameRateFps: Int): Boolean
    @JvmStatic private external fun nativeStop()
}
