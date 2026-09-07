package com.strike.core

fun systemProperty(name: String): String? {
    val value = try {
        val get = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
        get.invoke(null, name) as String?
    } catch (e: ReflectiveOperationException) {
        null
    }
    return if (value.isNullOrEmpty()) null else value
}
