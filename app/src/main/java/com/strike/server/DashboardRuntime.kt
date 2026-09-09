package com.strike.server

import android.content.Context
import com.strike.core.Pin
import com.strike.daemon.Shell
import com.strike.online.BrowserAccess
import com.strike.online.Online
import com.strike.online.OnlinePower
import com.strike.online.TunnelSettings
import com.strike.online.cloudflared
import com.strike.online.tunnelNetwork
import com.strike.online.tunnelReady
import com.strike.server.api.DaemonsApi
import com.strike.update.Updates
import com.strike.vehicle.VehicleTelemetry
import java.io.File

internal class DashboardRuntime(context: Context, shell: Shell, vehicle: VehicleTelemetry? = null) {
    val pin = Pin(File(context.filesDir, "pin.json"))
    val browsers = BrowserGate(BrowserAccess(File(context.filesDir, "browser-access.json")))
    private val power = OnlinePower(context)
    private val telemetry = vehicle ?: VehicleTelemetry(context)
    val online = Online(TunnelSettings(File(context.filesDir, "online.json")), browsers.access::isReady,
        { cloudflared(context, it) }, ::tunnelReady, { tunnelNetwork(context) },
        keepAwake = power::hold, readVehicle = vehicle?.let { it::parkingSnapshot })
    private val daemons = DaemonsApi(context, shell, online)
    val updates = Updates(context, shell, daemons::pauseForUpdate, daemons::resumeAfterUpdate)
    private val http = HttpServer(HttpServer.PORT,
        Router(context, pin, shell, online, browsers, daemons, updates, telemetry), browsers)

    fun start(background: Boolean = true) {
        check(http.start()) { "The dashboard port is occupied" }
        if (background) {
            online.restore()
            updates.resume()
        }
    }

    fun close() {
        check(http.close()) { "The dashboard is still finishing a request" }
        online.close()
        check(power.releasePanel()) { "The parked display is still held" }
        check(updates.awaitIdle(30_000L)) { "An update is still in progress" }
    }

    fun releasePanel(): Boolean = power.releasePanel()
}
