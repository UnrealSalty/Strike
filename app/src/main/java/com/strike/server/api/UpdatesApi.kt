package com.strike.server.api

import com.strike.server.JSON
import com.strike.server.Response
import com.strike.server.TEXT
import com.strike.server.formValue
import com.strike.update.Updates

class UpdatesApi(private val updates: Updates) {
    fun status(): Response = Response(200, JSON, updates.status().toString().toByteArray())

    fun update(body: String): Response {
        when (formValue(body, "action")) {
            "check" -> updates.check(manual = true)
            "download" -> updates.download()
            "install" -> {
                if (formValue(body, "confirmed") != "true") {
                    return Response(400, TEXT, "Confirm installation first".toByteArray())
                }
                updates.install()
            }
            else -> return Response(400, TEXT, "Choose an update action".toByteArray())
        }
        return status()
    }
}
