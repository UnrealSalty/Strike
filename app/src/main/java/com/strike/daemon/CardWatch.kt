package com.strike.daemon

import android.os.SystemClock
import com.strike.core.systemProperty
import com.strike.recording.cardAnswers
import com.strike.recording.remountStuck
import com.strike.recording.storageUuid
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private const val TAG = "Card"
private const val CHECK_EVERY_MS = 10_000L
private const val CARD_DETECT = "sys.byd.isSDExist"

// Probes run on their own thread: a dead card can hold a write for seconds.
internal class CardWatch(
    private val dirs: () -> List<File>,
    private val revive: () -> Unit,
    private val answering: (File) -> Unit
) {
    private val outages = HashMap<String, CardOutage>()
    private val down: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val nudges = ArrayBlockingQueue<Unit>(1)

    fun isDown(dir: File): Boolean {
        val uuid = storageUuid(dir.path) ?: return false
        return uuid in down
    }

    fun nudge() {
        nudges.offer(Unit)
    }

    fun watch(alive: () -> Boolean) {
        while (alive()) {
            for ((uuid, cards) in dirs().groupBy { storageUuid(it.path) }) {
                if (uuid != null) check(uuid, cards)
            }
            nudges.poll(CHECK_EVERY_MS, TimeUnit.MILLISECONDS)
        }
    }

    private fun check(uuid: String, cards: List<File>) {
        val answered = cardAnswers(cards.first()) ?: return
        val outage = outages.getOrPut(uuid) { CardOutage() }
        val since = outage.downSinceMs
        val nowMs = SystemClock.elapsedRealtime()
        when (outage.observe(answered, nowMs)) {
            CardStep.REVIVE -> {
                DaemonLog.w(TAG, "waking the car so the card reader at $uuid gets power back")
                revive()
            }
            CardStep.REMOUNT -> {
                val state = remountStuck(cards.first())
                DaemonLog.w(TAG, "the card at $uuid is still not answering; mounted it again from vold state ${state ?: "unlisted"}")
            }
            CardStep.NONE -> Unit
        }
        if (since == null && outage.isDown) {
            down.add(uuid)
            DaemonLog.e(TAG, "the card at $uuid stopped answering (card detect ${systemProperty(CARD_DETECT) ?: "unavailable"}); clips go to internal storage until it is back")
        }
        if (since != null && !outage.isDown) {
            down.remove(uuid)
            DaemonLog.d(TAG, "the card at $uuid is back after ${(nowMs - since) / 1000}s")
        }
        if (!outage.isDown) cards.forEach(answering)
    }
}
