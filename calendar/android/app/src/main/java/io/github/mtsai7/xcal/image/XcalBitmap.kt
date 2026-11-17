package io.github.mtsai7.xcal.image

import io.github.mtsai7.xcal.protocol.Xcal
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater

/**
 * Pinned-image encoding (spec §4.4), free of Android APIs so it runs in JVM tests.
 * Pixels are gray levels, row-major from the top: 0..1 for 1 bpp, 0..3 for
 * 2 bpp, 0 = black.
 */
object XcalBitmap {
    /** Gray values of the X3's four native levels. */
    val GRAY_LEVELS = intArrayOf(0, 85, 170, 255)

    enum class Mode(val bpp: Int) {
        /** Black and white at a fixed threshold: text and codes stay crisp. */
        SHARP(1),

        /** Four grays with Floyd–Steinberg dithering: photos. */
        PHOTO(2),
    }

    /** Quantizes luminance (0..255, row-major, [width] × [height]) to the mode's levels. */
    fun quantize(lum: IntArray, width: Int, height: Int, mode: Mode): ByteArray {
        require(lum.size == width * height) { "size mismatch" }
        val out = ByteArray(lum.size)
        when (mode) {
            Mode.SHARP -> for (i in lum.indices) out[i] = if (lum[i] >= SHARP_THRESHOLD) 1 else 0
            Mode.PHOTO -> {
                // Error diffusion over a float copy, so the input stays untouched.
                val work = FloatArray(lum.size) { lum[it].toFloat() }
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val i = y * width + x
                        val old = work[i].coerceIn(0f, 255f)
                        val level = ((old + 42.5f) / 85f).toInt().coerceIn(0, 3)
                        out[i] = level.toByte()
                        val err = old - GRAY_LEVELS[level]
                        if (x + 1 < width) work[i + 1] += err * 7 / 16
                        if (y + 1 < height) {
                            if (x > 0) work[i + width - 1] += err * 3 / 16
                            work[i + width] += err * 5 / 16
                            if (x + 1 < width) work[i + width + 1] += err * 1 / 16
                        }
                    }
                }
            }
        }
        return out
    }

    /** BMP file (BITMAPINFOHEADER, BI_RGB, bottom-up) of [levels] at [bpp] (1 or 2). */
    fun encodeBmp(levels: ByteArray, width: Int, height: Int, bpp: Int): ByteArray {
        require(bpp == 1 || bpp == 2) { "bpp must be 1 or 2" }
        require(levels.size == width * height) { "size mismatch" }
        val palette = if (bpp == 1) intArrayOf(0, 255) else GRAY_LEVELS
        val rowBytes = (width * bpp + 31) / 32 * 4
        val pixelBytes = rowBytes * height
        val dataOffset = 14 + 40 + palette.size * 4
        val buf = ByteBuffer.allocate(dataOffset + pixelBytes).order(ByteOrder.LITTLE_ENDIAN)
        // BITMAPFILEHEADER
        buf.put('B'.code.toByte()).put('M'.code.toByte())
        buf.putInt(dataOffset + pixelBytes).putShort(0).putShort(0).putInt(dataOffset)
        // BITMAPINFOHEADER
        buf.putInt(40).putInt(width).putInt(height).putShort(1).putShort(bpp.toShort())
        buf.putInt(0).putInt(pixelBytes).putInt(2835).putInt(2835).putInt(palette.size).putInt(0)
        for (v in palette) buf.put(v.toByte()).put(v.toByte()).put(v.toByte()).put(0)
        // Pixels, bottom row first, MSB-first packing, rows zero-padded.
        val row = ByteArray(rowBytes)
        for (y in height - 1 downTo 0) {
            row.fill(0)
            var bits = 0
            var nbits = 0
            var at = 0
            for (x in 0 until width) {
                bits = (bits shl bpp) or levels[y * width + x].toInt()
                nbits += bpp
                if (nbits == 8) {
                    row[at++] = bits.toByte()
                    bits = 0
                    nbits = 0
                }
            }
            if (nbits > 0) row[at] = (bits shl (8 - nbits)).toByte()
            buf.put(row)
        }
        return buf.array()
    }

    /** The image stream served over IMAGE_DATA: the BMP, zlib-wrapped (RFC 1950). */
    fun encodeStream(bmp: ByteArray): ByteArray {
        require(bmp.size <= Xcal.MAX_IMAGE_BMP) { "image too large" }
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        try {
            deflater.setInput(bmp)
            deflater.finish()
            val out = ByteArrayOutputStream(bmp.size / 4)
            val chunk = ByteArray(8192)
            while (!deflater.finished()) {
                val n = deflater.deflate(chunk)
                out.write(chunk, 0, n)
            }
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    /** IMAGE_DATA value after IMAGE_OFFSET = [offset]: a 512 B window, empty past the end. */
    fun window(stream: ByteArray, offset: Long): ByteArray {
        if (offset < 0 || offset >= stream.size) return ByteArray(0)
        val from = offset.toInt()
        return stream.copyOfRange(from, minOf(stream.size, from + Xcal.IMAGE_WINDOW))
    }

    private const val SHARP_THRESHOLD = 128
}
