package com.strike

import android.app.Application
import android.os.Handler
import android.os.Looper
import com.strike.core.Crashes
import com.strike.core.Logs
import com.strike.core.Pin
import com.strike.daemon.Shell
import com.strike.recording.Triggers
import com.strike.online.Online
import com.strike.online.BrowserAccess
import com.strike.online.OnlineService
import com.strike.online.TunnelSettings
import com.strike.online.cloudflared
import com.strike.online.tunnelNetwork
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
    internal lateinit var setup: SetupRestart
        private set

    override fun onCreate() {
        super.onCreate()
        Crashes.watch(filesDir)
        pin = Pin(File(filesDir, "pin.json"))
        browsers = BrowserGate(BrowserAccess(File(filesDir, "browser-access.json")))
        val main = Handler(Looper.getMainLooper())
        val shell = Shell(this) { main.post { setup.shellAuthorised() } }
        setup = SetupRestart(!File(filesDir, "adbkey").isFile) {
            Thread({
                Logs.d("Shell", "Restarting Strike after permission setup")
                // The shell survives the app process; the recorder has its own UID.
                val started = shell.check("nohup sh -c 'am force-stop com.strike; " +
                    "am start -n com.strike/.MainActivity' </dev/null >/dev/null 2>&1 &")
                if (!started) Logs.w("Shell", "Setup is complete. Close and reopen Strike")
            }, "setup-restart").start()
        }
        online = Online(TunnelSettings(File(filesDir, "online.json")), browsers.access::isReady,
            { cloudflared(this, it) }, ::tunnelReady, { tunnelNetwork(this) },
            keepAlive = { OnlineService.keepAlive(this, it) })
        val daemons = DaemonsApi(this, shell, online)
        updates = Updates(this, shell, daemons::pauseForUpdate, daemons::resumeAfterUpdate)
        HttpServer(HttpServer.PORT, Router(this, pin, shell, online, browsers, daemons, updates), browsers).start()
        Triggers(this, shell, online::vehicle).start()
        online.restore()
        updates.resume()
    }
}
