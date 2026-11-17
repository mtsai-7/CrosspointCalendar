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

    /** Decoration-only combining marks (DECORATIVE_MARK_RANGES in the script). */
    private fun isDecorativeMark(cp: Int): Boolean =
        cp == 0x0305 || cp in 0x0332..0x0338 || cp == 0x033F || cp in 0x0488..0x0489 ||
            cp in 0x1AB0..0x1AFF || cp in 0x20D0..0x20F0 || cp in 0xFE20..0xFE2F

    /** What the X3's title and location fonts can draw (DRAWABLE_RANGES in the script). */
    private fun isDrawable(cp: Int): Boolean =
        cp in 0x0020..0x007E || cp in 0x00A0..0x017F || cp in 0x01A0..0x01A1 || cp in 0x01AF..0x01B0 ||
            cp in 0x01C4..0x021F || cp in 0x0300..0x036F || cp in 0x0400..0x04FF || cp in 0x1EA0..0x1EF9 ||
            cp in 0x2000..0x2064 || cp in 0x2066..0x2071 || cp in 0x2074..0x208E || cp in 0x2090..0x209C ||
            cp in 0x20A0..0x20C0 || cp == 0x2212 || cp in 0xFB00..0xFB06 || cp == 0xFFFD

    /** "Xy Zw ..." -> {X: "y", Z: "w"}; a lone key maps to "" (deleted). */
    private fun pairs(table: String): Map<Int, String> =
        table.split(' ').filter { it.isNotEmpty() }.associate {
            val key = it.codePointAt(0)
            key to it.substring(Character.charCount(key))
        }

    /** Fancy-text lookalikes -> Latin (LOOKALIKES in the script; keep identical). */
    private val lookalikes = pairs(
            "ᗩA ᗅA ᗷB ᑕC ᗪD ᗴE ᖴF ᘜG ᕼH ᒍJ ᛕK ᒪL ᗰM ᑎN ᗝO ᑭP ᑫQ ᖇR ᔕS ᑌU ᐯV ᗯW ᙭X ᘔZ 卂A 乃B" +
            " 匚C 乇E 千F Ꮆg 卄H 丨I ﾌJ ズK ㄥL 爪M 几N ㄖO 卩P Ɋq 尺R 丂S ㄒT ㄩU 山W 乂X ㄚY 乙Z คa ๒b ςc ๔d" +
            " єe Ŧf ﻮg ђh เi ןj кk ɭl ๓m ภn ๏o קp ợq гr รs Շt ยu שv ฬw אx ץy չz αa βB γy δd" +
            " εe ζz ηn ιi κk νv οo πn ρp σo τt υu χx ωw ΑA ΒB ΕE ΖZ ΗH ΙI ΚK ΜM ΝN ΟO ΡP ΤT" +
            " ΥY ΧX аa вb дd еe ѕs зz иu йu лn мm нh оo пn рp сc тt уy хx шw щw ъb ьb яr ёe" +
            " іi їi јj ћh ҝk ԝw АA ВB ЕE ЗE КK МM НH ОO РP СC ТT УY ХX ШW ЩW ЯR ЬB ЪB 『 』 「 」" +
            " 【 】 〖 〗 〔 〕 《 》",
        )

    /** Upside-down letters, decoded after reversing the text (FLIPPED / FLIPPED_ASCII). */
    private val flipped = pairs(
            "ɐa ɔc ǝe ɟf ƃg ɥh ᴉi ıi ɾj ʞk ꞁl ɯm ɹr ʇt ʌv ʍw ʎy ∀A ꓭB ƆC ꓷD ƎE ℲF ⅁G ꓘK ˥L" +
            " ԀP ꓤR ⊥T ∩U ΛV ⅄Y ¿? ¡! ˙.",
        )
    private val flippedAscii = pairs("bq qb dp pd nu un MW WM")

    /**
     * Rewrites fancy-text lookalikes the X3 cannot draw back to Latin, but only
     * when every undrawable character in [text] is a known lookalike, so genuine
     * non-Latin text is left alone.
     */
    private fun decodeLookalikes(text: String): String {
        val cps = text.codePoints().toArray()
        val undrawable = cps.filterNot { isDrawable(it) }.toSet()
        if (undrawable.isEmpty()) return text
        val out = StringBuilder(text.length)
        when {
            flipped.keys.containsAll(undrawable) ->
                for (cp in cps.reversedArray()) {
                    val mapped = flipped[cp] ?: flippedAscii[cp]
                    if (mapped != null) out.append(mapped) else out.appendCodePoint(cp)
                }
            lookalikes.keys.containsAll(undrawable) ->
                for (cp in cps) {
                    val mapped = lookalikes[cp]
                    if (mapped != null) out.append(mapped) else out.appendCodePoint(cp)
                }
            else -> return text
        }
        // Deleted brackets can leave empty words.
        return out.split(' ').filter { it.isNotEmpty() }.joinToString(" ")
    }

    /**
     * NFKC, fold styled letters, drop pictographs and decorative marks, controls ->
     * space, split on White_Space runs, rejoin with single spaces, then decode
     * fancy-text lookalikes the X3 cannot draw.
     */
    fun sanitize(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
        val out = StringBuilder(normalized.length)
        var pendingSpace = false
        normalized.codePoints().forEach { raw ->
            // Lone surrogates cannot be UTF-8 encoded; treat them as U+FFFD.
            val cp = if (raw in 0xD800..0xDFFF) 0xFFFD else foldLetter(raw)
            if (isPictograph(cp) || isDecorativeMark(cp)) return@forEach
            if (isControl(cp) || isWhiteSpace(cp)) {
                pendingSpace = out.isNotEmpty()
            } else {
                if (pendingSpace) out.append(' ')
                pendingSpace = false
                out.appendCodePoint(cp)
            }
        }
        return decodeLookalikes(out.toString())
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
