package com.strike.server.api

import com.strike.recording.ClipStore
import com.strike.recording.Retention
import com.strike.recording.totalBytes
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.system.measureNanoTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DirectoryInventoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun aLargeLibrarySharesOneInventoryAcrossListSettingsAndDashboardReaders() {
        repeat(1_200) { index ->
            val minute = (index / 60).toString().padStart(2, '0')
            val second = (index % 60).toString().padStart(2, '0')
            File(folder.root, "drive_20260916_12$minute$second.mp4").writeBytes(byteArrayOf(1, 2))
        }
        var directScans = 0
        val directNanos = measureNanoTime {
            repeat(3) {
                val clips = ClipStore(folder.root).list()
                directScans++
                assertEquals(1_200, clips.size)
                assertEquals(2_400L, totalBytes(clips))
            }
        }
        val executor = QueuedExecutor()
        var scans = 0
        val inventory = DirectoryInventory({ root: File ->
            scans++
            ClipStore(root).list()
        }, executor)

        assertNull(inventory.snapshot(folder.root, 1L).value)
        executor.runNext()
        val warmNanos = measureNanoTime {
            repeat(3) {
                val snapshot = inventory.snapshot(folder.root, 1L)
                assertFalse(snapshot.pending)
                assertEquals(1_200, snapshot.value!!.size)
                assertEquals(2_400L, totalBytes(snapshot.value))
            }
        }
        val list = inventory.snapshot(folder.root, 1L)
        val settings = inventory.snapshot(folder.root, 1L)
        val dashboard = inventory.snapshot(folder.root, 1L)

        assertEquals(1_200, list.value!!.size)
        assertEquals(2_400L, totalBytes(settings.value!!))
        assertEquals(1_200, dashboard.value!!.size)
        assertEquals("drive_20260916_121959.mp4", list.value.first().id)
        assertSame(list.value, settings.value)
        assertSame(list.value, dashboard.value)
        assertFalse(list.pending)
        assertEquals(3, directScans)
        assertEquals(1, scans)
        assertEquals(0, executor.size)
        println("1,200 clips: three direct scans ${directNanos / 1_000_000.0}ms; " +
            "three warm snapshots ${warmNanos / 1_000_000.0}ms; scans $directScans versus $scans")
    }

    @Test(timeout = 5_000L)
    fun concurrentColdReadersQueueOnlyOneScan() {
        val executor = QueuedExecutor()
        val inventory = DirectoryInventory(::clips, executor)
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        val replies = ConcurrentLinkedQueue<DirectorySnapshot<List<String>>>()
        val readers = List(8) {
            Thread {
                ready.countDown()
                await(start)
                replies.add(inventory.snapshot(folder.root, 1L))
            }.also { it.start() }
        }
        await(ready)
        start.countDown()
        readers.forEach { it.join(2_000L) }

        assertTrue(readers.none { it.isAlive })
        assertEquals(8, replies.size)
        assertTrue(replies.all { it.pending && it.value == null })
        assertEquals(1, executor.size)
        executor.runNext()
        assertEquals(emptyList<String>(), inventory.snapshot(folder.root, 1L).value)
    }

    @Test(timeout = 5_000L)
    fun aWarmReaderReturnsItsSnapshotWhileTheRefreshIsBlocked() {
        File(folder.root, "first.mp4").writeText("clip")
        val executor = QueuedExecutor()
        val now = AtomicLong(0L)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val scans = AtomicInteger()
        val inventory = DirectoryInventory({ root: File ->
            if (scans.incrementAndGet() == 2) {
                entered.countDown()
                await(release)
            }
            clips(root)
        }, executor, now::get)
        inventory.snapshot(folder.root, 1L)
        executor.runNext()
        val first = inventory.snapshot(folder.root, 1L).value
        now.set(30_000L)
        assertTrue(inventory.snapshot(folder.root, 1L).pending)
        val worker = Thread { executor.runNext() }.also { it.start() }
        try {
            await(entered)
            val request = FutureTask { inventory.snapshot(folder.root, 1L) }
            val reader = Thread(request).also { it.start() }
            val current = request.get(2L, TimeUnit.SECONDS)
            assertSame(first, current.value)
            assertTrue(current.pending)
            reader.join(2_000L)
            assertEquals(0, executor.size)
        } finally {
            release.countDown()
            worker.join(2_000L)
        }
        assertFalse(inventory.snapshot(folder.root, 1L).pending)
        assertEquals(2, scans.get())
    }

    @Test
    fun finalizingAndDeletingClipsRefreshesBeforeTheTtl() {
        val id = "drive_20260916_120000.mp4"
        val writing = File(folder.root, "$id.tmp").also { it.writeText("clip") }
        stamp(1_000_000L)
        val executor = QueuedExecutor()
        val inventory = DirectoryInventory({ root: File -> ClipStore(root).list() }, executor) { 0L }
        inventory.snapshot(folder.root, 1L)
        executor.runNext()
        assertTrue(inventory.snapshot(folder.root, 1L).value!!.isEmpty())

        val completed = File(folder.root, id)
        assertTrue(writing.renameTo(completed))
        stamp(1_002_000L)
        assertTrue(inventory.snapshot(folder.root, 1L).pending)
        executor.runNext()
        val finalized = inventory.snapshot(folder.root, 1L).value!!
        assertEquals(listOf(id), finalized.map { it.id })
        assertEquals(4L, totalBytes(finalized))

        assertTrue(completed.delete())
        stamp(1_004_000L)
        assertTrue(inventory.snapshot(folder.root, 1L).pending)
        executor.runNext()
        assertTrue(inventory.snapshot(folder.root, 1L).value!!.isEmpty())
    }

    @Test
    fun retentionReadsNewFinalizedFilesEvenWhenTheDisplayInventoryIsStale() {
        val old = File(folder.root, "drive_20260916_120000.mp4").also { it.writeText("old!") }
        val inFlight = File(folder.root, "drive_20260916_120002.mp4.tmp").also { it.writeText("live") }
        stamp(1_000_000L)
        val executor = QueuedExecutor()
        val inventory = DirectoryInventory({ root: File -> ClipStore(root).list() }, executor) { 0L }
        inventory.snapshot(folder.root, 1L)
        executor.runNext()
        val cached = inventory.snapshot(folder.root, 1L).value!!
        assertEquals(listOf(old.name), cached.map { it.id })

        val latest = File(folder.root, "drive_20260916_120001.mp4").also { it.writeText("new!") }
        stamp(1_000_000L)
        assertSame(cached, inventory.snapshot(folder.root, 1L).value)
        assertEquals(1, Retention(ClipStore(folder.root), 4L).enforce(inFlight.name))
        assertFalse(old.exists())
        assertTrue(latest.exists())
        assertTrue(inFlight.exists())

        inventory.forget()
        assertNull(inventory.snapshot(folder.root, 1L).value)
        executor.runNext()
        val refreshed = inventory.snapshot(folder.root, 1L).value!!
        assertEquals(listOf(latest.name), refreshed.map { it.id })
        assertEquals(4L, totalBytes(refreshed))
    }

    @Test
    fun unchangedDirectoryStampsStillRefreshSidecarsThirtySecondsAfterCompletion() {
        val sidecar = File(folder.root, "event.json").also { it.writeText("person") }
        stamp(1_000_000L)
        val now = AtomicLong(0L)
        val executor = QueuedExecutor()
        val inventory = DirectoryInventory({ _: File ->
            sidecar.readText().also { now.addAndGet(5_000L) }
        }, executor, now::get)
        inventory.snapshot(folder.root, 1L)
        executor.runNext()
        sidecar.writeText("vehicle")
        stamp(1_000_000L)
        now.set(34_999L)
        assertEquals("person", inventory.snapshot(folder.root, 1L).value)
        assertEquals(0, executor.size)
        now.set(35_000L)
        assertTrue(inventory.snapshot(folder.root, 1L).pending)
        executor.runNext()
        assertEquals("vehicle", inventory.snapshot(folder.root, 1L).value)
    }

    @Test
    fun aMountRevisionNeverReturnsThePreviousCardsFiles() {
        val old = File(folder.root, "old.mp4").also { it.writeText("clip") }
        stamp(1_000_000L)
        val executor = QueuedExecutor()
        val inventory = DirectoryInventory(::clips, executor) { 0L }
        inventory.snapshot(folder.root, 1L)
        executor.runNext()
        assertTrue(old.delete())
        File(folder.root, "new.mp4").writeText("clip")
        stamp(1_000_000L)

        val changed = inventory.snapshot(folder.root, 2L)
        assertNull(changed.value)
        assertTrue(changed.pending)
        executor.runNext()
        assertEquals(listOf("new.mp4"), inventory.snapshot(folder.root, 2L).value)
    }

    @Test
    fun aDirectoryChangedDuringScanningCannotPublishAnObsoleteListing() {
        File(folder.root, "first.mp4").writeText("clip")
        stamp(1_000_000L)
        val executor = QueuedExecutor()
        var scans = 0
        val inventory = DirectoryInventory({ root: File ->
            clips(root).also {
                if (scans++ == 0) {
                    File(root, "second.mp4").writeText("clip")
                    stamp(1_002_000L)
                }
            }
        }, executor)
        inventory.snapshot(folder.root, 1L)
        executor.runNext()
        assertNull(inventory.snapshot(folder.root, 1L).value)
        executor.runNext()
        assertEquals(listOf("first.mp4", "second.mp4"), inventory.snapshot(folder.root, 1L).value)
    }

    @Test(timeout = 5_000L)
    fun forgettingDuringAScanCannotRestoreDeletedClips() = invalidateDuringScan(forget = true)

    @Test(timeout = 5_000L)
    fun changingMountRevisionDuringAScanRejectsThePreviousCard() = invalidateDuringScan(forget = false)

    private fun invalidateDuringScan(forget: Boolean) {
        val old = File(folder.root, "old.mp4").also { it.writeText("clip") }
        stamp(1_000_000L)
        val executor = QueuedExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val scans = AtomicInteger()
        val inventory = DirectoryInventory({ root: File ->
            clips(root).also {
                if (scans.incrementAndGet() == 1) {
                    entered.countDown()
                    await(release)
                }
            }
        }, executor)
        inventory.snapshot(folder.root, 1L)
        val worker = Thread { executor.runNext() }.also { it.start() }
        val revision = if (forget) 1L else 2L
        try {
            await(entered)
            assertTrue(old.delete())
            File(folder.root, "new.mp4").writeText("clip")
            stamp(1_000_000L)
            if (forget) inventory.forget()
            val pending = inventory.snapshot(folder.root, revision)
            assertNull(pending.value)
            assertTrue(pending.pending)
            assertEquals(0, executor.size)
        } finally {
            release.countDown()
            worker.join(2_000L)
        }
        assertNull(inventory.snapshot(folder.root, revision).value)
        executor.runNext()
        assertEquals(listOf("new.mp4"), inventory.snapshot(folder.root, revision).value)
    }

    @Test
    fun aFailedScanCanBeRetried() {
        val executor = QueuedExecutor()
        var scans = 0
        val inventory = DirectoryInventory({ _: File ->
            if (scans++ == 0) throw IOException("Card is busy")
            listOf("recovered.mp4")
        }, executor)
        inventory.snapshot(folder.root, 1L)
        try {
            executor.runNext()
            fail("The scan failure should reach its executor")
        } catch (_: IOException) { }
        assertTrue(inventory.snapshot(folder.root, 1L).pending)
        executor.runNext()
        assertEquals(listOf("recovered.mp4"), inventory.snapshot(folder.root, 1L).value)
    }

    @Test
    fun aRejectedJobDoesNotLeaveTheDirectoryPermanentlyPending() {
        val queued = QueuedExecutor()
        var rejected = false
        val executor = Executor { task ->
            if (!rejected) {
                rejected = true
                throw RejectedExecutionException("Worker is busy")
            }
            queued.execute(task)
        }
        val inventory = DirectoryInventory(::clips, executor)
        try {
            inventory.snapshot(folder.root, 1L)
            fail("The scheduling failure should reach its caller")
        } catch (_: RejectedExecutionException) { }
        assertTrue(inventory.snapshot(folder.root, 1L).pending)
        queued.runNext()
        assertFalse(inventory.snapshot(folder.root, 1L).pending)
    }

    @Test
    fun aFullCacheDoesNotQueueUnboundedScansAndRetriesWhenASlotIsFree() {
        val roots = List(9) { folder.newFolder("card-$it") }
        val executor = QueuedExecutor()
        val inventory = DirectoryInventory(::clips, executor)
        roots.forEach { assertTrue(inventory.snapshot(it, 1L).pending) }
        assertEquals(8, executor.size)
        repeat(8) { executor.runNext() }
        assertTrue(inventory.snapshot(roots.last(), 1L).pending)
        assertEquals(1, executor.size)
        executor.runNext()
        assertEquals(emptyList<String>(), inventory.snapshot(roots.last(), 1L).value)
    }

    private fun stamp(value: Long) {
        assertTrue(folder.root.setLastModified(value))
    }

    private fun clips(root: File): List<String> =
        root.list()?.filter { it.endsWith(".mp4") }?.sorted() ?: emptyList()

    private fun await(latch: CountDownLatch) {
        assertTrue("The coordinated operation did not finish", latch.await(2L, TimeUnit.SECONDS))
    }

    private class QueuedExecutor : Executor {
        private val jobs = ConcurrentLinkedQueue<Runnable>()
        val size: Int get() = jobs.size

        override fun execute(command: Runnable) {
            jobs.add(command)
        }

        fun runNext() {
            requireNotNull(jobs.poll()) { "No inventory scan was queued" }.run()
        }
    }
}
