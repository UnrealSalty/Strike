package com.strike.recording

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeCacheTest {
    private val work = ArrayDeque<Runnable>()
    private var nowMs = 0L
    private val cache = VolumeCache(Executor { work.addLast(it) }, { nowMs })
    private var reply = discovered("1111-AAAA")

    @Test
    fun readersShareOneRefreshAndReuseTheAnswerForFifteenSeconds() {
        repeat(20) {
            val snapshot = cache.snapshot { reply }
            assertTrue(snapshot.pending)
            assertTrue(snapshot.volumes.isEmpty())
        }
        assertEquals(1, work.size)
        work.removeFirst().run()
        val first = cache.snapshot { reply }
        assertFalse(first.pending)
        assertEquals("/storage/1111-AAAA", first.volumes.getValue("sd").dir.path.replace('\\', '/'))

        nowMs = 14_999L
        assertFalse(cache.snapshot { reply }.pending)
        assertTrue(work.isEmpty())
        nowMs = 15_000L
        assertTrue(cache.snapshot { reply }.pending)
        assertEquals(1, work.size)
    }

    @Test
    fun aBlockedShellProbeDoesNotBlockAnotherReaderOrAnInvalidation() {
        val workers = Executors.newFixedThreadPool(2)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val probes = AtomicInteger()
        val cache = VolumeCache(workers, { 0L })
        val probe = {
            if (probes.incrementAndGet() == 1) {
                entered.countDown()
                assertTrue(release.await(2, TimeUnit.SECONDS))
            }
            discovered("1111-AAAA")
        }
        try {
            assertTrue(cache.snapshot(probe).pending)
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            val reader = workers.submit<VolumeSnapshot> {
                cache.invalidate()
                cache.snapshot(probe)
            }
            val snapshot = reader.get(1, TimeUnit.SECONDS)
            assertTrue(snapshot.pending)
            assertTrue(snapshot.volumes.isEmpty())
            assertEquals(1, probes.get())
        } finally {
            release.countDown()
            workers.shutdown()
            assertTrue(workers.awaitTermination(2, TimeUnit.SECONDS))
        }
        assertEquals(2, probes.get())
        assertFalse(cache.snapshot(probe).pending)
        assertEquals(setOf("sd"), cache.snapshot(probe).volumes.keys)
    }

    @Test
    fun anInvalidatedProbeCannotPublishItsOldCard() {
        var probes = 0
        cache.snapshot {
            probes++
            if (probes == 1) {
                cache.invalidate()
                discovered("1111-AAAA")
            } else discovered("2222-BBBB")
        }
        work.removeFirst().run()
        val snapshot = cache.snapshot { reply }
        assertEquals(2, probes)
        assertFalse(snapshot.pending)
        assertEquals(1L, snapshot.revision)
        assertEquals(File("/storage/2222-BBBB"), snapshot.volumes.getValue("sd").dir)
        assertTrue(work.isEmpty())
    }

    @Test
    fun capacityChangesDoNotInvalidateAnUnchangedRoot() {
        val first = refresh()
        nowMs = 15_000L
        reply = discovered("1111-AAAA", freeMb = 120)
        val next = refresh()
        assertEquals(first.revision, next.revision)
        assertEquals(120, next.volumes.getValue("sd").freeMb)
    }

    @Test
    fun confirmedRemovalAndRemountEachChangeTheRevision() {
        val first = refresh()
        nowMs = 15_000L
        reply = VolumeDiscovery(emptyMap(), "")
        val removed = refresh()
        assertTrue(removed.volumes.isEmpty())
        assertTrue(removed.revision > first.revision)
        nowMs = 30_000L
        reply = discovered("1111-AAAA")
        val returned = refresh()
        assertTrue(returned.revision > removed.revision)
        assertEquals(setOf("sd"), returned.volumes.keys)
    }

    @Test
    fun aFailedCapacityProbeKeepsOnlyTheSameCardsPreviousMeasurement() {
        val first = refresh()
        nowMs = 15_000L
        reply = VolumeDiscovery(emptyMap(), listing("1111-AAAA"))
        val same = refresh()
        assertSame(first.volumes.getValue("sd"), same.volumes.getValue("sd"))
        assertEquals(first.revision, same.revision)

        nowMs = 30_000L
        reply = VolumeDiscovery(emptyMap(), listing("2222-BBBB"))
        val replaced = refresh()
        assertTrue(replaced.volumes.isEmpty())
        assertTrue(replaced.revision > same.revision)
        nowMs = 45_000L
        reply = discovered("2222-BBBB")
        assertEquals(File("/storage/2222-BBBB"), refresh().volumes.getValue("sd").dir)
    }

    @Test
    fun failedListingsKeepTheCardForOnlySixtySecondsAfterTheLastAnswer() {
        val first = refresh()
        reply = VolumeDiscovery(emptyMap(), null)
        nowMs = 15_000L
        assertEquals(first.volumes, refresh().volumes)
        nowMs = 60_000L
        assertEquals(first.volumes, refresh().volumes)
        nowMs = 60_001L
        val expired = cache.snapshot { reply }
        assertTrue(expired.volumes.isEmpty())
        assertTrue(expired.revision > first.revision)
        assertFalse(expired.pending)
    }

    @Test
    fun anUnfinishedRefreshCannotExtendTheRemovalGrace() {
        refresh()
        nowMs = 60_000L
        assertEquals(setOf("sd"), cache.snapshot { reply }.volumes.keys)
        nowMs = 60_001L
        val expired = cache.snapshot { reply }
        assertTrue(expired.pending)
        assertTrue(expired.volumes.isEmpty())
        assertEquals(1, work.size)
    }

    @Test
    fun explicitPathResolutionReusesAnUnmountedUuidWithoutMeasuringCapacity() {
        var reads = 0
        val read = {
            reads++
            "public:179,65 unmounted 3333-CCCC" to null
        }
        val first = cache.listing(read)!!
        val second = cache.listing(read)!!
        assertEquals(first, second)
        assertEquals("/storage/3333-CCCC", volumePathFor("sd", second, null))
        assertEquals(1, reads)
        assertTrue(work.isEmpty())
        assertTrue(cache.snapshot { reply }.volumes.isEmpty())
    }

    @Test
    fun anExplicitListingInvalidatesAnOlderMeasurementInFlight() {
        var probes = 0
        cache.snapshot {
            probes++
            if (probes == 1) {
                cache.listing { listing("2222-BBBB") to null }
                discovered("1111-AAAA")
            } else discovered("2222-BBBB")
        }
        work.removeFirst().run()
        val snapshot = cache.snapshot { reply }
        assertEquals(2, probes)
        assertEquals(File("/storage/2222-BBBB"), snapshot.volumes.getValue("sd").dir)
        assertFalse(snapshot.pending)
    }

    @Test
    fun anInvalidationDuringPathResolutionDoesNotRepublishTheOldListing() {
        assertNull(cache.listing {
            cache.invalidate()
            listing("1111-AAAA") to null
        })
        val fresh = cache.listing { listing("2222-BBBB") to null }
        assertEquals(listing("2222-BBBB"), fresh)
    }

    private fun refresh(): VolumeSnapshot {
        assertTrue(cache.snapshot { reply }.pending)
        work.removeFirst().run()
        return cache.snapshot { reply }
    }

    private fun discovered(uuid: String, freeMb: Int = 200): VolumeDiscovery = VolumeDiscovery(
        mapOf("sd" to Volume("sd", File("/storage/$uuid"), freeMb, 1_000)), listing(uuid)
    )

    private fun listing(uuid: String): String = "public:179,65 mounted $uuid"
}
