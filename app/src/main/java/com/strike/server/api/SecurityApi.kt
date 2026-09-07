package com.strike.server.api

import com.strike.core.PIN_MAX
import com.strike.core.PIN_MIN
import com.strike.core.Pin
import com.strike.core.PinCheck
import com.strike.core.PinSession
import com.strike.core.PinSet
import com.strike.server.JSON
import com.strike.server.Response
import com.strike.server.TEXT
import com.strike.server.formValue
import com.strike.server.sessionCookie
import org.json.JSONObject

class SecurityApi(private val pin: Pin) {

    fun status(): Response {
        val payload = JSONObject()
        payload.put("set", pin.isSet())
        payload.put("lockoutMs", pin.lockoutMs())
        return Response(200, JSON, payload.toString().toByteArray())
    }

    fun unlock(body: String): Response {
        if (!pin.isSet()) return ok()
        val given = formValue(body, "pin") ?: return refuse("wrong", pin.lockoutMs())
        return when (pin.check(given)) {
            PinCheck.OK -> opened()
            PinCheck.WRONG -> refuse("wrong", 0L)
            PinCheck.LOCKED -> refuse("locked", pin.lockoutMs())
            PinCheck.UNSET -> ok()
        }
    }

    fun update(body: String): Response {
        val action = formValue(body, "action") ?: return bad("No action named")
        return when (action) {
            "set" -> set(body)
            "change" -> change(body)
            "clear" -> clear(body)
            else -> bad("That action is not known")
        }
    }

    private fun set(body: String): Response {
        if (pin.isSet()) return bad("A PIN is already set")
        val next = formValue(body, "pin") ?: return bad("No PIN given")
        val confirm = formValue(body, "confirm") ?: return bad("No confirm given")
        if (next != confirm) return bad("Those PINs do not match")
        if (!Pin.acceptable(next)) return bad("PIN must be $PIN_MIN to $PIN_MAX digits")
        if (pin.set(next) != PinSet.OK) return bad("PIN must be $PIN_MIN to $PIN_MAX digits")
        return opened()
    }

    private fun change(body: String): Response {
        val current = formValue(body, "current") ?: return bad("No current PIN given")
        when (pin.check(current)) {
            PinCheck.OK -> Unit
            PinCheck.WRONG -> return refuse("wrong", 0L)
            PinCheck.LOCKED -> return refuse("locked", pin.lockoutMs())
            PinCheck.UNSET -> return bad("No PIN is set")
        }
        val next = formValue(body, "pin") ?: return bad("No PIN given")
        val confirm = formValue(body, "confirm") ?: return bad("No confirm given")
        if (next != confirm) return bad("Those PINs do not match")
        if (pin.set(next) != PinSet.OK) return bad("PIN must be $PIN_MIN to $PIN_MAX digits")
        return ok()
    }

    private fun clear(body: String): Response {
        val current = formValue(body, "current") ?: return bad("No current PIN given")
        return when (pin.check(current)) {
            PinCheck.OK -> {
                pin.clear()
                ok()
            }
            PinCheck.WRONG -> refuse("wrong", 0L)
            PinCheck.LOCKED -> refuse("locked", pin.lockoutMs())
            PinCheck.UNSET -> ok()
        }
    }

    private fun opened(): Response {
        val token = PinSession.unlock()
        val payload = JSONObject()
        payload.put("ok", true)
        return Response(
            200,
            JSON,
            payload.toString().toByteArray(),
            headers = mapOf("Set-Cookie" to sessionCookie(token))
        )
    }

    private fun ok(): Response {
        val payload = JSONObject()
        payload.put("ok", true)
        return Response(200, JSON, payload.toString().toByteArray())
    }

    private fun refuse(error: String, lockoutMs: Long): Response {
        val payload = JSONObject()
        payload.put("ok", false)
        payload.put("error", error)
        payload.put("lockoutMs", lockoutMs)
        return Response(403, JSON, payload.toString().toByteArray())
    }

    private fun bad(message: String): Response = Response(400, TEXT, message.toByteArray())
}
