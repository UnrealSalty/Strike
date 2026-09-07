package com.strike.surveillance

/** Overdrive's DetectionBaseline match. Same car, same place. */
private const val MATCH = 0.7f

/** Two quiet looks before a vehicle is scenery. One frame is a passer-by. */
private const val HITS = 2

class Scene {

    private val parked = ArrayList<Held>()

    @Synchronized
    fun forget() {
        parked.clear()
    }

    @Synchronized
    fun observe(sightings: List<Sighting>, quiet: Boolean) {
        val present = ArrayList<Held>()
        for (sighting in sightings) {
            if (sighting.seen != VEHICLE) continue
            val at = indexOf(sighting)
            val held = if (at >= 0) parked[at] else null
            if (!quiet && (held == null || held.hits < HITS)) continue
            val hits = ((held?.hits ?: 0) + if (quiet) 1 else 0).coerceAtMost(HITS)
            present.add(Held(held?.box ?: sighting, hits))
        }
        parked.clear()
        parked.addAll(present)
    }

    @Synchronized
    fun isParked(sighting: Sighting): Boolean {
        if (sighting.seen != VEHICLE) return false
        val at = indexOf(sighting)
        return at >= 0 && parked[at].hits >= HITS
    }

    private fun indexOf(sighting: Sighting): Int {
        for (i in parked.indices) {
            if (overlap(parked[i].box, sighting) >= MATCH) return i
        }
        return -1
    }

    private data class Held(val box: Sighting, val hits: Int)
}

internal fun overlap(a: Sighting, b: Sighting): Float {
    val left = maxOf(a.x, b.x)
    val top = maxOf(a.y, b.y)
    val right = minOf(a.x + a.width, b.x + b.width)
    val bottom = minOf(a.y + a.height, b.y + b.height)
    val wide = right - left
    val high = bottom - top
    if (wide <= 0 || high <= 0) return 0f
    val both = wide * high
    val either = a.width * a.height + b.width * b.height - both
    return if (either <= 0) 0f else both.toFloat() / either
}
