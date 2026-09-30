package io.github.mtsai7.xcal.protocol

import java.io.ByteArrayOutputStream

/**
 * Text rules of spec §4.2. Must stay byte-identical to tools/xcal_protocol.py:
 * iterate by code point (not UTF-16 unit) and use the explicit White_Space set.
 */
object TextRules {
    private fun isWhiteSpace(cp: Int): Boolean =
        cp in 0x09..0x0D || cp == 0x20 || cp == 0x85 || cp == 0xA0 || cp == 0x1680 ||
            cp in 0x2000..0x200A || cp == 0x2028 || cp == 0x2029 || cp == 0x202F ||
            cp == 0x205F || cp == 0x3000

    private fun isControl(cp: Int): Boolean = cp < 0x20 || cp == 0x7F

    /** Controls -> space, split on White_Space runs, rejoin with single spaces. */
    fun sanitize(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        val out = StringBuilder(text.length)
        var pendingSpace = false
        text.codePoints().forEach { raw ->
            // Lone surrogates cannot be UTF-8 encoded; treat them as U+FFFD.
            val cp = if (raw in 0xD800..0xDFFF) 0xFFFD else raw
            if (isControl(cp) || isWhiteSpace(cp)) {
                pendingSpace = out.isNotEmpty()
            } else {
                if (pendingSpace) out.append(' ')
                pendingSpace = false
                out.appendCodePoint(cp)
            }
        }
        return out.toString()
    }

    /** UTF-8 encode, cutting on a code-point boundary to at most [maxBytes]. */
    fun truncateUtf8(text: String, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(maxBytes, text.length * 3))
        val it = text.codePoints().iterator()
        while (it.hasNext()) {
            val cp = it.next()
            val encoded = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8)
            if (out.size() + encoded.size > maxBytes) break
            out.write(encoded)
        }
        return out.toByteArray()
    }

    /** Unsigned lexicographic comparison of encoded titles (= code-point order). */
    fun compareBytes(a: ByteArray, b: ByteArray): Int {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            val d = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (d != 0) return d
        }
        return a.size - b.size
    }
}
