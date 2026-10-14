package com.strike.recording

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

private const val MOVING = ".moving"
// The faststart pass may still be rewriting a clip that closed moments ago.
private const val SETTLED_MS = 60_000L

/** Moves settled clips oldest first; stops at the first copy the target will not take. */
internal fun moveKept(root: File, into: File, kept: List<Kept>, sidecars: (String) -> List<String>, nowMs: Long): Int {
    if (kept.isEmpty() || !ensureDir(into)) return 0
    var moved = 0
    for (clip in kept.asReversed()) {
        val source = File(root, clip.id)
        if (nowMs - source.lastModified() < SETTLED_MS) continue
        val target = File(into, clip.id)
        if (target.exists() && target.length() != source.length()) continue
        for (name in sidecars(clip.id)) {
            val side = File(root, name)
            if (side.isFile && !copySidecar(side, File(into, name))) return moved
        }
        if (!target.exists() && !copyDurably(source, target)) return moved
        try {
            withClipFileLock(source) { source.delete() }
        } catch (e: IOException) {
            return moved
        }
        for (name in sidecars(clip.id)) File(root, name).delete()
        moved++
    }
    return moved
}

private fun copySidecar(source: File, target: File): Boolean = try {
    source.copyTo(target, overwrite = true)
    target.setReadable(true, false)
    true
} catch (e: IOException) {
    false
}

private fun copyDurably(source: File, target: File): Boolean {
    val staging = File(target.parentFile, target.name + MOVING)
    try {
        FileInputStream(source).use { input ->
            FileOutputStream(staging).use { output ->
                input.copyTo(output, 1 shl 20)
                output.fd.sync()
            }
        }
        if (staging.length() != source.length()) {
            staging.delete()
            return false
        }
        val placed = withClipFileLock(target) { !target.exists() && staging.renameTo(target) }
        if (!placed) {
            staging.delete()
            return false
        }
        target.setReadable(true, false)
        return true
    } catch (e: IOException) {
        staging.delete()
        return false
    }
}
