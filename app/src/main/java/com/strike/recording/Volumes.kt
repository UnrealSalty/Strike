package com.strike.recording

import android.content.Context
import android.os.Environment
import android.os.StatFs
import com.strike.core.systemProperty
import com.strike.daemon.Shell
import java.io.File

internal const val MB = 1024L * 1024L
internal const val LIST_VOLUMES = "timeout -s KILL 3 sm list-volumes all"

internal const val INTERNAL = "internal"
private const val SD = "sd"
private const val USB = "usb"
private const val SD_UUID_PROP = "sys.byd.mSdcardUuid"
private const val STORAGE_ROOT = "/storage"

/** Linux block majors: mmcblk is the card slot, sd is anything on USB. */
private const val MMC_MAJOR = "179"
private const val SCSI_MAJOR = "8"

private const val REVALIDATE_MS = 5_000L

private val cacheLock = Any()
private var readAtMs = 0L
private var held: Map<String, Volume> = emptyMap()

class Volume(
    val location: String,
    val dir: File,
    val freeMb: Int,
    val totalMb: Int
)

// Removable storage may be absent from the app's view; query mounts and capacity through shell.
class Volumes(private val context: Context, private val shell: Shell) {

    // Share volume probes across API requests to avoid repeated ADB round trips.
    fun mounted(): Map<String, Volume> = synchronized(cacheLock) {
        val now = System.currentTimeMillis()
        if (now - readAtMs < REVALIDATE_MS) return held
        val found = discover()
        held = found
        readAtMs = now
        found
    }

    private fun discover(): Map<String, Volume> {
        val found = LinkedHashMap<String, Volume>()
        internal()?.let { found[INTERNAL] = it }
        for (mount in removable()) {
            if (found.containsKey(mount.location)) continue
            val room = room(mount.path) ?: continue
            if (!writable(mount.path)) continue
            found[mount.location] = Volume(
                location = mount.location,
                dir = File(mount.path),
                freeMb = room.freeMb,
                totalMb = room.totalMb
            )
        }
        return found
    }

    fun rootFor(location: String): File? {
        mounted()[location]?.dir?.let { return it }
        if (location == INTERNAL) return Environment.getExternalStorageDirectory()
        val listing = shell.read(LIST_VOLUMES) ?: return null
        val path = volumePathFor(location, listing, systemProperty(SD_UUID_PROP)) ?: return null
        return File(path)
    }

    private fun internal(): Volume? {
        val dir = Environment.getExternalStorageDirectory() ?: return null
        val stat = try {
            StatFs(dir.path)
        } catch (e: IllegalArgumentException) {
            return null
        }
        return Volume(
            location = INTERNAL,
            dir = dir,
            freeMb = (stat.availableBytes / MB).toInt(),
            totalMb = (stat.totalBytes / MB).toInt()
        )
    }

    private fun removable(): List<Mount> {
        val listing = shell.read(LIST_VOLUMES) ?: return emptyList()
        return parseVolumes(listing, systemProperty(SD_UUID_PROP))
    }

    private fun room(path: String): Room? {
        val output = shell.read("timeout -s KILL 3 df -k $path") ?: return null
        return parseDf(output)
    }

    private fun writable(path: String): Boolean {
        val probe = "$path/.strike-probe"
        return shell.check("timeout -s KILL 3 touch $probe && timeout -s KILL 3 rm -f $probe")
    }
}

internal class Mount(val location: String, val path: String)

internal class Room(val freeMb: Int, val totalMb: Int)

// sm mount takes the public volume ID, even when its UUID is unavailable.
internal fun volumeIdFor(listing: String, uuid: String): String? {
    for (line in listing.lineSequence()) {
        val fields = line.trim().split(Regex("\\s+"))
        if (fields.size < 3) continue
        if (!fields[0].startsWith("public")) continue
        if (!fields[2].equals(uuid, ignoreCase = true)) continue
        if (fields[2] == "null" || fields[2].isEmpty()) continue
        return fields[0]
    }
    return null
}

internal fun mountIds(listing: String, uuid: String): List<String> {
    volumeIdFor(listing, uuid)?.let { return listOf(it) }
    val ids = ArrayList<String>()
    for (line in listing.lineSequence()) {
        val fields = line.trim().split(Regex("\\s+"))
        if (fields.isEmpty()) continue
        if (fields[0].startsWith("public:")) ids.add(fields[0])
    }
    return ids
}

