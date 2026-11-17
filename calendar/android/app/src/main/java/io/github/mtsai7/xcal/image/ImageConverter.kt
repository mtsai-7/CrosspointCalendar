package io.github.mtsai7.xcal.image

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import io.github.mtsai7.xcal.protocol.Xcal

/** Turns a picture into the X3's pinned-image BMP (spec §4.4). */
object ImageConverter {
    /** Result of a conversion: the BMP to send plus a same-size preview. */
    class Converted(val bmp: ByteArray, val preview: Bitmap)

    /** Decodes [uri] small enough to fit comfortably (never more than ~2× the screen). */
    fun decode(resolver: ContentResolver, uri: Uri): Bitmap {
        val source = ImageDecoder.createSource(resolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longSide = maxOf(info.size.width, info.size.height)
            var sample = 1
            while (longSide / (sample * 2) >= 2 * Xcal.IMAGE_H) sample *= 2
            decoder.setTargetSampleSize(sample)
        }
    }

    /** Rotation that shows [source] upright on the portrait screen: landscape turns 90°. */
    fun autoRotation(source: Bitmap): Int = if (source.width > source.height) 90 else 0

    /**
     * Rotates [source] by [rotation] degrees (multiple of 90), fits it centered on
     * a white 528 × 792 canvas, and quantizes it for [mode].
     */
    fun convert(source: Bitmap, rotation: Int, mode: XcalBitmap.Mode): Converted {
        val w = Xcal.IMAGE_W
        val h = Xcal.IMAGE_H
        val turned = if (rotation % 360 == 0) {
            source
        } else {
            val m = Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(source, 0, 0, source.width, source.height, m, true)
        }
        val canvasBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(canvasBitmap).apply {
            drawColor(Color.WHITE)
            val scale = minOf(w.toFloat() / turned.width, h.toFloat() / turned.height)
            val m = Matrix().apply {
                postScale(scale, scale)
                postTranslate((w - turned.width * scale) / 2f, (h - turned.height * scale) / 2f)
            }
            drawBitmap(turned, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        }
        if (turned !== source) turned.recycle()

        val argb = IntArray(w * h)
        canvasBitmap.getPixels(argb, 0, w, 0, 0, w, h)
        canvasBitmap.recycle()
        val lum = IntArray(argb.size) {
            val c = argb[it]
            // Transparent areas read as the white background.
            val a = c ushr 24
            val l = (77 * (c shr 16 and 0xFF) + 150 * (c shr 8 and 0xFF) + 29 * (c and 0xFF)) shr 8
            (l * a + 255 * (255 - a)) / 255
        }
        val levels = XcalBitmap.quantize(lum, w, h, mode)
        val bmp = XcalBitmap.encodeBmp(levels, w, h, mode.bpp)

        val top = if (mode == XcalBitmap.Mode.SHARP) 1 else 3
        val previewPixels = IntArray(levels.size) {
            val v = XcalBitmap.GRAY_LEVELS[levels[it] * 3 / top]
            Color.rgb(v, v, v)
        }
        val preview = Bitmap.createBitmap(previewPixels, w, h, Bitmap.Config.ARGB_8888)
        return Converted(bmp, preview)
    }
}
