package com.strike.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouterTest {

    @Test
    fun rootServesTheDashboard() {
        assertEquals("web/index.html", resolveAssetPath("/"))
        assertEquals("web/index.html", resolveAssetPath("/index.html"))
    }

    @Test
    fun pagesWorkWithCleanPathsAndExistingBookmarks() {
        for (page in listOf("live", "recordings", "surveillance", "daemons", "online", "access", "lock")) {
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
