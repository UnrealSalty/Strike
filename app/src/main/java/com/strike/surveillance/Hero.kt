package com.strike.surveillance

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.strike.camera.Mosaic
import com.strike.daemon.DaemonFonts
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

private const val QUALITY = 80
private const val STROKE = 3f
private const val LABEL_SIZE = 22f
private const val LABEL_PAD = 6f

// Draw detection boxes only on the sidecar JPEG; leave recorded frames unchanged.
internal fun heroJpeg(mosaic: Mosaic, sighting: Sighting): ByteArray {
    val frame = Bitmap.createBitmap(mosaic.width, mosaic.height, Bitmap.Config.ARGB_8888)
    frame.copyPixelsFromBuffer(ByteBuffer.wrap(mosaic.rgba))
    val canvas = Canvas(frame)

    val box = Paint()
    box.isAntiAlias = true
    box.style = Paint.Style.STROKE
    box.strokeWidth = STROKE
    box.color = Color.WHITE
    val right = (sighting.x + sighting.width).toFloat()
    val bottom = (sighting.y + sighting.height).toFloat()
    canvas.drawRect(sighting.x.toFloat(), sighting.y.toFloat(), right, bottom, box)

    val label = if (sighting.seen == PERSON) "Person" else "Vehicle"
    val text = Paint()
    DaemonFonts.apply(text)
    text.isAntiAlias = true
    text.color = Color.WHITE
    text.textSize = LABEL_SIZE
    val width = if (DaemonFonts.canDraw) text.measureText(label) else 0f
    val plate = Paint()
    plate.color = Color.BLACK
    val top = Math.max(0f, sighting.y - LABEL_SIZE - LABEL_PAD * 2)
    canvas.drawRect(
        sighting.x.toFloat(),
        top,
        sighting.x + width + LABEL_PAD * 2,
        top + LABEL_SIZE + LABEL_PAD * 2,
        plate
    )
    if (DaemonFonts.canDraw) {
        canvas.drawText(label, sighting.x + LABEL_PAD, top + LABEL_SIZE + LABEL_PAD / 2, text)
    }

    val jpeg = ByteArrayOutputStream()
    frame.compress(Bitmap.CompressFormat.JPEG, QUALITY, jpeg)
    frame.recycle()
    return jpeg.toByteArray()
}
