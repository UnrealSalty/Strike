package com.strike.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class LogsTest {
    @Test fun aLostAcknowledgementDoesNotDuplicateAppLogs() {
        val source = UUID.randomUUID().toString()
        val batch = batch(source, 1, 2)
        assertEquals(2L, Logs.receive(source, batch))
        assertEquals(2L, Logs.receive(source, batch))
        val received = Logs.recent().filter { it.tag == source }
        assertEquals(listOf("line 1", "line 2"), received.map { it.message })
        assertEquals(listOf(1001L, 1002L), received.map { it.atMs })
        assertTrue(received.all { it.level == "warn" })
    }

    @Test fun anOverlappingRetryKeepsOnlyTheNewLines() {
        val source = UUID.randomUUID().toString()
        Logs.receive(source, batch(source, 1, 2))
        assertEquals(3L, Logs.receive(source, batch(source, 2, 3)))
        assertEquals(3L, Logs.receive(source, batch(source, 1)))
        assertEquals(listOf("line 1", "line 2", "line 3"),
            Logs.recent().filter { it.tag == source }.map { it.message })
    }

    @Test fun aNewAppProcessCanRestartItsLogSequence() {
        val first = UUID.randomUUID().toString()
        val second = UUID.randomUUID().toString()
        Logs.receive(first, batch(first, 1))
        Logs.receive(second, batch(second, 1))
        assertEquals(1, Logs.recent().count { it.tag == first })
        assertEquals(1, Logs.recent().count { it.tag == second })
    }

    @Test fun boundedBatchesResumeWithoutSkippingLines() {
        val tag = UUID.randomUUID().toString()
        var through = Logs.recent().lastOrNull()?.sequence ?: 0L
        repeat(100) { Logs.d(tag, "$it " + "x".repeat(600)) }
        val messages = ArrayList<String>()
        var batches = 0
        while (true) {
            val batch = Logs.batch(through) ?: break
            assertTrue(batch.toString().toByteArray(Charsets.UTF_8).size <= 32_768)
            val lines = batch.getJSONArray("lines")
            for (i in 0 until lines.length()) messages.add(lines.getJSONObject(i).getString("message"))
            through = batch.getLong("through")
            batches++
        }
        assertTrue(batches > 1)
        assertEquals((0 until 100).toList(), messages.map { it.substringBefore(' ').toInt() })
    }

    @Test fun aLongCrashEntryCannotBlockTheControlConnection() {
        val after = Logs.recent().lastOrNull()?.sequence ?: 0L
        Logs.e("Crash", "\u0001".repeat(10_000))
        val batch = Logs.batch(after)!!
        assertTrue(batch.toString().toByteArray(Charsets.UTF_8).size <= 32_768)
        val row = batch.getJSONArray("lines").getJSONObject(0)
        assertEquals("error", row.getString("level"))
        assertEquals("Crash", row.getString("tag"))
        assertEquals(4096, row.getString("message").length)
    }

    private fun batch(tag: String, vararg ids: Int): JSONObject {
        val lines = JSONArray()
        for (id in ids) lines.put(JSONObject().put("id", id).put("atMs", 1000L + id)
            .put("level", "warn").put("tag", tag).put("message", "line $id"))
        return JSONObject().put("through", ids.last()).put("lines", lines)
    }
}
