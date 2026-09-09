package com.strike.server

import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal val DASHBOARD_FILES = listOf("pin.json", "browser-access.json", "online.json",
    "updates.json", "update-install.json")
internal const val DASHBOARD_SOCKET = "com.strike.dashboard"
internal const val DASHBOARD_MAX_BYTES = 131_072

internal fun dashboardSeed(directory: File): JSONObject {
    val seed = JSONObject()
    for (name in DASHBOARD_FILES) {
        val file = File(directory, name)
        if (file.isFile) seed.put(name, file.readText())
    }
    return seed
}

internal fun importDashboard(directory: File, identity: String, seed: JSONObject) {
    require(identity.matches(Regex("[a-f0-9-]{36}")))
    for (name in DASHBOARD_FILES) {
        if (seed.has(name)) JSONObject(seed.getString(name))
    }
    Files.deleteIfExists(File(directory, "identity").toPath())
    for (name in DASHBOARD_FILES) {
        val file = File(directory, name)
        if (seed.has(name)) {
            atomicDashboardWrite(file, seed.getString(name))
        } else {
            Files.deleteIfExists(file.toPath())
        }
    }
    atomicDashboardWrite(File(directory, "identity"), identity)
}

internal fun atomicDashboardWrite(file: File, content: String) {
    val pending = File(file.path + ".tmp")
    pending.outputStream().use { it.write(content.toByteArray()); it.fd.sync() }
    Files.move(pending.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE)
}
