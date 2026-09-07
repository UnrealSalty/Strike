package com.strike.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouterTest {

    @Test
    fun rootServesTheDashboard() {
        assertEquals("web/index.html", resolveAssetPath("/"))
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
