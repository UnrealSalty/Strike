package com.strike.vehicle

import java.lang.reflect.Method

private const val CONNECT_RETRY_MS = 30_000L

internal class GearReader(
    private val adapter: () -> Any?,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    private val methods = HashMap<Class<*>, MutableMap<String, Method?>>()
    private var connectAtMs: Long? = null

    @Synchronized
    fun read(primary: Int?): String? {
        gearOf(primary)?.let { return it }
        val connected = adapter() ?: return null
        if (method(connected, "isCarServiceBound") != null) {
            when (call(connected, "isCarServiceBound")) {
                true -> Unit
                false -> {
                    val now = nowMs()
                    val last = connectAtMs
                    if (last == null || now - last >= CONNECT_RETRY_MS) {
                        connectAtMs = now
                        call(connected, "connect")
                    }
                    return null
                }
                else -> return null
            }
        }

        val body = call(connected, "getCarAdapterManager", "body")
        val shift = (call(body, "getShiftMode") as? Number)?.toInt()
        // The body adapter defines 1..4 as P/R/N/D; zero may be an unpopulated reading.
        if (shift != null && shift in 1..4) return gearOf(shift)

        val cabin = call(connected, "getCarAdapterManager", "cabin")
        gearOf((call(cabin, "getGearboxAutoModeType") as? Number)?.toInt())?.let { return it }
        return gearOf((call(cabin, "getGear") as? Number)?.toInt())
    }

    private fun call(target: Any?, name: String, section: String? = null): Any? {
        if (target == null) return null
        val method = method(target, name, section != null) ?: return null
        return try {
            if (section == null) method.invoke(target) else method.invoke(target, section)
        } catch (e: ReflectiveOperationException) {
            null
        }
    }

    private fun method(target: Any, name: String, hasSection: Boolean = false): Method? {
        val type = target.javaClass
        val available = methods.getOrPut(type) { HashMap() }
        if (!available.containsKey(name)) {
            available[name] = try {
                if (hasSection) type.getMethod(name, String::class.java) else type.getMethod(name)
            } catch (e: NoSuchMethodException) {
                null
            } catch (e: SecurityException) {
                null
            }
        }
        return available[name]
    }
}
