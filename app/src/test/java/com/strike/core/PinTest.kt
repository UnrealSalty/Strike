package com.strike.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CountDownLatch
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.io.path.createTempDirectory

class PinTest {

    @Test
    fun fourToEightDigitsAreTaken() {
        assertTrue(Pin.acceptable("1234"))
        assertTrue(Pin.acceptable("12345678"))
        assertFalse(Pin.acceptable("123"))
        assertFalse(Pin.acceptable("123456789"))
        assertFalse(Pin.acceptable("12ab"))
        assertFalse(Pin.acceptable(""))
    }

    @Test
    fun aSetPinOpensOnlyOnTheSameDigits() {
        val pin = fresh()
        assertFalse(pin.isSet())
        assertEquals(PinSet.OK, pin.set("2580"))
        assertTrue(pin.isSet())
        assertEquals(PinCheck.OK, pin.check("2580"))
        assertEquals(PinCheck.WRONG, pin.check("2581"))
    }

    @Test
    fun lettersAreRefusedAndLeaveTheStoreEmpty() {
        val pin = fresh()
        assertEquals(PinSet.BAD_PIN, pin.set("pass"))
        assertFalse(pin.isSet())
    }

    @Test
    fun aNewPinReplacesTheOldOne() {
        val pin = fresh()
        pin.set("1111")
        pin.set("9999")
        assertEquals(PinCheck.WRONG, pin.check("1111"))
        assertEquals(PinCheck.OK, pin.check("9999"))
    }

    @Test
    fun clearDropsTheGate() {
        val pin = fresh()
        pin.set("1234")
        pin.clear()
        assertFalse(pin.isSet())
        assertEquals(PinCheck.UNSET, pin.check("1234"))
    }

    @Test
    fun theFifthWrongTryLocksOut() {
        val pin = fresh()
        pin.set("1234")
        repeat(4) { assertEquals(PinCheck.WRONG, pin.check("0000")) }
        assertEquals(PinCheck.LOCKED, pin.check("0000"))
        assertEquals(PinCheck.LOCKED, pin.check("1234"))
        assertTrue(pin.lockoutMs() > 0L)
    }

    @Test
    fun guessesArrivingTogetherAllCountTowardTheLockout() {
        val pin = fresh()
        pin.set("1234")
        val start = CountDownLatch(1)
        val guessing = (1..8).map {
            Thread {
                start.await()
                pin.check("0000")
            }
        }
        guessing.forEach { it.start() }
        start.countDown()
        guessing.forEach { it.join() }
        assertEquals(PinCheck.LOCKED, pin.check("1234"))
    }

    @Test
    fun lockoutStepsMatchTheValetSchedule() {
        assertEquals(0L, Pin.waitAfter(4))
        assertEquals(30_000L, Pin.waitAfter(5))
        assertEquals(30_000L, Pin.waitAfter(9))
        assertEquals(5L * 60_000L, Pin.waitAfter(10))
        assertEquals(10L * 60_000L, Pin.waitAfter(15))
    }

    @Test
    fun anOlderHashIsRewrittenOnTheNextOpen() {
        val dir = createTempDirectory("strike-pin").toFile()
        val store = File(dir, "pin.json")
        val salt = ByteArray(32)
        SecureRandom().nextBytes(salt)
        val spec = PBEKeySpec("1234".toCharArray(), salt, 50_000, 256)
        val hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        val held = JSONObject()
        held.put("salt", Base64.getUrlEncoder().withoutPadding().encodeToString(salt))
        held.put("hash", Base64.getUrlEncoder().withoutPadding().encodeToString(hash))
        held.put("rounds", 50_000)
        held.put("fails", 0)
        held.put("lockUntilMs", 0L)
        store.writeText(held.toString())

        val pin = Pin(store, File(dir, "reset"))
        assertEquals(PinCheck.OK, pin.check("1234"))
        assertTrue(JSONObject(store.readText()).getInt("rounds") != 50_000)
        assertEquals(PinCheck.OK, pin.check("1234"))
    }

    @Test
    fun aRecoveryFlagClearsThePinOnce() {
        val dir = createTempDirectory("strike-pin").toFile()
        val flag = File(dir, "reset")
        val pin = Pin(File(dir, "pin.json"), flag)
        pin.set("1234")
        flag.writeText("")
        assertFalse(pin.isSet())
        pin.set("5678")
        assertTrue(pin.isSet())
        assertEquals(PinCheck.OK, pin.check("5678"))
    }

    private fun fresh(): Pin {
        val dir = createTempDirectory("strike-pin").toFile()
        return Pin(File(dir, "pin.json"), File(dir, "reset"))
    }
}
