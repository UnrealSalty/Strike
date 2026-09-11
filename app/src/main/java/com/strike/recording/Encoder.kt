package com.strike.recording

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
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
    var isDead = false
        private set

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

        val attempted = HashSet<String>()
        val failures = ArrayList<String>()
        val opened = firstEncoder(
            preferred = { open(wanted, null, attempted, failures) },
            alternatives = { alternatives(wanted, attempted, failures) },
            start = { open(wanted, it, attempted, failures) }
        )
        if (opened == null) {
            DaemonLog.e(TAG, "no encoder could start $mimeType ${width}x$height at " +
                "$frameRateFps fps, $bitrateBps bps: ${failures.joinToString("; ")}")
            return null
        }
        val (fresh, surface) = opened
        codec = fresh
        inputSurface = surface
        running = true
        DaemonLog.d(TAG, "${width}x$height on ${fresh.codecInfo.name}, ${instances(fresh)} at once")
        drain = Thread({ pump(fresh) }, "encoder").also { it.start() }
        return surface
    }

    private fun open(
        wanted: MediaFormat,
        name: String?,
        attempted: MutableSet<String>,
        failures: MutableList<String>
    ): Pair<MediaCodec, Surface>? {
        var fresh: MediaCodec? = null
        var surface: Surface? = null
        var started = false
        var label = name ?: "default"
        try {
            fresh = if (name == null) MediaCodec.createEncoderByType(mimeType)
                else MediaCodec.createByCodecName(name)
            label = fresh.name
            attempted.add(label)
            if (Build.VERSION.SDK_INT >= 29) attempted.add(fresh.canonicalName)
            if (!encodable(fresh)) {
                failures.add("$label does not support ${width}x$height")
                return null
            }
            fresh.configure(wanted, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = fresh.createInputSurface()
            fresh.start()
            started = true
            return Pair(fresh, surface)
        } catch (e: IOException) {
            failures.add("$label: ${e.message}")
        } catch (e: IllegalStateException) {
            failures.add("$label: ${e.message}")
        } catch (e: IllegalArgumentException) {
            failures.add("$label: ${e.message}")
        } finally {
            if (!started) {
                try {
                    fresh?.release()
                } catch (e: IllegalStateException) {
                    failures.add("$label could not release: ${e.message}")
                } finally {
                    surface?.release()
                }
            }
        }
        return null
    }

    private fun alternatives(
        wanted: MediaFormat,
        attempted: MutableSet<String>,
        failures: MutableList<String>
    ): Sequence<String> = sequence {
        val available = try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        } catch (e: RuntimeException) {
            failures.add("encoder list unavailable: ${e.message}")
            return@sequence
        }
        for (candidate in available) {
            if (!candidate.isEncoder) continue
            val hardware = if (Build.VERSION.SDK_INT >= 29) candidate.isHardwareAccelerated
                else legacyHardwareEncoder(candidate.name)
            if (!hardware) continue
            val name = if (Build.VERSION.SDK_INT >= 29) candidate.canonicalName else candidate.name
            if (!attempted.add(name)) continue
            val supported = try {
                candidate.getCapabilitiesForType(mimeType).isFormatSupported(wanted)
            } catch (e: IllegalArgumentException) {
                false
            }
            if (supported) yield(candidate.name)
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
        return video.isSizeSupported(width, height)
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

    fun setBitrate(bitrateBps: Int): Boolean {
        val active = codec ?: return false
        return try {
            active.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitrateBps) })
            true
        } catch (e: IllegalStateException) {
            DaemonLog.w(TAG, "live bitrate change needs an encoder restart")
            false
        } catch (e: IllegalArgumentException) {
            DaemonLog.w(TAG, "live bitrate change is not supported while encoding")
            false
        }
    }

    private fun pump(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        val startedAtMs = System.currentTimeMillis()
        var outputs = 0L
        var reported = false
        try {
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
        } finally {
            if (running) isDead = true
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

// Firmware capability reports can be incomplete; keep a working default before considering alternatives.
internal fun <T : Any> firstEncoder(
    preferred: () -> T?,
    alternatives: () -> Sequence<String>,
    start: (String) -> T?
): T? {
    preferred()?.let { return it }
    for (name in alternatives()) start(name)?.let { return it }
    return null
}

// Android 9 has no hardware-acceleration flag; software components must be excluded by name.
internal fun legacyHardwareEncoder(name: String): Boolean {
    val lower = name.lowercase(java.util.Locale.ROOT)
    return (lower.startsWith("omx.") || lower.startsWith("c2.")) &&
        !lower.startsWith("omx.google.") && !lower.startsWith("c2.android.") &&
        !lower.startsWith("c2.google.") && !lower.startsWith("omx.ffmpeg.") &&
        !lower.contains(".sw.") && !lower.contains(".sw_") && !lower.endsWith(".sw") &&
        !lower.startsWith("omx.sec.")
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
