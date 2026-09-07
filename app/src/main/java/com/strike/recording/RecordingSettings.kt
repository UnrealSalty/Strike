package com.strike.recording

class Choice(val options: List<String>, val fallback: String)

object RecordingSettings {

    const val MODE = "recording.mode"
    const val CLIP_LENGTH_MINUTES = "recording.clipLengthMinutes"
    const val QUALITY = "recording.quality"
    const val CODEC = "recording.codec"
    const val FRAME_RATE_FPS = "recording.frameRateFps"
    const val AUDIO = "recording.audio"
    const val LOCATION = "storage.location"
    const val BUDGET_MB = "storage.budgetMb"

    /** Written by the app, read by the daemon, never shown or set by hand. */
    const val CLIPS_DIR = "storage.clipsDir"
    const val BUDGET_FALLBACK_MB = 500
    const val BUDGET_FLOOR_MB = 100

    /** Left free on the volume so the car's own storage never runs to zero. */
    const val BUDGET_HEADROOM_MB = 256

    val choices = linkedMapOf(
        MODE to Choice(listOf("off", "continuous", "driving"), "off"),
        CLIP_LENGTH_MINUTES to Choice(listOf("2", "5", "10"), "2"),
        QUALITY to Choice(listOf("economy", "standard", "high", "premium", "max"), "standard"),
        CODEC to Choice(listOf("h264", "h265"), "h264"),
        FRAME_RATE_FPS to Choice(listOf("10", "15", "20", "25", "30"), "15"),
        LOCATION to Choice(listOf("internal", "sd", "usb"), "internal")
    )

    fun fallback(key: String): String = choices.getValue(key).fallback

    fun accepts(key: String, value: String): Boolean {
        val choice = choices[key]
        if (choice != null) return choice.options.contains(value)
        if (key == AUDIO) return value == "true" || value == "false"
        if (key == BUDGET_MB) {
            val budgetMb = value.toIntOrNull() ?: return false
            return budgetMb >= BUDGET_FLOOR_MB
        }
        return false
    }

    // Reclaimable clip space excludes headroom and the other feature's reservation.
    fun budgetCeilingMb(freeMb: Int, totalMb: Int, usedMb: Int, reservedMb: Int = 0): Int =
        maxOf(reachableMb(freeMb, totalMb, usedMb) - reservedMb, BUDGET_FLOOR_MB)

    fun hasRoom(freeMb: Int, totalMb: Int, usedMb: Int, reservedMb: Int = 0): Boolean =
        reachableMb(freeMb, totalMb, usedMb) - reservedMb >= BUDGET_FLOOR_MB

    private fun reachableMb(freeMb: Int, totalMb: Int, usedMb: Int): Int =
        minOf(freeMb + usedMb - BUDGET_HEADROOM_MB, totalMb)
}
