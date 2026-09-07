package com.strike.camera

// The supported input is a horizontal strip of four camera views.
enum class CameraView(val id: String) {
    ALL("all"),
    FRONT("front"),
    RIGHT("right"),
    REAR("rear"),
    LEFT("left");

    companion object {
        fun of(id: String?): CameraView = values().firstOrNull { it.id == id } ?: FRONT
    }
}

private const val SLICES = 4
private const val SLICE_WIDTH = 1f / SLICES

// Camera order matches Overdrive's PanoramicSlice mapping.
private val SLICE_X = mapOf(
    CameraView.REAR to 0.00f,
    CameraView.LEFT to 0.25f,
    CameraView.RIGHT to 0.50f,
    CameraView.FRONT to 0.75f
)

private val MOSAIC_XY = mapOf(
    CameraView.FRONT to Pair(0.0f, 0.0f),
    CameraView.RIGHT to Pair(0.5f, 0.0f),
    CameraView.REAR to Pair(0.0f, 0.5f),
    CameraView.LEFT to Pair(0.5f, 0.5f)
)

// Source and destination coordinates are fractions, with the origin at the top left.
class Tile(
    val sourceX: Float,
    val sourceWidth: Float,
    val destX: Float,
    val destY: Float,
    val destWidth: Float,
    val destHeight: Float
)

fun tilesOf(view: CameraView): List<Tile> {
    if (view != CameraView.ALL) {
        val x = SLICE_X.getValue(view)
        return listOf(Tile(x, SLICE_WIDTH, 0f, 0f, 1f, 1f))
    }
    return MOSAIC_XY.map { (angle, corner) ->
        Tile(SLICE_X.getValue(angle), SLICE_WIDTH, corner.first, corner.second, 0.5f, 0.5f)
    }
}

class Frame(val width: Int, val height: Int)

fun frameOf(view: CameraView, stripWidth: Int, stripHeight: Int): Frame {
    val slice = stripWidth / SLICES
    return if (view == CameraView.ALL) {
        Frame(slice * 2, stripHeight * 2)
    } else {
        Frame(slice, stripHeight)
    }
}

private const val LIVE_MAX_WIDTH = 1280
private const val LIVE_MAX_HEIGHT = 960
private const val MACROBLOCK = 16

// Scale Live separately to leave encoder capacity for full-resolution clips.
fun liveFrameOf(view: CameraView, stripWidth: Int, stripHeight: Int): Frame =
    fitted(frameOf(view, stripWidth, stripHeight), LIVE_MAX_WIDTH, LIVE_MAX_HEIGHT)

internal fun fitted(frame: Frame, maxWidth: Int, maxHeight: Int): Frame {
    val scale = minOf(
        maxWidth.toFloat() / frame.width,
        maxHeight.toFloat() / frame.height,
        1f
    )
    if (scale == 1f) return frame
    return Frame(blocks(frame.width * scale), blocks(frame.height * scale))
}

/** An encoder codes in whole macroblocks, so a stray pixel becomes a green edge. */
private fun blocks(value: Float): Int =
    maxOf(MACROBLOCK, (Math.round(value / MACROBLOCK) * MACROBLOCK))
