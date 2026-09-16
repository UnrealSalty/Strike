package com.strike.recording

import android.media.MediaCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipRotationTest {
    @Test
    fun nothingSplitsUntilARotationIsRequested() {
        val rotation = ClipRotation()
        assertEquals(0L, rotation.boundary(true, 1_000_000L))
        assertEquals(0L, rotation.boundary(false, 1_003_000L))
        assertFalse(rotation.isPending(0L))
    }

    @Test
    fun aRequestAtMonotonicZeroStillReachesItsDeadline() {
        val rotation = ClipRotation()
        assertTrue(rotation.request(0L))
        assertEquals(0L, rotation.boundary(false, 2_999L))
        val id = rotation.boundary(false, 3_000L)
        assertTrue(id > 0L)
        assertTrue(rotation.isPending(id))
    }

    @Test
    fun theFirstKeyframeMarksOneBoundaryAndWaitsForTheWriter() {
        val rotation = ClipRotation()
        assertTrue(rotation.request(1_000_000L))
        assertEquals(0L, rotation.boundary(false, 1_000_400L))
        val id = rotation.boundary(true, 1_000_400L)
        assertTrue(id > 0L)
        assertTrue(rotation.isPending(id))
        assertEquals(0L, rotation.boundary(true, 1_001_400L))
        assertFalse(rotation.request(1_006_400L))
        rotation.complete()
        assertFalse(rotation.isPending(id))
        assertTrue(rotation.request(1_120_400L))
        assertTrue(rotation.boundary(true, 1_120_800L) > id)
    }

    @Test
    fun repeatedRequestsCannotPostponeTheThreeSecondDeadline() {
        val rotation = ClipRotation()
        assertTrue(rotation.request(1_000L))
        assertFalse(rotation.request(3_999L))
        assertEquals(0L, rotation.boundary(false, 3_999L))
        val id = rotation.boundary(false, 4_000L)
        assertTrue(id > 0L)
        assertEquals(0L, rotation.boundary(false, 7_000L))
        assertEquals(0L, rotation.boundary(true, 7_100L))
        assertTrue(rotation.isPending(id))
    }

    @Test
    fun writerBacklogCannotArmAnotherBoundaryBehindTheOneAlreadyQueued() {
        val writer = Writer()
        val first = sample(0L, key = true)
        val overdue = sample(119_900L)
        val backlog = sample(119_950L)
        writer.enqueue(first, overdue, backlog)
        writer.writeNext(0L)
        writer.writeNext(120_000L)
        assertTrue(writer.rotation.request(120_000L))
        val boundary = writer.encoded(122_000L, key = true)
        writer.enqueue(boundary)
        writer.writeNext(120_100L)
        assertFalse(writer.rotation.request(120_100L))
        assertFalse(writer.rotation.request(120_200L))
        val sixSecondsLater = writer.encoded(128_000L, key = true)
        writer.enqueue(sixSecondsLater)
        writer.writeNext(122_000L)
        writer.writeNext(128_000L)
        assertEquals(listOf(listOf(0L, 119_900L, 119_950L), listOf(122_000L, 128_000L)), writer.partitions())
        writer.assertRetained(first, overdue, backlog, boundary, sixSecondsLater)
    }

    @Test
    fun duplicateQueuedBoundariesCannotCreateASixSecondClip() {
        val writer = Writer()
        val first = sample(0L, key = true)
        writer.enqueue(first)
        writer.writeNext(0L)
        writer.rotation.request(120_000L)
        val boundary = writer.encoded(122_000L, key = true)
        val duplicate = sample(128_000L, key = true, rotationId = boundary.rotationId)
        val tail = sample(128_100L)
        writer.enqueue(boundary, duplicate, tail)
        writer.writeNext(122_000L)
        writer.writeNext(128_000L)
        writer.writeNext(128_100L)
        assertEquals(listOf(listOf(0L), listOf(122_000L, 128_000L, 128_100L)), writer.partitions())
        writer.assertRetained(first, boundary, duplicate, tail)
    }

    @Test
    fun anOverdueQueuedKeyframeInvalidatesTheLaterEncoderBoundary() {
        val writer = Writer()
        val first = sample(0L, key = true)
        val fallback = sample(120_000L, key = true)
        writer.enqueue(first, fallback)
        writer.writeNext(0L)
        writer.rotation.request(120_000L)
        val obsolete = writer.encoded(122_000L, key = true)
        val tail = sample(126_000L)
        writer.enqueue(obsolete, tail)
        writer.writeNext(122_100L)
        assertFalse(writer.rotation.isPending(obsolete.rotationId))
        writer.writeNext(122_200L)
        writer.writeNext(126_000L)
        assertEquals(listOf(listOf(0L), listOf(120_000L, 122_000L, 126_000L)), writer.partitions())
        writer.assertRetained(first, fallback, obsolete, tail)
    }

    @Test
    fun lateAudioRotatesOnlyOnAnEligibleKeyframeAndCancelsThePendingAsk() {
        val writer = Writer()
        val retained = listOf(sample(0L, key = true), sample(999L, key = true), sample(1_000L), sample(1_200L, key = true))
        writer.enqueue(*retained.toTypedArray())
        writer.writeNext(0L)
        assertTrue(writer.rotation.request(500L))
        writer.writeNext(999L, audioReady = true)
        writer.writeNext(1_000L, audioReady = true)
        writer.writeNext(1_200L, audioReady = true)
        assertEquals(0L, writer.rotation.boundary(true, 6_000L))
        assertEquals(listOf(listOf(0L, 999L, 1_000L), listOf(1_200L)), writer.partitions())
        writer.assertRetained(*retained.toTypedArray())
    }

    @Test
    fun anObsoleteBoundaryCannotCancelANewerRequest() {
        val writer = Writer()
        writer.enqueue(sample(0L, key = true))
        writer.writeNext(0L)
        writer.rotation.request(100L)
        val obsoleteId = writer.rotation.boundary(true, 200L)
        val audioBoundary = sample(1_000L, key = true)
        writer.enqueue(audioBoundary)
        writer.writeNext(1_000L, audioReady = true)
        assertTrue(writer.rotation.request(2_000L))
        val obsolete = sample(4_000L, key = true, rotationId = obsoleteId)
        writer.enqueue(obsolete)
        writer.writeNext(4_000L)
        assertFalse(writer.rotation.request(4_000L))
        val current = writer.encoded(4_100L, key = true)
        assertTrue(current.rotationId > obsoleteId)
        assertTrue(writer.rotation.isPending(current.rotationId))
        writer.enqueue(current)
        writer.writeNext(4_100L)
        assertFalse(writer.rotation.isPending(current.rotationId))
        assertEquals(listOf(listOf(0L), listOf(1_000L, 4_000L), listOf(4_100L)), writer.partitions())
    }

    @Test
    fun queueOverflowTransfersTheSameRequestToTheRecoveryKeyframe() {
        val writer = Writer(capacity = 2)
        val first = sample(0L, key = true)
        val second = sample(1_000L)
        writer.enqueue(first, second)
        writer.rotation.request(120_000L)
        val droppedBoundary = writer.encoded(122_000L, key = true)
        assertEquals(SampleAdmission.REQUEST_KEY_FRAME, writer.queue.offer(droppedBoundary))
        assertEquals(SampleAdmission.DROPPED, writer.queue.offer(sample(122_100L)))
        writer.writeNext(0L)
        writer.writeNext(1_000L)
        val recovery = sample(122_200L, key = true)
        val tail = sample(128_200L)
        writer.enqueue(recovery, tail)
        val acceptedBoundary = writer.writeNext(122_200L)
        assertEquals(droppedBoundary.rotationId, acceptedBoundary.rotationId)
        assertTrue(acceptedBoundary.startsClip)
        assertFalse(writer.rotation.isPending(acceptedBoundary.rotationId))
        assertFalse(writer.writeNext(128_200L).startsClip)
        assertEquals(2L, writer.queue.dropped)
        assertEquals(listOf(listOf(0L, 1_000L), listOf(122_200L, 128_200L)), writer.partitions())
        writer.assertRetained(first, second, recovery, tail)
    }

    @Test
    fun ordinaryTwoMinuteClipsContinueAfterEachAcknowledgedBoundary() {
        val writer = Writer()
        val retained = mutableListOf(sample(0L, key = true))
        writer.enqueue(retained.last())
        writer.writeNext(0L)
        for (requestedAt in listOf(120_000L, 240_000L)) {
            val dependent = sample(requestedAt - 1L)
            retained.add(dependent)
            writer.enqueue(dependent)
            writer.writeNext(requestedAt)
            assertTrue(writer.rotation.request(requestedAt))
            val boundary = writer.encoded(requestedAt, key = true)
            retained.add(boundary)
            writer.enqueue(boundary)
            writer.writeNext(requestedAt)
        }
        assertEquals(listOf(listOf(0L, 119_999L), listOf(120_000L, 239_999L), listOf(240_000L)), writer.partitions())
        val ids = writer.clips.drop(1).map { it.first().rotationId }
        assertTrue(ids.all { it > 0L })
        assertNotEquals(ids[0], ids[1])
        writer.assertRetained(*retained.toTypedArray())
    }

    @Test
    fun theDeadlineBoundaryStillRotatesWithoutAKeyframe() {
        val writer = Writer()
        val first = sample(0L, key = true)
        writer.enqueue(first)
        writer.writeNext(0L)
        writer.rotation.request(120_000L)
        val waiting = writer.encoded(122_999L)
        val boundary = writer.encoded(123_000L)
        val tail = writer.encoded(129_000L)
        writer.enqueue(waiting, boundary, tail)
        writer.writeNext(122_999L)
        writer.writeNext(123_000L)
        writer.writeNext(129_000L)
        assertFalse(keyFrame(boundary))
        assertEquals(listOf(listOf(0L, 122_999L), listOf(123_000L, 129_000L)), writer.partitions())
        writer.assertRetained(first, waiting, boundary, tail)
    }

    private class Writer(capacity: Int = 16) {
        val rotation = ClipRotation()
        val queue = SampleQueue(capacity)
        val clips = mutableListOf(mutableListOf<Sample>())
        private var startedAtMs = 0L

        fun encoded(atMs: Long, key: Boolean = false): Sample =
            sample(atMs, key, rotation.boundary(key, atMs))

        fun enqueue(vararg samples: Sample) {
            samples.forEach { assertEquals(SampleAdmission.ACCEPTED, queue.offer(it)) }
        }

        fun writeNext(nowMs: Long, audioReady: Boolean = false): Sample {
            val sample = requireNotNull(queue.poll(0L))
            if (clipShouldRotate(rotation.isPending(sample.rotationId), nowMs - startedAtMs, 120_000L, keyFrame(sample), audioReady)) {
                clips.add(mutableListOf())
                startedAtMs = nowMs
                rotation.complete()
            }
            clips.last().add(sample)
            return sample
        }

        fun partitions(): List<List<Long>> = clips.map { clip -> clip.map { it.timeUs / 1_000L } }

        fun assertRetained(vararg expected: Sample) {
            val written = clips.flatten()
            assertEquals(expected.size, written.size)
            expected.zip(written).forEach { (before, after) ->
                assertSame(before.bytes, after.bytes)
                assertEquals(before.timeUs, after.timeUs)
                assertEquals(before.flags, after.flags)
            }
        }
    }

    companion object {
        private fun sample(atMs: Long, key: Boolean = false, rotationId: Long = 0L): Sample =
            Sample(byteArrayOf(atMs.toByte(), (atMs / 1_000L).toByte()), atMs * 1_000L,
                if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0, rotationId)
    }
}
