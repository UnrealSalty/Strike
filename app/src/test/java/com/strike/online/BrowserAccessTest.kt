package com.strike.online

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class BrowserAccessTest {
    @get:Rule val directory = TemporaryFolder()
    private var nowMs = 1_800_000_000_000L
    private var monotonicMs = 1_000L

    @Test fun codeAndBrowserSessionSurviveAppRestart() {
        val file = File(directory.root, "access.json")
        val access = access(file)
        val code = access.code()!!
        assertTrue(code.matches(Regex("[0-9A-HJKMNP-TV-Z]{4}(-[0-9A-HJKMNP-TV-Z]{4}){2}")))
        val token = access.login(code).token!!
        val reloaded = access(file)
        assertEquals(code, reloaded.code())
        assertTrue(reloaded.allows(token))
    }

    @Test fun codeAcceptsLowercaseAndAsciiGrouping() {
        val access = access()
        val code = access.code()!!.lowercase().replace("-", " \t")
        assertTrue(access.allows(access.login("\n$code\r").token))
        assertTrue(access.allows(access.login(access.code()!!.replace("-", "")).token))
    }

    @Test fun anExistingFourGroupCodeAndSessionSurviveUntilRegenerated() {
        val file = File(directory.root, "access.json")
        val oldCode = "0123456789ABCDEF"
        file.writeText(JSONObject().put("version", 1).put("code", oldCode)
            .put("key", Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() }))
            .toString())
        val access = access(file)
        assertEquals("0123-4567-89AB-CDEF", access.code())
        val token = access.login(access.code()!!).token!!
        assertTrue(access(file).allows(token))
        assertNull(access.login(oldCode.take(12)).token)
        val shorter = access.regenerate()
        assertEquals(14, shorter.length)
        val reloaded = access(file)
        assertEquals(shorter, reloaded.code())
        assertFalse(reloaded.allows(token))
        assertNull(reloaded.login(oldCode).token)
        assertNotNull(reloaded.login(shorter).token)
    }

    @Test fun extendingAShortCodeCannotAuthenticate() {
        val access = access()
        assertNull(access.login(access.code()!! + "-ABCD").token)
    }

    @Test fun wrongCodesCannotCreateOrServeAsBrowserSessions() {
        val access = access()
        val code = access.code()!!
        val changed = (if (code.first() == '0') "1" else "0") + code.drop(1)
        assertNull(access.login(changed).token)
        assertNull(access.login("").token)
        assertNull(access.login("a".repeat(100_000)).token)
        assertFalse(access.allows(code))
        assertFalse(access.allows(null))
    }

    @Test fun fiveFailedAttemptsBlockEvenTheCorrectCodeUntilTheWaitEnds() {
        val access = access()
        repeat(4) { assertEquals(0, access.login("wrong").retryAfterSeconds) }
        assertEquals(30, access.login("wrong").retryAfterSeconds)
        assertEquals(30, access.login(access.code()!!).retryAfterSeconds)
        nowMs += 86_400_000
        assertEquals(30, access.login(access.code()!!).retryAfterSeconds)
        monotonicMs += 29_001
        assertEquals(1, access.login(access.code()!!).retryAfterSeconds)
        monotonicMs += 999
        assertNotNull(access.login(access.code()!!).token)
        repeat(4) { assertEquals(0, access.login("wrong").retryAfterSeconds) }
        assertEquals(30, access.login("wrong").retryAfterSeconds)
    }

    @Test fun repeatedBatchesIncreaseTheWaitAndCapAtFiveMinutes() {
        val access = access()
        for (wait in listOf(30, 60, 120, 240, 300, 300)) {
            repeat(4) { assertNull(access.login("wrong").token) }
            assertEquals(wait, access.login("wrong").retryAfterSeconds)
            monotonicMs += wait * 1000L
        }
        monotonicMs += 600_000
        repeat(4) { assertEquals(0, access.login("wrong").retryAfterSeconds) }
        assertEquals(30, access.login("wrong").retryAfterSeconds)
    }

    @Test fun simultaneousRequestsShareTheSameAttemptLimit() {
        val access = access()
        val workers = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val attempts = (1..20).map { workers.submit<AccessLogin> {
                assertTrue(start.await(2, TimeUnit.SECONDS))
                access.login("wrong")
            } }
            start.countDown()
            val replies = attempts.map { it.get(2, TimeUnit.SECONDS) }
            assertEquals(4, replies.count { it.retryAfterSeconds == 0 })
            assertEquals(16, replies.count { it.retryAfterSeconds == 30 })
        } finally {
            workers.shutdownNow()
        }
    }

    @Test fun rememberedSessionExpiresAfterNinetyDays() {
        val access = access()
        val token = access.login(access.code()!!).token!!
        nowMs += BrowserAccess.SESSION_SECONDS * 1000 - 1
        assertTrue(access.allows(token))
        nowMs++
        assertFalse(access.allows(token))
    }

    @Test fun futureAndModifiedSessionsAreRejected() {
        val access = access()
        val token = access.login(access.code()!!).token!!
        nowMs -= 1_000
        assertFalse(access.allows(token))
        nowMs += 1_000
        val parts = token.split('.').toMutableList()
        parts[2] = (parts[2].toLong() + 60).toString()
        assertFalse(access.allows(parts.joinToString(".")))
        for (malformed in listOf("", token + ".", token + "=", token.replace('.', '/'),
            token.dropLast(1), "0" + token.drop(1), " "+ token, "x".repeat(100_000))) {
            assertFalse(access.allows(malformed))
        }
        val changedNonce = token.split('.').toMutableList()
        changedNonce[3] = (if (changedNonce[3][0] == 'A') "B" else "A") + changedNonce[3].drop(1)
        assertFalse(access.allows(changedNonce.joinToString(".")))
        val changedSignature = token.split('.').toMutableList()
        changedSignature[4] = (if (changedSignature[4][0] == 'A') "B" else "A") + changedSignature[4].drop(1)
        assertFalse(access.allows(changedSignature.joinToString(".")))
    }

    @Test fun eachLoginGetsADifferentRememberedSession() {
        val access = access()
        val first = access.login(access.code()!!).token!!
        val second = access.login(access.code()!!).token!!
        assertNotEquals(first, second)
        assertTrue(access.allows(first))
        assertTrue(access.allows(second))
        assertFalse(access().allows(first))
    }

    @Test fun regenerationRevokesEveryBrowserAndTheOldCodeAcrossRestart() {
        val file = File(directory.root, "access.json")
        val access = access(file)
        val oldCode = access.code()!!
        val sessions = (1..3).map { access.login(oldCode).token!! }
        val code = access.regenerate()
        assertNotEquals(oldCode, code)
        assertNull(access.login(oldCode).token)
        sessions.forEach { assertFalse(access.allows(it)) }
        val reloaded = access(file)
        assertEquals(code, reloaded.code())
        sessions.forEach { assertFalse(reloaded.allows(it)) }
        assertTrue(reloaded.allows(reloaded.login(code).token))
    }

    @Test fun unreadableCredentialsStayClosedUntilExplicitRegeneration() {
        val file = File(directory.root, "access.json")
        for (contents in listOf("{broken", "{}", "x".repeat(2048),
            JSONObject().put("version", 1).put("code", "A".repeat(16)).put("key", "").toString(),
            JSONObject().put("version", 2).put("code", "A".repeat(16))
                .put("key", Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32))).toString())) {
            file.writeText(contents)
            val access = access(file)
            assertFalse(access.isReady())
            assertNull(access.code())
            assertTrue(access.login("A".repeat(16)).unavailable)
            assertFalse(access.allows("anything"))
            assertEquals(contents, file.readText())
            val code = access.regenerate()
            assertTrue(access.allows(access.login(code).token))
        }
    }

    @Test fun failedRegenerationKeepsTheExistingCodeAndBrowserSessions() {
        val file = File(directory.root, "access.json")
        val access = access(file)
        val code = access.code()!!
        val token = access.login(code).token!!
        val saved = file.readText()
        val blocked = File(directory.root, "access.json.tmp")
        assertTrue(blocked.mkdir())
        File(blocked, "keep").writeText("occupied")
        try {
            access.regenerate()
            fail("Regeneration should fail when the pending file cannot be written")
        } catch (e: IOException) {
            assertEquals(saved, file.readText())
            assertEquals(code, access.code())
            assertTrue(access.allows(token))
            assertTrue(access(file).allows(token))
        }
    }

    @Test fun initialSaveFailureCannotEnableBrowserAccess() {
        val parent = File(directory.root, "occupied")
        parent.writeText("not a directory")
        val access = access(File(parent, "access.json"))
        assertFalse(access.isReady())
        assertTrue(access.login("A".repeat(16)).unavailable)
    }

    private fun access(file: File = File(directory.newFolder(), "access.json")) =
        BrowserAccess(file, { nowMs }, { monotonicMs })
}
