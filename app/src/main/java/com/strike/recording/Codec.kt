package com.strike.recording

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.IOException

// The head unit's WebView cannot decode HEVC in a video element, so every screen
// that offers playback has to name the codec the clip was actually written with.
fun codecOf(clip: File): String? {
    val extractor = MediaExtractor()
    return try {
        extractor.setDataSource(clip.absolutePath)
        videoCodec(extractor)
    } catch (e: IOException) {
        null
    } catch (e: RuntimeException) {
        null
    } finally {
        extractor.release()
    }
}

private fun videoCodec(extractor: MediaExtractor): String? {
    for (track in 0 until extractor.trackCount) {
        val mime = extractor.getTrackFormat(track).getString(MediaFormat.KEY_MIME) ?: continue
        if (!mime.startsWith("video/")) continue
        return codecNameOf(mime)
    }
    return null
}

internal fun codecNameOf(mime: String): String? = when (mime) {
    MediaFormat.MIMETYPE_VIDEO_HEVC -> "h265"
    MediaFormat.MIMETYPE_VIDEO_AVC -> "h264"
    else -> null
}
