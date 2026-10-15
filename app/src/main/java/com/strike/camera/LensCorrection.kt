package com.strike.camera

/** Radial terms over the squared distance from a tile's centre, where its corner is 1. */
enum class LensCorrection(val id: String, val k1: Float, val k2: Float) {
    NONE("none", 0f, 0f),
    LIGHT("light", -0.15f, 0f),
    MEDIUM("medium", -0.30f, 0.02f),
    STRONG("strong", -0.45f, 0.05f);

    companion object {
        fun of(id: String?): LensCorrection = values().firstOrNull { it.id == id } ?: NONE
    }
}

/** Where a point of the corrected tile reads from, both in 0..1 across the tile. */
internal fun lensSource(correction: LensCorrection, x: Float, y: Float, aspect: Float): Pair<Float, Float> {
    val dx = x - 0.5f
    val dy = y - 0.5f
    val r2 = (dx * dx * aspect * aspect + dy * dy) / (0.25f * (aspect * aspect + 1f))
    val scale = 1f + correction.k1 * r2 + correction.k2 * r2 * r2
    return Pair(0.5f + dx * scale, 0.5f + dy * scale)
}
