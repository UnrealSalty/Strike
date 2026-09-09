package com.strike.core

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

private const val KEPT = 200
private const val BATCH_BYTES = 32_768

class LogLine(val atMs: Long, val level: String, val tag: String, val message: String,
              internal val sequence: Long = 0L)

// Keep a bounded in-memory log for the Daemons page alongside logcat.
object Logs {

    private val kept = ArrayDeque<LogLine>()
    private var sequence = 0L
    private val received = LinkedHashMap<String, Long>()

    fun d(tag: String, message: String) {
        Log.d("Strike/$tag", message)
        keep("debug", tag, message)
    }

    fun w(tag: String, message: String, error: Throwable? = null) {
        Log.w("Strike/$tag", message, error)
        keep("warn", tag, message)
    }

    fun e(tag: String, message: String, error: Throwable? = null) {
        Log.e("Strike/$tag", message, error)
        keep("error", tag, message)
    }

    fun recent(): List<LogLine> = synchronized(kept) { kept.toList() }

    internal fun batch(after: Long): JSONObject? = synchronized(kept) {
        val lines = JSONArray()
        var bytes = 64
        var through = after
        for (line in kept) {
            if (line.sequence <= after) continue
            val row = JSONObject().put("id", line.sequence).put("atMs", line.atMs)
                .put("level", line.level).put("tag", line.tag.take(128))
                .put("message", line.message.take(4096))
            val length = row.toString().toByteArray(Charsets.UTF_8).size + 1
            if (bytes + length > BATCH_BYTES) break
            bytes += length
            lines.put(row)
            through = line.sequence
        }
        if (lines.length() == 0) null else JSONObject().put("through", through).put("lines", lines)
    }

    internal fun receive(source: String, batch: JSONObject): Long = synchronized(kept) {
        val accepted = received[source] ?: 0L
        val through = batch.getLong("through")
        val lines = batch.getJSONArray("lines")
        require(lines.length() <= KEPT)
        for (i in 0 until lines.length()) {
            val row = lines.getJSONObject(i)
            val id = row.getLong("id")
            if (id <= accepted || id > through) continue
            append(row.getLong("atMs"), row.getString("level"), row.getString("tag"), row.getString("message"))
        }
        val next = maxOf(accepted, through)
        received[source] = next
        if (received.size > 8) received.remove(received.keys.first())
        next
    }

    private fun keep(level: String, tag: String, message: String) {
        synchronized(kept) {
            append(System.currentTimeMillis(), level, tag, message)
        }
    }

    private fun append(atMs: Long, level: String, tag: String, message: String) {
        if (kept.size == KEPT) kept.removeFirst()
        kept.addLast(LogLine(atMs, level, tag, message, ++sequence))
    }
}
