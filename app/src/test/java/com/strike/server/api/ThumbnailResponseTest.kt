package com.strike.server.api

import com.strike.recording.Thumb
import com.strike.recording.ThumbResult
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailResponseTest {
    @Test
    fun pendingAndSaturatedDecodersTellClientsToRetryWithoutAnImage() {
        for ((state, status) in listOf(ThumbResult.Pending to 202, ThumbResult.Busy to 503)) {
            val response = thumbnailResponse(state)
            assertEquals(status, response.status)
            assertEquals("1", response.headers["Retry-After"])
            assertTrue(JSONObject(String(response.body)).getBoolean("pending"))
            assertFalse(response.contentType.startsWith("image/"))
        }
    }

    @Test
    fun completedThumbnailIncludesItsActualDurationAndCodec() {
        val jpeg = byteArrayOf(1, 2, 3)
        val response = thumbnailResponse(ThumbResult.Ready(Thumb(jpeg, 6_020L, "h265")))
        assertEquals(200, response.status)
        assertEquals("image/jpeg", response.contentType)
        assertArrayEquals(jpeg, response.body)
        assertEquals("6020", response.headers[DURATION_HEADER])
        assertEquals("h265", response.headers[CODEC_HEADER])
        assertFalse(response.headers.containsKey("Retry-After"))
    }

    @Test
    fun aDetectionStillCanBeServedWithoutInventingClipMetadata() {
        val response = thumbnailResponse(ThumbResult.Ready(Thumb(byteArrayOf(4, 5), 0L, null)))
        assertEquals(200, response.status)
        assertEquals("0", response.headers[DURATION_HEADER])
        assertFalse(response.headers.containsKey(CODEC_HEADER))
    }

    @Test
    fun anUnreadableClipIsTerminalForTheCurrentAttempt() {
        val response = thumbnailResponse(ThumbResult.Missing)
        assertEquals(404, response.status)
        assertFalse(response.headers.containsKey("Retry-After"))
    }
}
