package com.strike.recording

import android.content.Context
import android.os.Environment
import android.os.StatFs
import com.strike.core.systemProperty
import com.strike.daemon.Shell
import java.io.File

internal const val MB = 1024L * 1024L

internal const val INTERNAL = "internal"
private const val SD = "sd"
private const val USB = "usb"
private const val SD_UUID_PROP = "sys.byd.mSdcardUuid"
private const val STORAGE_ROOT = "/storage"

/** Linux block majors: mmcblk is the card slot, sd is anything on USB. */
private const val MMC_MAJOR = "179"
private const val SCSI_MAJOR = "8"

private const val REVALIDATE_MS = 5_000L

// Three api classes each hold a Storage, and they all describe the one device.
private val cacheLock = Any()
private var readAtMs = 0L
private var held: Map<String, Volume> = emptyMap()

class Volume(
    val location: String,
    val dir: File,
    val freeMb: Int,
    val totalMb: Int
)

/**
 * A removable volume is mounted outside the app's storage view, so
 * getExternalFilesDirs and StatFs never see it. Overdrive asks the volume
 * manager and measures through the shell, which is also the uid that writes
 * the clips, so a volume it cannot write is a volume Strike cannot offer.
 */
class Volumes(private val context: Context, private val shell: Shell) {

    /**
     * Asking the volume manager costs several shell round trips, and every
     * page that shows storage asks on every poll, so the answer is held for a
     * few seconds. A card appearing late is nothing; a settings page that
     * takes seconds to answer a tap is what this avoids.
     */
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
        val listing = shell.read("sm list-volumes all") ?: return null
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
        val listing = shell.read("sm list-volumes all") ?: return emptyList()
        return parseVolumes(listing, systemProperty(SD_UUID_PROP))
    }

    private fun room(path: String): Room? {
        val output = shell.read("df -k $path") ?: return null
        return parseDf(output)
    }

    private fun writable(path: String): Boolean {
        val probe = "$path/.strike-probe"
        return shell.check("touch $probe && rm -f $probe")
    }
}

internal class Mount(val location: String, val path: String)

internal class Room(val freeMb: Int, val totalMb: Int)

/**
 * Rows read `public:179,65 mounted 3439-3138`. ACC off often prints
 * `unmounted` or a null uuid; `sm mount` still wants `public:8,1`.
 */
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

/**
 * The FUSE view lands a few seconds after `sm mount` returns. A leftover
 * /storage/<uuid> node can exist while the tree is not writable, so this
 * waits until [dir] itself can be created, which is Overdrive's gate.
 */
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
    val process = ProcessBuilder(*command).redirectErrorStream(true).start()
    val printed = process.inputStream.bufferedReader().use { it.readText() }
    process.waitFor()
    printed
} catch (e: java.io.IOException) {
    null
} catch (e: InterruptedException) {
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
