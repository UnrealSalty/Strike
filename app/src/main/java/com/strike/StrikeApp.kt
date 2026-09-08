package com.strike

import android.app.Application
import com.strike.core.Crashes
import com.strike.core.Pin
import com.strike.daemon.Shell
import com.strike.recording.Triggers
import com.strike.online.Online
import com.strike.online.BrowserAccess
import com.strike.online.OnlineService
import com.strike.online.TunnelSettings
import com.strike.online.cloudflared
import com.strike.online.hasInternet
import com.strike.online.tunnelReady
import com.strike.server.HttpServer
import com.strike.server.BrowserGate
import com.strike.server.Router
import com.strike.server.api.DaemonsApi
import com.strike.update.Updates
import java.io.File

class StrikeApp : Application() {

    lateinit var pin: Pin
        private set
    lateinit var online: Online
        private set
    lateinit var browsers: BrowserGate
        private set
    lateinit var updates: Updates
        private set

    override fun onCreate() {
        super.onCreate()
        Crashes.watch(filesDir)
        pin = Pin(File(filesDir, "pin.json"))
        browsers = BrowserGate(BrowserAccess(File(filesDir, "browser-access.json")))
        val shell = Shell(this)
        online = Online(TunnelSettings(File(filesDir, "online.json")), browsers.access::isReady,
            { cloudflared(this, it) }, ::tunnelReady, { hasInternet(this) },
            keepAlive = { OnlineService.keepAlive(this, it) })
        val daemons = DaemonsApi(this, shell, online)
        updates = Updates(this, shell, daemons::pauseForUpdate, daemons::resumeAfterUpdate)
        HttpServer(HttpServer.PORT, Router(this, pin, shell, online, browsers, daemons, updates), browsers).start()
        Triggers(this, shell, online::vehicle).start()
        online.restore()
        updates.resume()
    }
}
