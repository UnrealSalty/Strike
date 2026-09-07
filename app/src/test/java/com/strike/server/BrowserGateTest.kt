package com.strike.server

import com.strike.online.BrowserAccess
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class BrowserGateTest {
    @get:Rule val directory = TemporaryFolder()
    private val loopback = InetAddress.getByName("127.0.0.1")

    @Test fun onlyTheAppCookieOnTheCarOriginIdentifiesTheWebView() {
        val gate = gate()
        val cookie = gate.nativeCookie().substringBefore(';')
        assertTrue(gate.isCar(loopback, "127.0.0.1:8090", cookie, false))
        assertFalse(gate.isCar(loopback, "127.0.0.1:8090", null, false))
        assertFalse(gate.isCar(loopback, "127.0.0.1:8090", cookie, true))
        assertFalse(gate.isCar(loopback, "car.example.com", cookie, false))
        assertFalse(gate.isCar(loopback, "127.0.0.1:8091", cookie, false))
        assertFalse(gate.isCar(loopback, "127.0.0.1:8090.evil.test", cookie, false))
        assertFalse(gate.isCar(InetAddress.getByName("192.168.1.2"), "127.0.0.1:8090", cookie, false))
        assertFalse(gate.isCar(loopback, "127.0.0.1:8090", gate().nativeCookie(), false))
    }

    @Test fun browserSessionsAreRememberedWithoutExposingThemToScripts() {
        val secure = browserCookie("session", true).split("; ")
        assertTrue(secure.contains("$ACCESS_COOKIE=session"))
        assertTrue(secure.contains("Path=/"))
        assertTrue(secure.contains("HttpOnly"))
        assertTrue(secure.contains("SameSite=Lax"))
        assertTrue(secure.contains("Max-Age=${BrowserAccess.SESSION_SECONDS}"))
        assertTrue(secure.contains("Secure"))
        assertFalse(secure.any { it.startsWith("Domain=") })
        assertFalse(browserCookie("session", false).split("; ").contains("Secure"))
    }

    @Test fun onlyAccessScreenAssetsArePublic() {
        for (path in listOf(ACCESS_PAGE, "/access.html", FAVICON_PATH, "/css/strike.css", "/css/tokens.css",
                "/js/access.js", "/img/wordmark.webp")) {
            assertTrue(path, browserPublicAsset(path))
        }
        for (path in listOf("/js/core.js", "/js/access.js/../online.js", "/css/../online.html",
                "/img/event.webp", "/access.html/extra", "/%61ccess.html", "/api/status")) {
            assertFalse(path, browserPublicAsset(path))
        }
    }

    @Test fun pagesAskForTheBrowserCodeWithoutShowingTheCarPin() {
        val gate = gate()
        Socket().use { socket ->
            for (path in listOf("/", "/index.html", "/live", "/recordings", "/surveillance",
                    "/daemons", "/online", "/online.html", LOCK_PAGE, "/lock.html")) {
                val response = guard(gate, socket, "GET", path)!!
                assertEquals(path, 200, response.status)
                assertEquals(path, ACCESS_PAGE, String(response.body))
            }
        }
    }

    @Test fun theFaviconLoadsBeforeBrowserSignIn() {
        Socket().use { socket ->
            val response = guard(gate(), socket, "GET", FAVICON_PATH)!!
            assertEquals(200, response.status)
            assertEquals(FAVICON_PATH, String(response.body))
        }
    }

    @Test fun linksToFootageAndLiveCannotBypassBrowserAuthentication() {
        val gate = gate()
        val paths = listOf("/api/status", "/api/online", "/clips/drive.mp4", "/events/event.mp4",
            "/thumbs/drive.jpg", "/heroes/event.jpg", LIVE_STREAM_PATH)
        val pinCookie = "$SESSION_COOKIE=${gate.access.login(gate.access.code()!!).token}"
        Socket().use { socket ->
            for (path in paths) {
                assertEquals(path, 401, guard(gate, socket, "GET", path)!!.status)
                assertEquals(path, 401, guard(gate, socket, "GET", path, cookie = pinCookie)!!.status)
            }
            assertEquals(401, guard(gate, socket, "POST", "/css/strike.css")!!.status)
        }
    }

    @Test fun rememberedBrowserSessionsCanReadAndControlStrike() {
        val gate = gate()
        val cookie = "$ACCESS_COOKIE=${gate.access.login(gate.access.code()!!).token}"
        Socket().use { socket ->
            for (path in listOf("/live", "/recordings", "/surveillance", "/daemons", "/online",
                    "/online.html", "/api/status", "/api/online", "/clips/drive.mp4", "/events/event.mp4",
                    "/thumbs/drive.jpg", "/heroes/event.jpg", LIVE_STREAM_PATH)) {
                assertNull(path, guard(gate, socket, "GET", path, cookie = cookie))
            }
            assertNull(guard(gate, socket, "POST", "/api/recording/settings", cookie = cookie))
            for (path in listOf(LOCK_PAGE, "/lock.html")) {
                val lock = guard(gate, socket, "GET", path, cookie = cookie)!!
                assertEquals(302, lock.status)
                assertEquals("/", lock.headers["Location"])
            }
            gate.leave(socket)
        }
    }

    @Test fun browsersCannotInspectUnlockOrChangeTheCarPin() {
        val gate = gate()
        val cookie = "$ACCESS_COOKIE=${gate.access.login(gate.access.code()!!).token}"
        Socket().use { socket ->
            for ((method, path) in listOf("GET" to SECURITY_API, "POST" to SECURITY_API,
                    "POST" to UNLOCK_API, "GET" to "$SECURITY_API/")) {
                assertEquals(403, guard(gate, socket, method, path)!!.status)
                assertEquals(403, guard(gate, socket, method, path, cookie = cookie)!!.status)
            }
        }
    }

    @Test fun loginExchangesTheCodeForAPersistentCookie() {
        val gate = gate()
        Socket().use { socket ->
            val anonymous = guard(gate, socket, "GET", ACCESS_API)!!
            assertFalse(JSONObject(String(anonymous.body)).getBoolean("authenticated"))
            val opened = guard(gate, socket, "POST", ACCESS_LOGIN, "code=${gate.access.code()}")!!
            assertEquals(200, opened.status)
            assertEquals(setOf("ok"), JSONObject(String(opened.body)).keys().asSequence().toSet())
            val cookie = opened.headers["Set-Cookie"]!!.substringBefore(';')
            val authenticated = guard(gate, socket, "GET", ACCESS_API, cookie = cookie)!!
            assertTrue(JSONObject(String(authenticated.body)).getBoolean("authenticated"))
            assertNull(guard(gate, socket, "GET", "/api/status", cookie = cookie))
            gate.leave(socket)
        }
    }

    @Test fun wrongCodesAreThrottledWithoutReturningASession() {
        val gate = gate()
        Socket().use { socket ->
            repeat(4) {
                val wrong = guard(gate, socket, "POST", ACCESS_LOGIN, "code=wrong")!!
                assertEquals(401, wrong.status)
                assertFalse(wrong.headers.containsKey("Set-Cookie"))
            }
            val blocked = guard(gate, socket, "POST", ACCESS_LOGIN, "code=wrong")!!
            assertEquals(429, blocked.status)
            assertEquals("30", blocked.headers["Retry-After"])
            assertEquals(30, JSONObject(String(blocked.body)).getInt("retryAfterSeconds"))
        }
    }

    @Test fun unreadableCredentialsKeepBrowserAccessClosed() {
        val file = directory.newFile("broken.json")
        file.writeText("broken")
        val gate = BrowserGate(BrowserAccess(file))
        Socket().use { socket ->
            assertEquals(503, guard(gate, socket, "POST", ACCESS_LOGIN, "code=wrong")!!.status)
            assertEquals(401, guard(gate, socket, "GET", "/api/status")!!.status)
        }
    }

    @Test fun regeneratingClosesBrowserStreamsAndRejectsTheOldSession() {
        val gate = gate()
        val token = gate.access.login(gate.access.code()!!).token!!
        pair { viewer, server ->
            assertTrue(gate.enter(token, server))
            gate.regenerate()
            assertTrue(server.isClosed)
            assertEquals(-1, viewer.getInputStream().read())
            Socket().use { assertFalse(gate.enter(token, it)) }
        }
    }

    @Test fun regeneratingDoesNotCloseCarConnectionsOrFinishedRequests() {
        val gate = gate()
        val token = gate.access.login(gate.access.code()!!).token!!
        pair { viewer, server ->
            assertTrue(gate.enter(token, server))
            gate.leave(server)
            gate.regenerate()
            server.getOutputStream().write(42)
            assertEquals(42, viewer.getInputStream().read())
        }
        pair { viewer, server ->
            assertTrue(gate.isCar(loopback, "127.0.0.1:8090", gate.nativeCookie(), false))
            gate.regenerate()
            server.getOutputStream().write(43)
            assertEquals(43, viewer.getInputStream().read())
        }
    }

    @Test fun failedRegenerationLeavesCurrentBrowserConnectionsWorking() {
        val file = File(directory.root, "access.json")
        val gate = BrowserGate(BrowserAccess(file))
        val token = gate.access.login(gate.access.code()!!).token!!
        val pending = File(directory.root, "access.json.tmp")
        assertTrue(pending.mkdir())
        File(pending, "keep").writeText("occupied")
        pair { viewer, server ->
            assertTrue(gate.enter(token, server))
            try {
                gate.regenerate()
                fail("The pending file cannot be replaced")
            } catch (e: IOException) {
                assertTrue(gate.access.allows(token))
                server.getOutputStream().write(44)
                assertEquals(44, viewer.getInputStream().read())
            }
            gate.leave(server)
        }
    }

    @Test fun aConnectionOpeningDuringRegenerationCannotSurviveWithTheOldSession() {
        val gate = gate()
        val token = gate.access.login(gate.access.code()!!).token!!
        val workers = Executors.newFixedThreadPool(2)
        try {
            pair { _, server ->
                val start = CountDownLatch(1)
                val opened = workers.submit<Boolean> { start.await(); gate.enter(token, server) }
                val rotated = workers.submit { start.await(); gate.regenerate() }
                start.countDown()
                rotated.get(3, TimeUnit.SECONDS)
                assertTrue(!opened.get(3, TimeUnit.SECONDS) || server.isClosed)
                assertFalse(gate.access.allows(token))
            }
        } finally {
            workers.shutdownNow()
        }
    }

    private fun gate(): BrowserGate = BrowserGate(BrowserAccess(File(directory.newFolder(), "access.json")))

    private fun guard(gate: BrowserGate, socket: Socket, method: String, path: String,
                      body: String = "", cookie: String? = null): Response? =
        gate.guard(method, path, body, cookie, true, socket) { Response(200, TEXT, it.toByteArray()) }

    private fun pair(block: (Socket, Socket) -> Unit) {
        ServerSocket(0, 1, loopback).use { listener ->
            Socket(loopback, listener.localPort).use { viewer ->
                viewer.soTimeout = 1_000
                listener.accept().use { server -> block(viewer, server) }
            }
        }
    }
}
