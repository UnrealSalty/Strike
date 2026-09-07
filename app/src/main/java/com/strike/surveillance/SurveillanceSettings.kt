package com.strike.surveillance

import com.strike.recording.Choice
import com.strike.recording.RecordingSettings

// If lock state stays unavailable, arm after this delay once parking is confirmed.
const val LOCK_FALLBACK_MS = 60_000L

/** Cap on one event clip. The event itself ends when the movement does. */
const val EVENT_CLIP_CAP_MS = 120_000L

/** Kept recording for this long after the last confirmed person or vehicle. */
const val EVENT_TAIL_MS = 20_000L

object SurveillanceSettings {

    const val ENABLED = "surveillance.enabled"
    const val MODE = "surveillance.mode"
    const val ARM = "surveillance.arm"
    const val PROXIMITY = "surveillance.proximity"
    const val SCREEN = "surveillance.screen"
    const val MESSAGE = "surveillance.message"
    const val SCREEN_SECONDS = "surveillance.screenSeconds"
    const val LOCATION = "surveillance.location"
    const val BUDGET_MB = "surveillance.budgetMb"

    /** Written by the app, read by the daemon, never shown or set by hand. */
    const val EVENTS_DIR = "surveillance.eventsDir"

    const val BUDGET_FALLBACK_MB = 500
    const val MESSAGE_FALLBACK = "You are being recorded."
    const val SCREEN_SUBTITLE = "Recording in progress"
    const val MESSAGE_MAX_CHARS = 60

    val choices = linkedMapOf(
        MODE to Choice(listOf("smart", "continuous"), "smart"),
        ARM to Choice(listOf("off", "lock"), "off"),
        PROXIMITY to Choice(listOf("1", "2", "3", "4", "5"), "3"),
        SCREEN_SECONDS to Choice(listOf("5", "10", "20", "30"), "10"),
        LOCATION to Choice(listOf("internal", "sd", "usb"), "internal")
    )

    fun fallback(key: String): String = choices.getValue(key).fallback

    fun accepts(key: String, value: String): Boolean {
        val choice = choices[key]
        if (choice != null) return choice.options.contains(value)
        if (key == ENABLED || key == SCREEN) return value == "true" || value == "false"
        if (key == MESSAGE) return value.isNotBlank() && value.length <= MESSAGE_MAX_CHARS
        if (key == BUDGET_MB) {
            val budgetMb = value.toIntOrNull() ?: return false
            return budgetMb >= RecordingSettings.BUDGET_FLOOR_MB
        }
        return false
    }
}

// Minimum changed area within one camera quadrant, from closest (1) to widest (5).
fun movedShareFor(proximity: Int): Float = when (proximity) {
    1 -> 0.24f
    2 -> 0.12f
    4 -> 0.03f
    5 -> 0.015f
    else -> 0.06f
}

// Minimum detection-box height relative to one camera quadrant.
fun boxShareFor(proximity: Int): Float = when (proximity) {
    1 -> 0.55f
    2 -> 0.35f
    4 -> 0.12f
    5 -> 0.06f
    else -> 0.20f
}
