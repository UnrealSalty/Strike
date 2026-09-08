package com.strike.camera

enum class LiveQuality(val id: String, val bitrateBps: Int) {
    LOW("low", 500_000),
    BALANCED("balanced", 900_000),
    HIGH("high", LIVE_BITRATE_BPS);

    companion object {
        fun of(id: String?): LiveQuality = entries.firstOrNull { it.id == id } ?: HIGH
    }
}
