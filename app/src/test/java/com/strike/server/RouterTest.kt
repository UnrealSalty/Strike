package com.strike.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class RouterTest {

    @Test
    fun rootServesTheDashboard() {
        assertEquals("web/index.html", resolveAssetPath("/"))
        assertEquals("web/index.html", resolveAssetPath("/index.html"))
    }

    @Test
    fun pagesWorkWithCleanPathsAndExistingBookmarks() {
        for (page in listOf("live", "recordings", "surveillance", "daemons", "online", "settings", "access", "lock")) {
            assertEquals("web/$page.html", resolveAssetPath("/$page"))
            assertEquals("web/$page.html", resolveAssetPath("/$page.html"))
        }
    }

    @Test
    fun pageRoutesDoNotCaptureStreamsApisOrUnknownPaths() {
        for (path in listOf(LIVE_STREAM_PATH, "/api/online", "/clips/drive.mp4",
                "/missing", "/online.html/extra", "/online.html.html", "//online", "online")) {
            assertNull(path, pagePath(path))
        }
    }

    @Test
    fun scriptsAreCachedUntilTheInstallChanges() {
        val first = assetResponse("web/js/strike.js", "\"7-100\"", null) { "code".toByteArray() }
        assertEquals(200, first.status)
        assertEquals("\"7-100\"", first.headers["ETag"])
        assertEquals("no-cache", first.headers["Cache-Control"])

        val again = assetResponse("web/js/strike.js", "\"7-100\"", "\"7-100\"") { "code".toByteArray() }
        assertEquals(304, again.status)
        assertEquals(0, again.body.size)

        val updated = assetResponse("web/js/strike.js", "\"8-200\"", "\"7-100\"") { "new".toByteArray() }
        assertEquals(200, updated.status)
        assertEquals("new", String(updated.body))
    }

    @Test
    fun pagesAreNeverCached() {
        val page = assetResponse("web/index.html", "\"7-100\"", "\"7-100\"") { "<html>".toByteArray() }

        assertEquals(200, page.status)
        assertNull(page.headers["ETag"])
        assertTrue(headerBlock(page).contains("Cache-Control: no-store"))
    }

    @Test
    fun aMissingFileIsNotFoundEvenWithATag() {
        val missing = assetResponse("web/js/gone.js", "\"7-100\"", "\"7-100\"") { throw IOException() }

        assertEquals(404, missing.status)
    }

    @Test
    fun faviconIsServedAsAPng() {
        assertEquals("web/favicon.png", resolveAssetPath(FAVICON_PATH))
        assertEquals("image/png", contentTypeFor("web/favicon.png"))
    }

    @Test
    fun assetsAreScopedToTheWebFolder() {
        assertEquals("web/css/strike.css", resolveAssetPath("/css/strike.css"))
    }

    @Test
    fun parentTraversalIsRefused() {
        assertNull(resolveAssetPath("/../AndroidManifest.xml"))
        assertNull(resolveAssetPath("/css/../../local.properties"))
    }

    @Test
    fun backslashAndDoubleSlashAreRefused() {
        assertNull(resolveAssetPath("/css\\strike.css"))
        assertNull(resolveAssetPath("//css/strike.css"))
    }

    @Test
    fun relativePathsAreRefused() {
        assertNull(resolveAssetPath("css/strike.css"))
    }

    @Test
    fun textTypesCarryACharset() {
        assertEquals("text/html; charset=utf-8", contentTypeFor("web/index.html"))
        assertEquals("text/css; charset=utf-8", contentTypeFor("web/css/strike.css"))
        assertEquals("application/javascript; charset=utf-8", contentTypeFor("web/js/core.js"))
    }

    @Test
    fun unknownTypesFallBackToBytes() {
        assertEquals("application/octet-stream", contentTypeFor("web/drive.mp4"))
    }
}
