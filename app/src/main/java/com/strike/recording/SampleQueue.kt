package com.strike.recording

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

internal enum class SampleAdmission { ACCEPTED, DROPPED, REQUEST_KEY_FRAME }

internal class SampleQueue(capacity: Int) {
    private val held = ArrayBlockingQueue<Sample>(capacity)
    private var waitingForKeyFrame = false
    private var rotationPending = false

    var dropped = 0L
        private set

    @Synchronized
    fun offer(sample: Sample): SampleAdmission {
        rotationPending = rotationPending || sample.startsClip
        if (waitingForKeyFrame && !keyFrame(sample)) {
            dropped++
            return SampleAdmission.DROPPED
        }
        val next = if (rotationPending && !sample.startsClip) {
            Sample(sample.bytes, sample.timeUs, sample.flags, true)
        } else sample
        if (held.offer(next)) {
            waitingForKeyFrame = false
            rotationPending = false
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
        rotationPending = false
        dropped = 0L
    }
}
