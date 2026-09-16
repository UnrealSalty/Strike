package com.strike.recording

import android.media.MediaCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleQueueTest {
    @Test
    fun acceptedFramesKeepTheirOrderAndPayload() {
        val queue = SampleQueue(3)
        val key = sample(0, key = true)
        val next = sample(1)
        assertEquals(SampleAdmission.ACCEPTED, queue.offer(key))
        assertEquals(SampleAdmission.ACCEPTED, queue.offer(next))
        assertSame(key, queue.poll(0))
        assertSame(next, queue.poll(0))
        assertNull(queue.poll(0))
        assertEquals(0L, queue.dropped)
    }

    @Test
    fun overloadPreservesQueuedFootageAndRejectsDependentFramesUntilAKeyframeFits() {
        val queue = SampleQueue(2)
        val key = sample(0, key = true)
        val next = sample(1)
        queue.offer(key)
        queue.offer(next)
        assertEquals(SampleAdmission.REQUEST_KEY_FRAME, queue.offer(sample(2)))
        assertSame(key, queue.poll(0))
        assertEquals(SampleAdmission.DROPPED, queue.offer(sample(3)))
        assertSame(next, queue.poll(0))
        assertNull(queue.poll(0))
        val recovery = sample(4, key = true)
        assertEquals(SampleAdmission.ACCEPTED, queue.offer(recovery))
        assertEquals(SampleAdmission.ACCEPTED, queue.offer(sample(5)))
        assertSame(recovery, queue.poll(0))
        assertEquals(5L, queue.poll(0)!!.timeUs)
        assertEquals(2L, queue.dropped)
    }

    @Test
    fun aKeyframeThatCannotFitDoesNotResumeDependentFrames() {
        val queue = SampleQueue(1)
        val first = sample(0, key = true)
        queue.offer(first)
        assertEquals(SampleAdmission.REQUEST_KEY_FRAME, queue.offer(sample(1, key = true)))
        assertEquals(SampleAdmission.DROPPED, queue.offer(sample(2, key = true)))
        assertSame(first, queue.poll(0))
        assertEquals(SampleAdmission.DROPPED, queue.offer(sample(3)))
        assertNull(queue.poll(0))
        val recovery = sample(4, key = true)
        assertEquals(SampleAdmission.ACCEPTED, queue.offer(recovery))
        assertSame(recovery, queue.poll(0))
    }

    @Test(timeout = 2_000)
    fun aRotationNeverWaitsForSpaceAndMovesToTheNextAcceptedKeyframe() {
        val queue = SampleQueue(1)
        val first = sample(0, key = true)
        queue.offer(first)
        assertEquals(SampleAdmission.REQUEST_KEY_FRAME, queue.offer(sample(1, key = true, rotate = true)))
        assertEquals(SampleAdmission.DROPPED, queue.offer(sample(2)))
        assertSame(first, queue.poll(0))
        val recovery = sample(3, key = true)
        queue.offer(recovery)
        val splice = queue.poll(0)!!
        assertTrue(splice.startsClip)
        assertTrue(keyFrame(splice))
        assertEquals(recovery.timeUs, splice.timeUs)
        assertSame(recovery.bytes, splice.bytes)
        queue.offer(sample(4))
        assertFalse(queue.poll(0)!!.startsClip)
    }

    @Test
    fun queuedRotationCannotBeEvictedByLaterFrames() {
        val queue = SampleQueue(1)
        val splice = sample(0, key = true, rotate = true)
        queue.offer(splice)
        queue.offer(sample(1))
        queue.offer(sample(2))
        assertSame(splice, queue.poll(0))
    }

    @Test
    fun rotationRequestedWhileRecoveringSurvivesDiscardedFrames() {
        val queue = SampleQueue(1)
        queue.offer(sample(0, key = true))
        queue.offer(sample(1))
        queue.poll(0)
        assertEquals(SampleAdmission.DROPPED, queue.offer(sample(2, rotate = true)))
        queue.offer(sample(3, key = true))
        assertTrue(queue.poll(0)!!.startsClip)
    }

    @Test
    fun recoveryRequestsOneKeyframePerOverloadEpisode() {
        val queue = SampleQueue(1)
        queue.offer(sample(0, key = true))
        assertEquals(SampleAdmission.REQUEST_KEY_FRAME, queue.offer(sample(1)))
        assertEquals(SampleAdmission.DROPPED, queue.offer(sample(2)))
        queue.poll(0)
        queue.offer(sample(3, key = true))
        assertEquals(SampleAdmission.REQUEST_KEY_FRAME, queue.offer(sample(4)))
    }

    @Test
    fun clearingAStoppedSessionResetsItsRecoveryAndRotation() {
        val queue = SampleQueue(1)
        queue.offer(sample(0, key = true))
        queue.offer(sample(1, key = true, rotate = true))
        queue.clear()
        assertFalse(queue.isNotEmpty())
        assertEquals(0L, queue.dropped)
        val fresh = sample(2, key = true)
        assertEquals(SampleAdmission.ACCEPTED, queue.offer(fresh))
        assertSame(fresh, queue.poll(0))
        assertFalse(fresh.startsClip)
    }

    private fun sample(atUs: Long, key: Boolean = false, rotate: Boolean = false) =
        Sample(byteArrayOf(atUs.toByte()), atUs, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0, if (rotate) 1L else 0L)
}
