package com.strike.daemon

private const val CACHE_MS = 10_000L

// Cache shell process probes across dashboard polls.
class Processes(private val shell: Shell) {

    private val lock = Any()
    private val seen = HashMap<String, Boolean>()
    private var readAtMs = 0L

    fun forget() = synchronized(lock) {
        seen.clear()
        readAtMs = 0L
    }

    /** Null when there is no shell, so the caller shows unknown rather than dead. */
    fun isRunning(pattern: String): Boolean? {
        if (!shell.isAuthorised()) return null
        synchronized(lock) {
            val now = System.currentTimeMillis()
            if (now - readAtMs > CACHE_MS) {
                seen.clear()
                readAtMs = now
            }
            seen[pattern]?.let { return it }
            val alive = shell.check("ps -A -o ARGS | grep -v grep | grep -q $pattern")
            seen[pattern] = alive
            return alive
        }
    }
}
