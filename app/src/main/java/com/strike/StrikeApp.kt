package com.strike

import android.app.Application
import com.strike.core.Crashes
import com.strike.core.Pin
import com.strike.daemon.Shell
import com.strike.recording.Triggers
import com.strike.server.HttpServer
import com.strike.server.Router
import java.io.File

class StrikeApp : Application() {

    lateinit var pin: Pin
        private set

    override fun onCreate() {
        super.onCreate()
        Crashes.watch(filesDir)
        pin = Pin(File(filesDir, "pin.json"))
        val shell = Shell(this)
        HttpServer(HttpServer.PORT, Router(this, pin, shell)).start()
        Triggers(this, shell).start()
    }
}
