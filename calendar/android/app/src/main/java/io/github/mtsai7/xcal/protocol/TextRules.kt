package io.github.mtsai7.xcal.protocol

import java.io.ByteArrayOutputStream
import java.text.Normalizer

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

    /** Emoji, emoji-sequence glue and pictographic symbols the X3 fonts cannot draw (PICTOGRAPH_RANGES). */
    private fun isPictograph(cp: Int): Boolean =
        cp == 0x200D || cp == 0x20E3 || cp in 0xFE00..0xFE0F || cp in 0xE0020..0xE007F ||
            cp in 0x2190..0x21FF || cp in 0x2300..0x23FF || cp in 0x25A0..0x27BF || cp in 0x2900..0x297F ||
            cp in 0x2B00..0x2BFF || cp in 0x1F000..0x1FAFF

    /** "Fancy text" letters NFKC leaves alone: small capitals -> lowercase (SMALL_CAPS in the script). */
    private val smallCaps: Map<Int, Int> = "ᴀa ʙb ᴄc ᴅd ᴇe ꜰf ɢg ʜh ɪi ᴊj ᴋk ʟl ᴍm ɴn ᴏo ᴘp ꞯq ʀr ꜱs ᴛt ᴜu ᴠv ᴡw ʏy ᴢz"
        .split(' ').associate { it.codePointAt(0) to it.codePointAt(it.offsetByCodePoints(0, 1)) }

    /** Plain letter for a styled letter NFKC does not decompose, else [cp] unchanged. */
    private fun foldLetter(cp: Int): Int = when (cp) {
        in smallCaps -> smallCaps.getValue(cp)
        in 0x1F150..0x1F169 -> 'A'.code + cp - 0x1F150 // negative circled capitals
        in 0x1F170..0x1F189 -> 'A'.code + cp - 0x1F170 // negative squared capitals
        else -> cp
    }

    /** NFKC, fold styled letters, drop pictographs, controls -> space, split on White_Space runs, rejoin with single spaces. */
    fun sanitize(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
        val out = StringBuilder(normalized.length)
        var pendingSpace = false
        normalized.codePoints().forEach { raw ->
            // Lone surrogates cannot be UTF-8 encoded; treat them as U+FFFD.
            val cp = if (raw in 0xD800..0xDFFF) 0xFFFD else foldLetter(raw)
            if (isPictograph(cp)) return@forEach
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
