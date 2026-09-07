package com.strike.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.IOException
import java.io.StringReader

class HttpServerTest {

    @Test
    fun headerLinesEndBeforeTheNextLineOrBody() {
        val reader = BufferedReader(StringReader("GET / HTTP/1.1\r\nHost: car\r\n\r\ncode=1234"))
        assertEquals("GET / HTTP/1.1", boundedLine(reader))
        assertEquals("Host: car", boundedLine(reader))
        assertEquals("", boundedLine(reader))
        assertEquals("code=1234", reader.readText())
        assertNull(boundedLine(reader))
    }

    @Test(expected = IOException::class)
    fun truncatedHeadersAreNotTreatedAsComplete() {
        boundedLine(BufferedReader(StringReader("Cookie: strike_browser=partial")))
    }

    @Test(expected = IOException::class)
    fun oversizedHeadersAreRejected() {
        boundedLine(BufferedReader(StringReader("Cookie: " + "a".repeat(16_384) + "\r\n")))
    }

    @Test
    fun methodComesFromTheRequestLine() {
        assertEquals("GET", requestMethod("GET /api/status HTTP/1.1"))
    }

    @Test
    fun queryStringIsNotPartOfThePath() {
        assertEquals("/api/status", requestPath("GET /api/status?since=12 HTTP/1.1"))
    }

    @Test
    fun fragmentIsNotPartOfThePath() {
        assertEquals("/index.html", requestPath("GET /index.html#live HTTP/1.1"))
    }

    @Test
    fun malformedRequestLineHasNoPath() {
        assertNull(requestPath("GARBAGE"))
        assertNull(requestPath(""))
    }

    @Test
    fun relativeRequestTargetIsRefused() {
        assertNull(requestPath("GET index.html HTTP/1.1"))
    }

    @Test
    fun theLiveViewIsReadFromTheQuery() {
        assertEquals("rear", queryValue("GET /live/stream?view=rear HTTP/1.1", "view"))
        assertEquals("all", queryValue("GET /live/stream?a=1&view=all&b=2 HTTP/1.1", "view"))
    }

    @Test
    fun aMissingOrEmptyViewIsNoView() {
        assertNull(queryValue("GET /live/stream HTTP/1.1", "view"))
        assertNull(queryValue("GET /live/stream?view= HTTP/1.1", "view"))
        assertNull(queryValue("GET /live/stream?preview=rear HTTP/1.1", "view"))
    }

    @Test
    fun formFieldsAreReadByName() {
        assertEquals("continuous", formValue("key=recording.mode&value=continuous", "value"))
        assertEquals("recording.mode", formValue("key=recording.mode&value=continuous", "key"))
    }

    @Test
    fun formFieldsArePercentDecoded() {
        assertEquals("storage.budgetMb", formValue("key=storage%2EbudgetMb", "key"))
    }

    @Test
    fun missingFormFieldIsNull() {
        assertNull(formValue("key=recording.mode", "value"))
        assertNull(formValue("", "key"))
    }

    @Test
    fun noRangeHeaderMeansTheWholeFile() {
        assertNull(rangeOf(null, 1000))
    }

    @Test
    fun aClosedRangeIsTakenAsGiven() {
        assertEquals(0L..99L, rangeOf("bytes=0-99", 1000))
    }

    @Test
    fun anOpenEndedRangeRunsToTheLastByte() {
        assertEquals(500L..999L, rangeOf("bytes=500-", 1000))
    }

    @Test
    fun aSuffixRangeCountsBackFromTheEnd() {
        assertEquals(800L..999L, rangeOf("bytes=-200", 1000))
        assertEquals(0L..999L, rangeOf("bytes=-4000", 1000))
    }

    @Test
    fun anEndPastTheFileIsClamped() {
        assertEquals(900L..999L, rangeOf("bytes=900-5000", 1000))
    }

    @Test
    fun anUnsatisfiableRangeFallsBackToTheWholeFile() {
        assertNull(rangeOf("bytes=1000-", 1000))
        assertNull(rangeOf("bytes=90-10", 1000))
        assertNull(rangeOf("bytes=0-99", 0))
    }

    @Test
    fun multipartAndMalformedRangesAreNotAnswered() {
        assertNull(rangeOf("bytes=0-99,200-299", 1000))
        assertNull(rangeOf("bytes=abc-def", 1000))
        assertNull(rangeOf("bytes=", 1000))
        assertNull(rangeOf("items=0-99", 1000))
    }

    @Test
    fun aResponsesOwnHeadersReachTheWire() {
        val block = headerBlock(
            Response(200, "image/jpeg", ByteArray(9), headers = mapOf("X-Clip-Duration-Ms" to "119000"))
        )
        assertTrue(block.contains("\r\nX-Clip-Duration-Ms: 119000\r\n"))
        assertTrue(block.contains("\r\nContent-Length: 9\r\n"))
        assertTrue(block.endsWith("\r\n\r\n"))
    }

    @Test
    fun headersEndBeforeTheBodyBegins() {
        val block = headerBlock(Response(200, JSON, "{}".toByteArray()))
        assertEquals(block.indexOf("\r\n\r\n") + 4, block.length)
    }
}
