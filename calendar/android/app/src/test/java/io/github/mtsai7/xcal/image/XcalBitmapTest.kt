package io.github.mtsai7.xcal.image

import io.github.mtsai7.xcal.protocol.PayloadCodec
import io.github.mtsai7.xcal.protocol.Xcal
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.Inflater

/** Pinned image encoding (spec §4.4); vectors from tools/xcal_protocol.py. */
class XcalBitmapTest {
    /** vector_image_bmp() in the script: 8 px checker blocks over a level ramp. */
    private fun vectorLevels(bpp: Int): ByteArray {
        val top = (1 shl bpp) - 1
        val w = Xcal.IMAGE_W
        val h = Xcal.IMAGE_H
        return ByteArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val band = minOf(top, y * (top + 1) / h)
            (if ((x / 8 + y / 8) % 2 == 1) band else top - band).toByte()
        }
    }

    @Test
    fun bmpVectorsMatchTheScript() {
        val bmp1 = XcalBitmap.encodeBmp(vectorLevels(1), Xcal.IMAGE_W, Xcal.IMAGE_H, 1)
        assertEquals(53918, bmp1.size)
        assertEquals(0x64d1bd7dL, PayloadCodec.crc32(bmp1))
        val bmp2 = XcalBitmap.encodeBmp(vectorLevels(2), Xcal.IMAGE_W, Xcal.IMAGE_H, 2)
        assertEquals(104614, bmp2.size)
        assertEquals(0xb4cfd680L, PayloadCodec.crc32(bmp2))
    }

    @Test
    fun streamRoundTripsAndWindowsCoverIt() {
        val bmp = XcalBitmap.encodeBmp(vectorLevels(2), Xcal.IMAGE_W, Xcal.IMAGE_H, 2)
        val stream = XcalBitmap.encodeStream(bmp)
        assertEquals(0x78, stream[0].toInt() and 0xFF) // zlib header (RFC 1950)
        val inflater = Inflater()
        inflater.setInput(stream)
        val out = ByteArray(bmp.size + 1)
        val n = inflater.inflate(out)
        assertTrue(inflater.finished())
        inflater.end()
        assertArrayEquals(bmp, out.copyOf(n))

        val joined = (0 until stream.size step Xcal.IMAGE_WINDOW)
            .flatMap { XcalBitmap.window(stream, it.toLong()).toList() }.toByteArray()
        assertArrayEquals(stream, joined)
        assertEquals(0, XcalBitmap.window(stream, stream.size.toLong()).size)
    }

    @Test
    fun sharpThresholdsAndPhotoUsesFourLevels() {
        val lum = intArrayOf(0, 127, 128, 255)
        assertArrayEquals(byteArrayOf(0, 0, 1, 1), XcalBitmap.quantize(lum, 4, 1, XcalBitmap.Mode.SHARP))
        // Exact native grays need no dithering.
        val grays = intArrayOf(0, 85, 170, 255)
        assertArrayEquals(byteArrayOf(0, 1, 2, 3), XcalBitmap.quantize(grays, 4, 1, XcalBitmap.Mode.PHOTO))
        // A flat mid-gray dithers to a mix of its neighbours, averaging close to it.
        val flat = IntArray(64 * 64) { 128 }
        val q = XcalBitmap.quantize(flat, 64, 64, XcalBitmap.Mode.PHOTO)
        assertTrue(q.all { it in 1..2 })
        val mean = q.sumOf { XcalBitmap.GRAY_LEVELS[it.toInt()] }.toDouble() / q.size
        assertEquals(128.0, mean, 2.0)
    }
}
