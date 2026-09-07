package com.strike.recording

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.view.Surface
import com.strike.daemon.DaemonLog
import java.io.IOException

private const val TAG = "Encoder"
private const val DEQUEUE_TIMEOUT_US = 10_000L

/** A keyframe normally lands within a second; this only bounds the wait. */
private const val SPLICE_DEADLINE_MS = 3_000L

/** Long enough that a slow first keyframe is not mistaken for a dead encoder. */
private const val MUTE_MS = 5_000L

class Sample(val bytes: ByteArray, val timeUs: Long, val flags: Int, val startsClip: Boolean)

// Surface input stays on the GPU; the drain thread returns encoded samples.
class Encoder(
    private val width: Int,
    private val height: Int,
    private val frameRateFps: Int,
    private val bitrateBps: Int,
    private val mimeType: String,
    private val onSample: (Sample) -> Unit
) {

    @Volatile
    var format: MediaFormat? = null
        private set

    @Volatile
    private var running = false

    @Volatile
    private var rotateAskedAtMs = 0L

    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var drain: Thread? = null

    fun start(): Surface? {
        val wanted = MediaFormat.createVideoFormat(mimeType, width, height)
        wanted.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        wanted.setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
        wanted.setInteger(MediaFormat.KEY_FRAME_RATE, frameRateFps)
        wanted.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        wanted.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, width * height * 3 / 2)
        if (mimeType == MediaFormat.MIMETYPE_VIDEO_HEVC) {
            wanted.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
            wanted.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel4)
        } else {
            wanted.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            wanted.setInteger(MediaFormat.KEY_LEVEL, avcLevelFor(width, height))
        }

        return try {
            val fresh = MediaCodec.createEncoderByType(mimeType)
            codec = fresh
            if (!encodable(fresh)) {
                fresh.release()
                codec = null
                return null
            }
            fresh.configure(wanted, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = fresh.createInputSurface()
            inputSurface = surface
            fresh.start()
            running = true
            DaemonLog.d(TAG, "${width}x$height on ${fresh.codecInfo.name}, ${instances(fresh)} at once")
            drain = Thread({ pump(fresh) }, "encoder").also { it.start() }
            surface
        } catch (e: IOException) {
            DaemonLog.e(TAG, "no $mimeType encoder on this device: ${e.message}")
            null
        } catch (e: IllegalStateException) {
            DaemonLog.e(TAG, "encoder refused ${width}x$height at $bitrateBps bps: ${e.message}")
            release()
            null
        } catch (e: IllegalArgumentException) {
            DaemonLog.e(TAG, "encoder refused ${width}x$height at $bitrateBps bps: ${e.message}")
            release()
            null
        }
    }

    private fun instances(codec: MediaCodec): String = try {
        codec.codecInfo.getCapabilitiesForType(mimeType).maxSupportedInstances.toString()
    } catch (e: IllegalArgumentException) {
        "unknown"
    }

    private fun encodable(codec: MediaCodec): Boolean {
        val video = try {
            codec.codecInfo.getCapabilitiesForType(mimeType).videoCapabilities
        } catch (e: IllegalArgumentException) {
            return true
        }
        if (video.isSizeSupported(width, height)) return true
        DaemonLog.e(
            TAG,
            "the encoder cannot take ${width}x$height, it stops at " +
                "${video.supportedWidths.upper}x${video.supportedHeights.upper}"
        )
        return false
    }

    fun splitAtNextKeyFrame() {
        if (rotateAskedAtMs != 0L) return
        rotateAskedAtMs = System.currentTimeMillis()
        try {
            val request = Bundle()
            request.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            codec?.setParameters(request)
        } catch (e: IllegalStateException) {
            DaemonLog.w(TAG, "cannot ask for a keyframe")
        }
    }

    fun stop() {
        running = false
        drain?.join(1_000)
        drain = null
        release()
    }

    private fun pump(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        val startedAtMs = System.currentTimeMillis()
        var outputs = 0L
        var reported = false
        while (running) {
            val index = try {
                codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
            } catch (e: IllegalStateException) {
                DaemonLog.e(TAG, "encoder stopped answering")
                return
            }
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                format = codec.outputFormat
                continue
            }
            if (index < 0) {
                if (!reported && outputs == 0L &&
                    System.currentTimeMillis() - startedAtMs > MUTE_MS
                ) {
                    reported = true
                    DaemonLog.e(
                        TAG,
                        "the ${width}x$height encoder has returned nothing in ${MUTE_MS / 1000}s"
                    )
                }
                continue
            }
            outputs++

            // SPS and PPS reach the file through the format handed to addTrack.
            val skip = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 || info.size == 0
            if (!skip) {
                val buffer = codec.getOutputBuffer(index)
                if (buffer != null) {
                    val bytes = ByteArray(info.size)
                    buffer.position(info.offset)
                    buffer.get(bytes)
                    val keyFrame = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                    onSample(Sample(bytes, info.presentationTimeUs, info.flags, splits(keyFrame)))
                }
            }
            codec.releaseOutputBuffer(index, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
        }
    }

    private fun splits(keyFrame: Boolean): Boolean {
        if (!splitsNow(keyFrame, rotateAskedAtMs, System.currentTimeMillis())) return false
        rotateAskedAtMs = 0L
        if (!keyFrame) DaemonLog.w(TAG, "split without a keyframe, the clip head may not draw")
        return true
    }

    private fun release() {
        try {
            codec?.stop()
        } catch (e: IllegalStateException) {
            DaemonLog.w(TAG, "encoder stop failed")
        }
        try {
            codec?.release()
        } catch (e: IllegalStateException) {
            DaemonLog.w(TAG, "encoder release failed: ${e.message}")
        } finally {
            codec = null
            inputSurface?.release()
            inputSurface = null
        }
    }
}

/** H.264 Table A-1, MaxFS in macroblocks against the level that allows it. */
private val AVC_LEVELS = listOf(
    1620 to MediaCodecInfo.CodecProfileLevel.AVCLevel3,
    3600 to MediaCodecInfo.CodecProfileLevel.AVCLevel31,
    5120 to MediaCodecInfo.CodecProfileLevel.AVCLevel32,
    8192 to MediaCodecInfo.CodecProfileLevel.AVCLevel4,
    8704 to MediaCodecInfo.CodecProfileLevel.AVCLevel42,
    22080 to MediaCodecInfo.CodecProfileLevel.AVCLevel5,
    36864 to MediaCodecInfo.CodecProfileLevel.AVCLevel51
)

// Choose the H.264 level for the frame size; browsers can reject an underspecified SPS.
internal fun avcLevelFor(width: Int, height: Int): Int {
    val macroblocks = ((width + 15) / 16) * ((height + 15) / 16)
    for ((limit, level) in AVC_LEVELS) {
        if (macroblocks <= limit) return level
    }
    return MediaCodecInfo.CodecProfileLevel.AVCLevel52
}

// Rotate on a keyframe, with a bounded wait if the encoder stops producing them.
internal fun splitsNow(keyFrame: Boolean, askedAtMs: Long, nowMs: Long): Boolean {
    if (askedAtMs == 0L) return false
    return keyFrame || nowMs - askedAtMs >= SPLICE_DEADLINE_MS
}
