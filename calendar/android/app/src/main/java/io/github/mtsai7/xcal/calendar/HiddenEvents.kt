package io.github.mtsai7.xcal.calendar

import io.github.mtsai7.xcal.protocol.TextRules

/**
 * User hide-list for recurring all-day noise (working location, standing
 * reminders). Only all-day events are hidden, so a timed meeting that happens
 * to share a word still shows. Matching is a case-insensitive substring of
 * the title or the location.
 */
class HiddenEvents(phrases: Collection<String>) {
    private val phrases = phrases.map { TextRules.sanitize(it) }.filter { it.isNotEmpty() }

    fun hides(title: String?, location: String?, allDay: Boolean): Boolean {
        if (!allDay || phrases.isEmpty()) return false
        // Compare what the X3 would show, so styled or emoji-laden titles still match plain phrases.
        val t = TextRules.sanitize(title)
        val l = TextRules.sanitize(location)
        return phrases.any { p -> t.contains(p, ignoreCase = true) || l.contains(p, ignoreCase = true) }
    }

    companion object {
        val NONE = HiddenEvents(emptyList())

        /** One phrase per line, as typed in the settings box. */
        fun parse(text: String?): HiddenEvents = HiddenEvents(text.orEmpty().lines())
    }
}
