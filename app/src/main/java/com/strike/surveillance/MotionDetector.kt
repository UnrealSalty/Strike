package com.strike.surveillance

import com.strike.camera.Mosaic

private const val ACROSS = 16
private const val DOWN = 12
private const val BLOCKS = ACROSS * DOWN

private const val LUMA_STEP = 12

class Verdict(
    val moved: Boolean,
    val score: Float,
    private val quadrants: BooleanArray = BooleanArray(4),
    private val blocks: BooleanArray = BooleanArray(BLOCKS)
) {
    fun movedIn(quadrant: Int): Boolean = quadrants[quadrant]

    // Require changed blocks inside the detected box, not merely in the same camera view.
    fun movedInBox(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        mosaicWidth: Int,
        mosaicHeight: Int,
        share: Float
    ): Boolean {
        if (mosaicWidth <= 0 || mosaicHeight <= 0 || width <= 0 || height <= 0) return false
        val blockWidth = mosaicWidth / ACROSS
        val blockHeight = mosaicHeight / DOWN
        if (blockWidth <= 0 || blockHeight <= 0) return false
        val left = (x / blockWidth).coerceIn(0, ACROSS - 1)
        val right = ((x + width - 1) / blockWidth).coerceIn(0, ACROSS - 1)
        val top = (y / blockHeight).coerceIn(0, DOWN - 1)
        val bottom = ((y + height - 1) / blockHeight).coerceIn(0, DOWN - 1)
        var total = 0
        var hit = 0
        for (row in top..bottom) {
            for (column in left..right) {
                total++
                if (blocks[row * ACROSS + column]) hit++
            }
        }
        return total > 0 && hit.toFloat() / total >= share
    }
}

// Score block brightness changes per camera quadrant.
class MotionDetector {

    private var previous: IntArray? = null

    @Synchronized
    fun forget() {
        previous = null
    }

    @Synchronized
    fun evaluate(mosaic: Mosaic, movedShare: Float): Verdict {
        val means = blockMeans(mosaic)
        val before = previous
        previous = means
        if (before == null) return Verdict(false, 0f)

        val deltas = IntArray(BLOCKS) { means[it] - before[it] }
        val exposure = exposureShift(deltas)
        val movedBlocks = BooleanArray(BLOCKS)
        val movedPerQuadrant = IntArray(4)
        for (block in 0 until BLOCKS) {
            if (Math.abs(deltas[block] - exposure) < LUMA_STEP) continue
            movedBlocks[block] = true
            val column = block % ACROSS
            val row = block / ACROSS
            val quadrant = (if (row < DOWN / 2) 0 else 2) + (if (column < ACROSS / 2) 0 else 1)
            movedPerQuadrant[quadrant]++
        }
        val perQuadrant = (BLOCKS / 4).toFloat()
        val quadrants = BooleanArray(4)
        var score = 0f
        for (quadrant in 0 until 4) {
            val share = movedPerQuadrant[quadrant] / perQuadrant
            quadrants[quadrant] = share >= movedShare
            if (share > score) score = share
        }
        return Verdict(score >= movedShare, score, quadrants, movedBlocks)
    }

    // Subtract the common brightness shift to reject camera auto-exposure changes.
    private fun exposureShift(deltas: IntArray): Int {
        val sorted = deltas.copyOf()
        sorted.sort()
        return sorted[sorted.size / 2]
    }

    private fun blockMeans(mosaic: Mosaic): IntArray {
        val blockWidth = mosaic.width / ACROSS
        val blockHeight = mosaic.height / DOWN
        val means = IntArray(BLOCKS)
        for (row in 0 until DOWN) {
            for (column in 0 until ACROSS) {
                var total = 0L
                for (y in row * blockHeight until (row + 1) * blockHeight) {
                    var at = (y * mosaic.width + column * blockWidth) * 4
                    for (x in 0 until blockWidth) {
                        val red = mosaic.rgba[at].toInt() and 0xFF
                        val green = mosaic.rgba[at + 1].toInt() and 0xFF
                        val blue = mosaic.rgba[at + 2].toInt() and 0xFF
                        total += (red * 77 + green * 151 + blue * 28) shr 8
                        at += 4
                    }
                }
                means[row * ACROSS + column] = (total / (blockWidth * blockHeight)).toInt()
            }
        }
        return means
    }
}
