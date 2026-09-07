package com.strike.recording

import android.media.MediaFormat

/**
 * Overdrive's ladder: H.265 costs half again less than H.264 for the same
 * picture, so the H.264 rung is 1.5 times the H.265 one. The settings note in
 * the UI promises these numbers.
 */
private val H265_BPS = mapOf(
    "economy" to 1_000_000,
    "standard" to 2_000_000,
    "high" to 4_000_000,
    "premium" to 6_000_000,
    "max" to 10_000_000
)

fun bitrateBps(quality: String, codec: String): Int {
    val base = H265_BPS[quality] ?: H265_BPS.getValue(RecordingSettings.fallback(RecordingSettings.QUALITY))
    return if (codec == "h265") base else base * 3 / 2
}

fun mimeTypeOf(codec: String): String =
    if (codec == "h265") MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
