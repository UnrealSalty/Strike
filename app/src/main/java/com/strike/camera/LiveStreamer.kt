package com.strike.camera

import android.media.MediaCodec
import android.media.MediaFormat
import com.strike.daemon.DaemonLog
import com.strike.daemon.PACKET_FLAG_CONFIG
import com.strike.daemon.PACKET_FLAG_KEYFRAME
import com.strike.daemon.PacketRelay
import com.strike.recording.Encoder
import com.strike.recording.Sample

private const val TAG = "Live"

const val LIVE_FRAME_RATE_FPS = 12
const val LIVE_BITRATE_BPS = 1_500_000

private const val CONSUMER = "live"

// H.264 Baseline keeps Live compatible with the head unit's MSE decoder.
class LiveStreamer(private val relay: PacketRelay) {

    private var bus: FrameBus? = null
    private var encoder: Encoder? = null
    private var sentConfig = false
    private var bitrateBps = 0

    @Volatile
    private var frames = 0L

    @Volatile
    var isStreaming = false
        private set

    @Volatile
    var view = CameraView.FRONT
        private set

    fun start(bus: FrameBus, view: CameraView, frameRateFps: Int, bitrateBps: Int): Boolean {
        if (isStreaming) return true
        val frame = liveFrameOf(view, bus.stripWidth, bus.stripHeight)
        val fresh = Encoder(
            frame.width,
            frame.height,
            frameRateFps,
            bitrateBps,
            MediaFormat.MIMETYPE_VIDEO_AVC,
            ::relay
        )
        val surface = fresh.start() ?: return false
        bus.add(Consumer(CONSUMER, surface, view, frame))
        this.bus = bus
        this.view = view
        this.bitrateBps = bitrateBps
        encoder = fresh
        sentConfig = false
        frames = 0
        isStreaming = true
        DaemonLog.d(TAG, "live ${view.id} at ${frame.width}x${frame.height}, $bitrateBps bps")
        return true
    }

    fun stop() {
        if (!isStreaming) return
        isStreaming = false
        bus?.remove(CONSUMER)
        bus = null
        encoder?.stop()
        encoder = null
    }

    fun adjustBitrate(bitrateBps: Int): Boolean {
        if (this.bitrateBps == bitrateBps) return true
        if (encoder?.setBitrate(bitrateBps) != true) return false
        this.bitrateBps = bitrateBps
        return true
    }

    // Repeat SPS/PPS before keyframes so viewers can join an existing stream.
    private fun relay(sample: Sample) {
        val header = encoder?.format
        val keyFrame = sample.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        frames++
        if (frames == 1L) DaemonLog.d(TAG, "first live frame, ${sample.bytes.size} bytes")
        if (frames % 240 == 0L) DaemonLog.d(TAG, "$frames live frames encoded")
        if (keyFrame || !sentConfig) {
            val config = parameterSets(header)
            if (config != null) {
                if (!sentConfig) DaemonLog.d(TAG, "live config ${config.size} bytes")
                relay.send(config, sample.timeUs, PACKET_FLAG_CONFIG)
                sentConfig = true
            }
        }
        relay.send(sample.bytes, sample.timeUs, if (keyFrame) PACKET_FLAG_KEYFRAME else 0)
    }
}

internal fun parameterSets(format: MediaFormat?): ByteArray? =
    annexB(csd(format, "csd-0"), csd(format, "csd-1"))

// Qualcomm supplies SPS in csd-0 and PPS in csd-1; browsers need both.
internal fun annexB(sps: ByteArray?, pps: ByteArray?): ByteArray? {
    if (sps == null || sps.isEmpty()) return null
    val head = withStart(sps)
    if (pps == null || pps.isEmpty()) return head
    return head + withStart(pps)
}

private fun csd(format: MediaFormat?, key: String): ByteArray? {
    val buffer = try {
        format?.getByteBuffer(key)
    } catch (e: NullPointerException) {
        null
    } ?: return null
    if (buffer.remaining() == 0) return null
    val bytes = ByteArray(buffer.remaining())
    buffer.duplicate().get(bytes)
    return bytes
}

private val START = byteArrayOf(0, 0, 0, 1)

private fun withStart(nal: ByteArray): ByteArray {
    if (nal.size >= 3 && nal[0] == 0.toByte() && nal[1] == 0.toByte() && nal[2] == 1.toByte()) {
        return nal
    }
    if (nal.size >= 4 && nal[0] == 0.toByte() && nal[1] == 0.toByte() &&
        nal[2] == 0.toByte() && nal[3] == 1.toByte()
    ) {
        return nal
    }
    return START + nal
}
