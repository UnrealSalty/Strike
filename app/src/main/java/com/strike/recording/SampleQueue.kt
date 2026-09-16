package com.strike.recording

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

internal enum class SampleAdmission { ACCEPTED, DROPPED, REQUEST_KEY_FRAME }

internal class SampleQueue(capacity: Int) {
    private val held = ArrayBlockingQueue<Sample>(capacity)
    private var waitingForKeyFrame = false
    private var rotationPending = 0L

    var dropped = 0L
        private set

    @Synchronized
    fun offer(sample: Sample): SampleAdmission {
        if (sample.startsClip) rotationPending = sample.rotationId
        if (waitingForKeyFrame && !keyFrame(sample)) {
            dropped++
            return SampleAdmission.DROPPED
        }
        val next = if (rotationPending != 0L && !sample.startsClip) {
            Sample(sample.bytes, sample.timeUs, sample.flags, rotationPending)
        } else sample
        if (held.offer(next)) {
            waitingForKeyFrame = false
            rotationPending = 0L
            return SampleAdmission.ACCEPTED
        }
        dropped++
        val firstDrop = !waitingForKeyFrame
        waitingForKeyFrame = true
        return if (firstDrop) SampleAdmission.REQUEST_KEY_FRAME else SampleAdmission.DROPPED
    }

    fun poll(timeoutMs: Long): Sample? = held.poll(timeoutMs, TimeUnit.MILLISECONDS)

    fun isNotEmpty(): Boolean = held.isNotEmpty()

    @Synchronized
    fun clear() {
        held.clear()
        waitingForKeyFrame = false
        rotationPending = 0L
        dropped = 0L
    }
}