internal fun forgetMounted() = synchronized(cacheLock) {
    readAtMs = 0L
}

internal fun allPublicIds(listing: String): List<String> {
    val ids = ArrayList<String>()
    for (line in listing.lineSequence()) {
        val fields = line.trim().split(Regex("\\s+"))
        if (fields.isEmpty()) continue
        if (fields[0].startsWith("public:")) ids.add(fields[0])
    }
    return ids
}

internal fun volumePathFor(location: String, listing: String, sdUuid: String?): String? {
    if (location == INTERNAL) return "/storage/emulated/0"
    for (line in listing.lineSequence()) {
        val fields = line.trim().split(Regex("\\s+"))
        if (fields.size < 3) continue
        val descriptor = fields[0]
        if (!descriptor.startsWith("public")) continue
        val uuid = fields[2]
        if (uuid == "null" || uuid.isEmpty()) continue
        if (classify(descriptor, uuid, sdUuid) == location) return "$STORAGE_ROOT/$uuid"
    }
    return null
}

internal fun storageUuid(path: String): String? {
    if (!path.startsWith("$STORAGE_ROOT/")) return null
    val uuid = path.substring(STORAGE_ROOT.length + 1).substringBefore('/')
    if (uuid.isEmpty() || uuid == "emulated" || uuid == "self") return null
    return uuid
}

/** Issues `sm mount`. Does not wait for FUSE; the supervisor cannot stall here. */
internal fun remount(dir: File) {
    val uuid = storageUuid(dir.path) ?: return
    val listing = exec("sm", "list-volumes", "all") ?: ""
    for (id in mountIds(listing, uuid)) exec("sm", "mount", id)
}

// FUSE may lag behind sm mount; wait until the recording directory can be created.
internal fun remountUntil(dir: File): Boolean {
    remount(dir)
    repeat(20) {
        if (dir.exists() || (dir.mkdirs() && dir.exists())) return true
        try {
            Thread.sleep(500)
        } catch (e: InterruptedException) {
            return dir.exists()
        }
    }
    return dir.exists() || (dir.mkdirs() && dir.exists())
}

private fun exec(vararg command: String): String? = try {
    val process = ProcessBuilder("timeout", "-s", "KILL", "3", *command).redirectErrorStream(true).start()
    try {
        val printed = process.inputStream.bufferedReader().use { it.readText() }
        if (process.waitFor() == 0) printed else null
    } finally {
        if (process.isAlive) process.destroyForcibly()
    }
} catch (e: java.io.IOException) {
    null
} catch (e: InterruptedException) {
    Thread.currentThread().interrupt()
    null
}

internal fun parseVolumes(listing: String, sdUuid: String?): List<Mount> {
    val mounts = ArrayList<Mount>()
    for (line in listing.lineSequence()) {
        val fields = line.trim().split(Regex("\\s+"))
        if (fields.size < 3) continue
        val descriptor = fields[0]
        if (!descriptor.startsWith("public")) continue
        if (fields[1] != "mounted") continue
        val uuid = fields[2]
        if (uuid == "null" || uuid.isEmpty()) continue
        mounts.add(Mount(classify(descriptor, uuid, sdUuid), "$STORAGE_ROOT/$uuid"))
    }
    return mounts
}

internal fun classify(descriptor: String, uuid: String, sdUuid: String?): String {
    if (sdUuid != null && sdUuid.isNotEmpty()) {
        return if (uuid.equals(sdUuid, ignoreCase = true)) SD else USB
    }
    return when (descriptor.substringAfter(':', "").substringBefore(',')) {
        MMC_MAJOR -> SD
        SCSI_MAJOR -> USB
        else -> SD
    }
}

/** toybox prints a header, then the filesystem with 1K blocks then used then free. */
internal fun parseDf(output: String): Room? {
    for (line in output.lineSequence()) {
        val fields = line.trim().split(Regex("\\s+"))
        if (fields.size < 4) continue
        val totalKb = fields[1].toLongOrNull() ?: continue
        val freeKb = fields[3].toLongOrNull() ?: continue
        return Room(freeMb = (freeKb / 1024L).toInt(), totalMb = (totalKb / 1024L).toInt())
    }
    return null
}
