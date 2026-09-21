package com.strike

import android.content.Context
import com.strike.core.Logs
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

internal fun prepareBootAccess(context: Context) {
    val source = context.filesDir
    val configured = canRecoverAfterBoot(source)
    if (!prepareBootAccess(source, context.createDeviceProtectedStorageContext().filesDir) && configured) {
        Logs.w("Boot", "Could not save access for recovery before Android unlocks")
    }
}

// Only the existing shell identity is needed before unlock; dashboard secrets stay in their own store.
@Synchronized
internal fun prepareBootAccess(source: File, destination: File): Boolean {
    val marker = File(destination, "dashboard.migrated")
    try {
        if (!canRecoverAfterBoot(source)) {
            Files.deleteIfExists(marker.toPath())
            return false
        }
        val names = listOf("adbkey", "adbkey.pub", "dashboard.identity", "dashboard.migrated")
        val saved = names.associateWith { File(source, it).readBytes() }
        if (canRecoverAfterBoot(destination) && saved.all { (name, bytes) ->
                File(destination, name).readBytes().contentEquals(bytes)
            }) return true
        Files.deleteIfExists(marker.toPath())
        Files.createDirectories(destination.toPath())
        for ((name, bytes) in saved) {
            if (!canRecoverAfterBoot(source)) return false
            val target = File(destination, name)
            val temporary = File(destination, "$name.tmp")
            try {
                temporary.writeBytes(bytes)
                Files.move(temporary.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(temporary.toPath())
            }
        }
        return true
    } catch (e: IOException) {
        try {
            Files.deleteIfExists(marker.toPath())
        } catch (ignored: IOException) {
            return false
        }
        return false
    }
}
