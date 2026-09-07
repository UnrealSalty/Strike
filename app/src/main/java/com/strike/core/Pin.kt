package com.strike.core

import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.spec.InvalidKeySpecException
import java.util.Arrays
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

private const val TAG = "Pin"
private const val SALT_BYTES = 32
private const val HASH_BYTES = 32

private const val ROUNDS = 10_000

const val PIN_MIN = 4
const val PIN_MAX = 8

enum class PinSet { OK, BAD_PIN }
enum class PinCheck { OK, WRONG, LOCKED, UNSET }

class Pin(
    private val store: File,
    private val resetFlag: File = File("/data/local/tmp/.strike_pin_reset")
) {

    // Serialize attempt counting so concurrent requests cannot bypass lockout.
    private val lock = Any()

    init {
        factory
    }

    fun isSet(): Boolean = synchronized(lock) {
        recover()
        val held = read() ?: return false
        held.optString("hash").isNotEmpty() && held.optString("salt").isNotEmpty()
    }

    fun lockoutMs(): Long = synchronized(lock) {
        val held = read() ?: return 0L
        maxOf(0L, held.optLong("lockUntilMs") - System.currentTimeMillis())
    }

    fun set(pin: String): PinSet = synchronized(lock) {
        if (!acceptable(pin)) return PinSet.BAD_PIN
        val salt = ByteArray(SALT_BYTES)
        random.nextBytes(salt)
        val hash = derive(pin, salt, ROUNDS)
        val held = read() ?: JSONObject()
        held.put("salt", encode(salt))
        held.put("hash", encode(hash))
        held.put("rounds", ROUNDS)
        held.put("fails", 0)
        held.put("lockUntilMs", 0L)
        Arrays.fill(salt, 0)
        Arrays.fill(hash, 0)
        write(held)
        Logs.d(TAG, "PIN set")
        return PinSet.OK
    }

    fun clear() = synchronized(lock) {
        val held = read() ?: JSONObject()
        held.put("salt", "")
        held.put("hash", "")
        held.put("rounds", 0)
        held.put("fails", 0)
        held.put("lockUntilMs", 0L)
        write(held)
        Logs.d(TAG, "PIN cleared")
    }

    fun check(pin: String): PinCheck = synchronized(lock) {
        val held = read() ?: return PinCheck.UNSET
        if (held.optString("hash").isEmpty() || held.optString("salt").isEmpty()) {
            return PinCheck.UNSET
        }
        val now = System.currentTimeMillis()
        if (held.optLong("lockUntilMs") > now) return PinCheck.LOCKED
        val salt = decode(held.optString("salt"))
        val expected = decode(held.optString("hash"))
        val rounds = held.optInt("rounds", ROUNDS)
        val got = derive(pin, salt, rounds)
        val match = same(expected, got)
        Arrays.fill(salt, 0)
        Arrays.fill(expected, 0)
        Arrays.fill(got, 0)
        if (match) {
            held.put("fails", 0)
            held.put("lockUntilMs", 0L)
            if (rounds != ROUNDS) {
                val fresh = ByteArray(SALT_BYTES)
                random.nextBytes(fresh)
                val hash = derive(pin, fresh, ROUNDS)
                held.put("salt", encode(fresh))
                held.put("hash", encode(hash))
                held.put("rounds", ROUNDS)
                Arrays.fill(fresh, 0)
                Arrays.fill(hash, 0)
            }
            write(held)
            return PinCheck.OK
        }
        val fails = held.optInt("fails") + 1
        val wait = waitAfter(fails)
        held.put("fails", fails)
        if (wait > 0L) held.put("lockUntilMs", now + wait)
        write(held)
        if (wait > 0L) PinCheck.LOCKED else PinCheck.WRONG
    }

    private fun recover() {
        if (!resetFlag.isFile) return
        val touched = resetFlag.lastModified()
        if (touched <= 0L) return
        val held = read() ?: JSONObject()
        if (touched <= held.optLong("recoveredAtMs")) return
        held.put("salt", "")
        held.put("hash", "")
        held.put("rounds", 0)
        held.put("fails", 0)
        held.put("lockUntilMs", 0L)
        held.put("recoveredAtMs", touched)
        write(held)
        Logs.d(TAG, "PIN cleared from the recovery flag")
    }

    private fun read(): JSONObject? {
        if (!store.isFile) return null
        return try {
            JSONObject(store.readText())
        } catch (e: JSONException) {
            Logs.w(TAG, "the PIN file is not readable json")
            null
        } catch (e: IOException) {
            Logs.w(TAG, "cannot read the PIN file")
            null
        }
    }

    // Replace the PIN file atomically to avoid partial writes.
    private fun write(held: JSONObject) {
        val parent = store.parentFile ?: return
        if (!parent.isDirectory && !parent.mkdirs()) {
            Logs.w(TAG, "cannot create the PIN directory")
            return
        }
        val temp = File(parent, store.name + ".tmp")
        try {
            temp.writeText(held.toString())
            Files.move(
                temp.toPath(),
                store.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (e: IOException) {
            temp.delete()
            Logs.w(TAG, "cannot save the PIN")
        }
    }

    companion object {
        private val random = SecureRandom()
        private val factory: SecretKeyFactory by lazy {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        }

        fun acceptable(pin: String): Boolean {
            if (pin.length < PIN_MIN || pin.length > PIN_MAX) return false
            for (i in pin.indices) {
                if (!pin[i].isDigit()) return false
            }
            return true
        }

        internal fun waitAfter(fails: Int): Long = when {
            fails < 5 -> 0L
            fails < 10 -> 30_000L
            fails < 15 -> 5L * 60_000L
            else -> 10L * 60_000L
        }

        private fun derive(pin: String, salt: ByteArray, rounds: Int): ByteArray {
            val spec = PBEKeySpec(pin.toCharArray(), salt, rounds, HASH_BYTES * 8)
            return try {
                factory.generateSecret(spec).encoded
            } catch (e: InvalidKeySpecException) {
                throw IllegalStateException("PIN hash failed", e)
            } finally {
                spec.clearPassword()
            }
        }

        private fun same(left: ByteArray, right: ByteArray): Boolean {
            if (left.size != right.size) return false
            var diff = 0
            for (i in left.indices) diff = diff or (left[i].toInt() xor right[i].toInt())
            return diff == 0
        }

        private fun encode(bytes: ByteArray): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        private fun decode(text: String): ByteArray = try {
            Base64.getUrlDecoder().decode(text)
        } catch (e: IllegalArgumentException) {
            ByteArray(0)
        }
    }
}
