package com.strike.recording

import android.os.SystemClock
import com.strike.core.Logs
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors

private const val REVALIDATE_MS = 15_000L
private const val GRACE_MS = 60_000L

internal class VolumeSnapshot(val volumes: Map<String, Volume>, val revision: Long, val pending: Boolean)

internal class VolumeDiscovery(
    val volumes: Map<String, Volume>,
    val listing: String?,
    val sdUuid: String? = null
)

internal class VolumeCache(
    private val worker: Executor = Executors.newSingleThreadExecutor {
        Thread(it, "volumes").also { thread -> thread.isDaemon = true }
    },
    private val nowMs: () -> Long = SystemClock::elapsedRealtime
) {
    private val lock = Any()
    private var held: Map<String, Volume> = emptyMap()
    private var topology: Map<String, String> = emptyMap()
    private var readAtMs: Long? = null
    private var answeredAtMs: Long? = null
    private var listing: String? = null
    private var listingGeneration = -1L
    private var generation = 0L
    private var revision = 0L
    private var refreshing = false

    fun snapshot(probe: () -> VolumeDiscovery): VolumeSnapshot {
        var start = false
        val snapshot = synchronized(lock) {
            val now = nowMs()
            expire(now)
            val readAt = readAtMs
            if (!refreshing && (readAt == null || now - readAt >= REVALIDATE_MS)) {
                refreshing = true
                start = true
            }
            VolumeSnapshot(held, revision, refreshing)
        }
        if (start) worker.execute { refresh(probe) }
        return snapshot
    }

    fun invalidate() = synchronized(lock) {
        generation++
        readAtMs = null
    }

    fun listing(read: () -> Pair<String, String?>?): String? {
        val started = synchronized(lock) {
            freshListing()?.let { return it }
            generation
        }
        val found = read() ?: return null
        return synchronized(lock) {
            if (generation != started) return freshListing()
            generation++
            listing = found.first
            listingGeneration = generation
            answeredAtMs = nowMs()
            readAtMs = null
            val roots = mountedRoots(found.first, found.second)
            replace(held.filter { (location, volume) ->
                location == INTERNAL || roots[location] == volume.dir.path
            }, roots)
            found.first
        }
    }

    private fun freshListing(): String? {
        val answeredAt = answeredAtMs ?: return null
        return if (listingGeneration == generation && nowMs() - answeredAt < REVALIDATE_MS) listing else null
    }

    // Only publication holds the cache lock; readers never wait for shell commands.
    private fun refresh(probe: () -> VolumeDiscovery) {
        while (true) {
            val (started, previous) = synchronized(lock) { generation to held }
            val found = try {
                probe()
            } catch (e: RuntimeException) {
                Logs.w("Storage", "Could not refresh mounted storage", e)
                VolumeDiscovery(previous.filterKeys { it == INTERNAL }, null)
            }
            val published = synchronized(lock) {
                if (generation != started) false else {
                    val now = nowMs()
                    if (found.listing != null) {
                        listing = found.listing
                        listingGeneration = generation
                        answeredAtMs = now
                        val roots = mountedRoots(found.listing, found.sdUuid)
                        val retained = previous.filter { (location, volume) -> roots[location] == volume.dir.path }
                        replace(retained + found.volumes, roots)
                    } else {
                        val answeredAt = answeredAtMs
                        val retained = if (answeredAt != null && now - answeredAt <= GRACE_MS) {
                            previous.filterKeys { it != INTERNAL }
                        } else emptyMap()
                        replace(found.volumes + retained, topology)
                    }
                    readAtMs = now
                    refreshing = false
                    true
                }
            }
            if (published) return
        }
    }

    private fun expire(now: Long) {
        val answeredAt = answeredAtMs ?: return
        if (now - answeredAt > GRACE_MS) replace(held.filterKeys { it == INTERNAL }, topology)
    }

    private fun replace(volumes: Map<String, Volume>, roots: Map<String, String>) {
        if (topology != roots || held.mapValues { it.value.dir.path } != volumes.mapValues { it.value.dir.path }) {
            revision++
        }
        held = volumes.toMap()
        topology = roots
    }

    private fun mountedRoots(listing: String, sdUuid: String?): Map<String, String> {
        val roots = LinkedHashMap<String, String>()
        for (mount in parseVolumes(listing, sdUuid)) {
            if (!roots.containsKey(mount.location)) roots[mount.location] = File(mount.path).path
        }
        return roots
    }
}
