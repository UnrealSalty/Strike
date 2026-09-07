package com.strike.camera

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveStreamerTest {

    @Test
    fun noSpsMeansNoConfig() {
        assertNull(annexB(null, byteArrayOf(8, 1)))
        assertNull(annexB(byteArrayOf(), byteArrayOf(8, 1)))
    }

    @Test
    fun aRawSpsGetsAStartCode() {
        val out = annexB(byteArrayOf(0x67, 0x42), null)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x67, 0x42), out)
    }

    @Test
    fun spsAndPpsAreBothOnTheWire() {
        val out = annexB(byteArrayOf(0x67, 0x42), byteArrayOf(0x68, 0xCE.toByte()))!!
        assertEquals(12, out.size)
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0, 0, 0, 1, 0x68, 0xCE.toByte()),
            out
        )
    }

    @Test
    fun anAnnexBSpsIsNotPrefixedAgain() {
        val sps = byteArrayOf(0, 0, 0, 1, 0x67, 0x42)
        assertArrayEquals(sps, annexB(sps, null))
    }
}
