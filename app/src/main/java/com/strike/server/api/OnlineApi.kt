package com.strike.server.api

import com.strike.online.Online
import com.strike.online.localAddresses
import com.strike.server.HttpServer
import com.strike.server.BrowserGate
import com.strike.server.JSON
import com.strike.server.Response
import com.strike.server.TEXT
import com.strike.server.formValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class OnlineApi(
    private val online: Online,
    private val browsers: BrowserGate,
    private val addresses: () -> List<String> = ::localAddresses
) {
    fun status(inCar: Boolean = false): Response {
        val payload = online.status()
        val access = JSONObject().put("canManage", inCar)
        if (inCar) access.put("code", browsers.access.code() ?: JSONObject.NULL)
        payload.put("browserAccess", access)
        payload.put("addresses", JSONArray(addresses().map { "http://$it:${HttpServer.PORT}/" }))
        return Response(200, JSON, payload.toString().toByteArray())
    }

    fun update(body: String, inCar: Boolean = false): Response = if (!inCar) {
        Response(403, TEXT, "Edit tunnel settings from the car".toByteArray())
    } else try {
        when (formValue(body, "action") ?: "toggle") {
            "regenerate" -> {
                browsers.regenerate()
            }
            "save" -> online.configure(formValue(body, "hostname") ?: "",
                formValue(body, "token") ?: "", formValue(body, "mode") ?: "")
            "toggle" -> {
                val enabled = formValue(body, "enabled")
                require(enabled == "true" || enabled == "false") { "Choose on or off" }
                online.enable(enabled == "true")
            }
            "retry" -> online.retry()
            "forget" -> online.forget()
            else -> throw IllegalArgumentException("Choose an Online action")
        }
        status(inCar)
    } catch (e: IllegalArgumentException) {
        Response(400, TEXT, (e.message ?: "Check the tunnel settings").toByteArray())
    } catch (e: IllegalStateException) {
        Response(409, TEXT, (e.message ?: "The tunnel is busy").toByteArray())
    } catch (e: IOException) {
        Response(500, TEXT, "Cannot save the Online settings".toByteArray())
    }
}
