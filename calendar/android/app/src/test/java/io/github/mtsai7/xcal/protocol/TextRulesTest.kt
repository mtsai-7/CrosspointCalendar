package io.github.mtsai7.xcal.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

/** Mirrors SANITIZE_CASES in tools/xcal_protocol.py. */
class TextRulesTest {
    @Test
    fun sanitizeCases() {
        val cases = listOf(
            "" to "",
            "  Standup  " to "Standup",
            "Late\tnight  deploy" to "Late night deploy",
            "a  b" to "a b",
            "a​b" to "a​b",
            "line1\r\nline2" to "line1 line2",
            "x\u0000y\u007fz" to "x y z",
            "　Tokyo　" to "Tokyo",
            "tab\u0085nel" to "tab nel",
            "𝐁𝐨𝐥𝐝 𝓼𝓬𝓻𝓲𝓹𝓽" to "Bold script",
            "Ｔｅａｍ Ⓐ" to "Team A",
            "Team 🎉 lunch 🍕" to "Team lunch",
            "👨‍💻 Dev sync ✅️" to "Dev sync",
            "🇺🇸 Holiday" to "Holiday",
            "Café → office" to "Café office",
            "😀" to "",
            "ʙᴇᴘ ʀᴇᴠɪᴇᴡ 🚗" to "bep review",
            "🅐🅑 🅾🅿" to "AB OP",
        )
        for ((raw, want) in cases) assertEquals("sanitize(${raw.map { it.code }})", want, TextRules.sanitize(raw))
    }

    @Test
    fun truncateNeverSplitsSupplementaryCharacters() {
        // U+1F4C5 (calendar emoji) is 4 UTF-8 bytes / 2 UTF-16 units.
        val bytes = TextRules.truncateUtf8("ab📅", 5)
        assertEquals("ab", String(bytes, Charsets.UTF_8))
        assertEquals(6, TextRules.truncateUtf8("ab📅", 6).size)
    }

    @Test
    fun loneSurrogateBecomesReplacementCharacter() {
        assertEquals("a�b", TextRules.sanitize("a\uD800b"))
    }
}
