package com.strike.server.api

import com.strike.recording.Thumb
import com.strike.recording.ThumbResult
import com.strike.server.JSON
import com.strike.server.Response
import com.strike.server.notFound

internal const val DURATION_HEADER = "X-Clip-Duration-Ms"
internal const val CODEC_HEADER = "X-Clip-Codec"

internal fun thumbHeaders(thumb: Thumb): Map<String, String> {
    val headers = HashMap<String, String>()
    headers[DURATION_HEADER] = thumb.durationMs.toString()
    thumb.codec?.let { headers[CODEC_HEADER] = it }
    return headers
}

internal fun thumbnailResponse(result: ThumbResult): Response = when (result) {
    is ThumbResult.Ready -> Response(
        200, "image/jpeg", result.thumb.jpeg, headers = thumbHeaders(result.thumb)
    )
    ThumbResult.Missing -> notFound()
    ThumbResult.Pending, ThumbResult.Busy -> Response(
        if (result == ThumbResult.Pending) 202 else 503,
        JSON, "{\"pending\":true}".toByteArray(), headers = mapOf("Retry-After" to "1")
    )
}
