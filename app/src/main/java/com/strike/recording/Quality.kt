package com.strike.recording

import android.media.MediaFormat

// Bitrate presets match Overdrive; H.264 uses 1.5 times the H.265 bitrate.
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
