package com.strike.online

import com.strike.core.Logs
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class AccessLogin(val token: String? = null, val retryAfterSeconds: Int = 0, val unavailable: Boolean = false)

class BrowserAccess(
    private val file: File,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    private class Credentials(val code: String, val key: ByteArray)

    private var credentials: Credentials? = null
    private var failures = 0
    private var penalties = 0
    private var blockedUntilMs = Long.MIN_VALUE
    private var lastFailureMs: Long? = null

    init {
        try {
            if (Files.notExists(file.toPath())) regenerate() else credentials = read()
        } catch (e: IOException) {
            Logs.w("Online", "Cannot read or save browser access; regenerate the code in the car")
        } catch (e: SecurityException) {
            Logs.w("Online", "Cannot read or save browser access; regenerate the code in the car")
        }
    }

    @Synchronized fun code(): String? = credentials?.code?.chunked(4)?.joinToString("-")

    @Synchronized fun isReady(): Boolean = credentials != null

    @Synchronized fun regenerate(): String {
        val replacement = Credentials(buildString { repeat(CODE_LENGTH) { append(ALPHABET[random.nextInt(32)]) } },
            ByteArray(32).also(random::nextBytes))
        save(replacement)
        credentials = replacement
        resetAttempts()
        return code()!!
    }

    @Synchronized fun login(given: String): AccessLogin {
        val held = credentials ?: return AccessLogin(unavailable = true)
        val now = monotonicMs()
        if (now < blockedUntilMs) {
            return AccessLogin(retryAfterSeconds = ((blockedUntilMs - now + 999) / 1000).toInt())
        }
        if (lastFailureMs?.let { now - it >= 600_000 } == true) resetAttempts()
        val normalized = normalize(given)
        if (normalized == null || !MessageDigest.isEqual(held.code.toByteArray(Charsets.US_ASCII),
                normalized.toByteArray(Charsets.US_ASCII))) {
            lastFailureMs = now
            failures++
            if (failures < 5) return AccessLogin()
            failures = 0
            val waitSeconds = minOf(300, 30 shl penalties)
            penalties = minOf(4, penalties + 1)
            blockedUntilMs = now + waitSeconds * 1000L
            return AccessLogin(retryAfterSeconds = waitSeconds)
        }
        resetAttempts()
        val issuedSeconds = nowMs() / 1000
        val nonce = encode(ByteArray(24).also(random::nextBytes))
        val payload = "1.$issuedSeconds.${issuedSeconds + SESSION_SECONDS}.$nonce"
        return AccessLogin(token = "$payload.${encode(sign(held.key, payload))}")
    }

    @Synchronized fun allows(token: String?): Boolean {
        val held = credentials ?: return false
        if (token == null || !TOKEN.matches(token)) return false
        val parts = token.split('.')
        val issuedSeconds = parts[1].toLongOrNull() ?: return false
        val expiresSeconds = parts[2].toLongOrNull() ?: return false
        val nowSeconds = nowMs() / 1000
        if (issuedSeconds > nowSeconds || expiresSeconds <= nowSeconds ||
            expiresSeconds - issuedSeconds != SESSION_SECONDS) return false
        val signature = decode(parts[4]) ?: return false
        return MessageDigest.isEqual(sign(held.key, token.substringBeforeLast('.')), signature)
    }

    private fun read(): Credentials {
        if (!file.isFile || file.length() !in 1..1024) throw IOException("Invalid browser access file")
        try {
            val saved = JSONObject(file.readText())
            if (saved.getInt("version") != 1) throw IOException("Invalid browser access version")
            val code = saved.getString("code")
            val key = decode(saved.getString("key"))
            if (code.length != CODE_LENGTH && code.length != 16 || code.any { it !in ALPHABET } || key?.size != 32) {
                throw IOException("Invalid browser access credentials")
            }
            return Credentials(code, key)
        } catch (e: JSONException) {
            throw IOException("Invalid browser access file", e)
        }
    }

    private fun save(replacement: Credentials) {
        val parent = file.parentFile ?: throw IOException("Missing browser access directory")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create browser access directory")
        val pending = File(parent, file.name + ".tmp")
        val saved = JSONObject().put("version", 1).put("code", replacement.code)
            .put("key", encode(replacement.key)).toString().toByteArray(Charsets.UTF_8)
        try {
            FileOutputStream(pending).use {
                it.write(saved)
                it.fd.sync()
            }
            Files.move(pending.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE)
        } finally {
            if (pending.isFile) pending.delete()
        }
    }

    private fun resetAttempts() {
        failures = 0
        penalties = 0
        blockedUntilMs = Long.MIN_VALUE
        lastFailureMs = null
    }

    companion object {
        const val SESSION_SECONDS = 90L * 24 * 60 * 60
        private const val CODE_LENGTH = 12
        private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        private val random = SecureRandom()
        private val TOKEN = Regex("1\\.[0-9]{1,12}\\.[0-9]{1,12}\\.[A-Za-z0-9_-]{32}\\.[A-Za-z0-9_-]{43}")

        private fun normalize(given: String): String? {
            if (given.length !in CODE_LENGTH..64) return null
            val normalized = buildString {
                for (character in given) {
                    if (character == '-' || character == ' ' || character == '\t' ||
                        character == '\r' || character == '\n') continue
                    append(if (character in 'a'..'z') character - 32 else character)
                }
            }
            return normalized.takeIf { (it.length == CODE_LENGTH || it.length == 16) &&
                it.all { character -> character in ALPHABET } }
        }

        private fun sign(key: ByteArray, payload: String): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(payload.toByteArray(Charsets.US_ASCII))
        }

        private fun encode(bytes: ByteArray): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        private fun decode(encoded: String): ByteArray? = try {
            Base64.getUrlDecoder().decode(encoded).takeIf { encode(it) == encoded }
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
