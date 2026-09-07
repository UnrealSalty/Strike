package com.strike.camera

import org.junit.Assert.assertEquals
import org.junit.Test

private const val STRIP_WIDTH = 5120
private const val STRIP_HEIGHT = 960

class CameraViewTest {

    @Test
    fun eachAngleReadsItsOwnQuarterOfTheStrip() {
        assertEquals(0.00f, only(CameraView.REAR).sourceX, 0f)
        assertEquals(0.25f, only(CameraView.LEFT).sourceX, 0f)
        assertEquals(0.50f, only(CameraView.RIGHT).sourceX, 0f)
        assertEquals(0.75f, only(CameraView.FRONT).sourceX, 0f)
    }

    @Test
    fun anAngleFillsTheWholeFrameItIsDrawnInto() {
        val tile = only(CameraView.FRONT)

        assertEquals(0.25f, tile.sourceWidth, 0f)
        assertEquals(1f, tile.destWidth, 0f)
        assertEquals(1f, tile.destHeight, 0f)
    }

    @Test
    fun theMosaicDrawsFourQuartersIntoFourCorners() {
        val tiles = tilesOf(CameraView.ALL)

        assertEquals(4, tiles.size)
        for (tile in tiles) {
            assertEquals(0.25f, tile.sourceWidth, 0f)
            assertEquals(0.5f, tile.destWidth, 0f)
            assertEquals(0.5f, tile.destHeight, 0f)
        }
        assertEquals(setOf(0f, 0.5f), tiles.map { it.destX }.toSet())
        assertEquals(setOf(0f, 0.5f), tiles.map { it.destY }.toSet())
    }

    @Test
    fun theMosaicCornersFollowOverdrivesLayout() {
        val corners = tilesOf(CameraView.ALL).associate { Pair(it.destX, it.destY) to it.sourceX }

        assertEquals(0.75f, corners.getValue(Pair(0f, 0f)))
        assertEquals(0.50f, corners.getValue(Pair(0.5f, 0f)))
        assertEquals(0.00f, corners.getValue(Pair(0f, 0.5f)))
        assertEquals(0.25f, corners.getValue(Pair(0.5f, 0.5f)))
    }

    @Test
    fun noTwoTilesOfTheMosaicOverlapOrRepeatAnAngle() {
        val tiles = tilesOf(CameraView.ALL)

        assertEquals(4, tiles.map { Pair(it.destX, it.destY) }.toSet().size)
        assertEquals(4, tiles.map { it.sourceX }.toSet().size)
    }

    @Test
    fun theStripIsTooWideToEncodeButItsSlicesAreNot() {
        val whole = frameOf(CameraView.ALL, STRIP_WIDTH, STRIP_HEIGHT)
        val one = frameOf(CameraView.FRONT, STRIP_WIDTH, STRIP_HEIGHT)

        assertEquals(2560, whole.width)
        assertEquals(1920, whole.height)
        assertEquals(1280, one.width)
        assertEquals(960, one.height)
    }

    @Test
    fun anUnknownAngleFallsBackToTheRoadAhead() {
        assertEquals(CameraView.FRONT, CameraView.of(null))
        assertEquals(CameraView.FRONT, CameraView.of("sideways"))
        assertEquals(CameraView.ALL, CameraView.of("all"))
    }

    @Test
    fun theTextureCoordinatesSpanTheSliceAndFlipTheRows() {
        val coords = sourceCoords(only(CameraView.RIGHT))

        assertEquals(8, coords.size)
        assertEquals(setOf(0.50f, 0.75f), coords.filterIndexed { i, _ -> i % 2 == 0 }.toSet())
        // Bottom two corners of the quad read row 1, the top two read row 0.
        assertEquals(listOf(1f, 1f, 0f, 0f), coords.filterIndexed { i, _ -> i % 2 == 1 })
    }

    @Test
    fun theMosaicIsScaledDownForAViewerButAnAngleIsNot() {
        val strip = liveFrameOf(CameraView.ALL, 5120, 960)
        val angle = liveFrameOf(CameraView.FRONT, 5120, 960)

        assertEquals(1280, strip.width)
        assertEquals(960, strip.height)
        assertEquals(1280, angle.width)
        assertEquals(960, angle.height)
    }

    @Test
    fun scalingKeepsWholeMacroblocks() {
        val fit = fitted(Frame(1000, 700), 640, 480)

        assertEquals(0, fit.width % 16)
        assertEquals(0, fit.height % 16)
    }

    private fun only(view: CameraView): Tile {
        val tiles = tilesOf(view)
        assertEquals(1, tiles.size)
        return tiles[0]
    }
}
