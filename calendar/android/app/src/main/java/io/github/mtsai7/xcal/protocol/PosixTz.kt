package io.github.mtsai7.xcal.protocol

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.zone.ZoneOffsetTransitionRule
import java.time.zone.ZoneOffsetTransitionRule.TimeDefinition

/**
 * POSIX TZ rule for a zone (spec §6), e.g. "STD8DST,M3.2.0,M11.1.0".
 *
 * Names are always the placeholders STD/DST. Transition times are local wall
 * time before the transition, as POSIX requires; the default 02:00 is omitted.
 */
object PosixTz {
    private const val DEFAULT_TRANSITION_SECONDS = 2 * 3600

    fun forZone(zone: ZoneId, now: Instant = Instant.now()): String {
        val rules = zone.rules
        val transitionRules = rules.transitionRules
        val fixed = "STD" + offset(rules.getOffset(now))
        if (transitionRules.size != 2) return fromUpcomingTransitions(zone, now) ?: fixed

        // Lower offset is "standard": also covers zones whose tzdb data uses
        // negative DST (Europe/Dublin), which POSIX cannot express directly.
        val offsets = transitionRules.flatMap { listOf(it.offsetBefore, it.offsetAfter) }
        val std = offsets.minBy { it.totalSeconds }
        val dst = offsets.maxBy { it.totalSeconds }
        if (std == dst) return fixed
        val start = transitionRules.firstOrNull { it.offsetAfter == dst } ?: return fixed
        val end = transitionRules.firstOrNull { it !== start } ?: return fixed

        val sb = StringBuilder("STD").append(offset(std)).append("DST")
        if (dst.totalSeconds - std.totalSeconds != 3600) sb.append(offset(dst))
        sb.append(',').append(ruleSpec(start)).append(',').append(ruleSpec(end))
        val result = sb.toString()
        return if (result.length <= Xcal.MAX_TZ_LEN) result else fixed
    }

    /**
     * Fallback when the platform exposes no transition rules (possible with
     * Android's ICU-backed ZoneRules): describe the next two actual transitions
     * as recurring "Mm.w.d" rules. Null if the zone has no upcoming DST pair.
     */
    internal fun fromUpcomingTransitions(zone: ZoneId, now: Instant): String? {
        val rules = zone.rules
        val first = rules.nextTransition(now) ?: return null
        val second = rules.nextTransition(first.instant) ?: return null
        if (second.instant.epochSecond - first.instant.epochSecond > 366L * 86400) return null
        val lo = minOf(first.offsetBefore.totalSeconds, first.offsetAfter.totalSeconds)
        val hi = maxOf(first.offsetBefore.totalSeconds, first.offsetAfter.totalSeconds)
        if (lo == hi || second.offsetBefore != first.offsetAfter || second.offsetAfter != first.offsetBefore) return null
        val start = if (first.offsetAfter.totalSeconds == hi) first else second
        val end = if (start === first) second else first
        fun spec(t: java.time.zone.ZoneOffsetTransition): String {
            val before = t.dateTimeBefore
            val len = before.month.length(before.toLocalDate().isLeapYear)
            val week = if (before.dayOfMonth + 7 > len) 5 else (before.dayOfMonth - 1) / 7 + 1
            val date = "M${before.monthValue}.$week.${before.dayOfWeek.value % 7}"
            val secs = before.toLocalTime().toSecondOfDay()
            return if (secs == DEFAULT_TRANSITION_SECONDS) date else "$date/${hms(secs)}"
        }
        val std = ZoneOffset.ofTotalSeconds(lo)
        val dst = ZoneOffset.ofTotalSeconds(hi)
        val sb = StringBuilder("STD").append(offset(std)).append("DST")
        if (hi - lo != 3600) sb.append(offset(dst))
        sb.append(',').append(spec(start)).append(',').append(spec(end))
        return sb.toString().takeIf { it.length <= Xcal.MAX_TZ_LEN }
    }

    /** POSIX offsets are inverted: UTC-8 is "8", UTC+5:30 is "-5:30". */
    private fun offset(o: ZoneOffset): String {
        val posix = -o.totalSeconds
        return (if (posix < 0) "-" else "") + hms(Math.abs(posix))
    }

    private fun hms(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return when {
            s != 0 -> "%d:%02d:%02d".format(java.util.Locale.ROOT, h, m, s)
            m != 0 -> "%d:%02d".format(java.util.Locale.ROOT, h, m)
            else -> h.toString()
        }
    }

    private fun ruleSpec(r: ZoneOffsetTransitionRule): String {
        val date = dateSpec(r.month, r.dayOfMonthIndicator, r.dayOfWeek)
        var t = r.localTime.toSecondOfDay() + if (r.isMidnightEndOfDay) 86400 else 0
        t += when (r.timeDefinition) {
            TimeDefinition.UTC -> r.offsetBefore.totalSeconds
            TimeDefinition.STANDARD -> r.offsetBefore.totalSeconds - r.standardOffset.totalSeconds
            else -> 0 // WALL
        }
        if (t == DEFAULT_TRANSITION_SECONDS) return date
        return date + "/" + (if (t < 0) "-" else "") + hms(Math.abs(t))
    }

    private fun dateSpec(month: Month, dom: Int, dow: DayOfWeek?): String {
        // Fixed date: POSIX "Jn", day of a non-leap year (Feb 29 never counted).
        if (dow == null) {
            val len = month.length(false)
            val day = if (dom < 0) len + dom + 1 else dom
            return "J" + LocalDate.of(2001, month, day.coerceIn(1, len)).dayOfYear
        }
        val d = dow.value % 7 // java: Monday=1..Sunday=7; POSIX: Sunday=0
        val len = month.length(false)
        val week = when {
            dom < 0 -> 5 // last <dow> of the month
            (dom - 1) % 7 == 0 -> (dom - 1) / 7 + 1 // "<dow> >= 1, 8, 15, 22"
            dom + 6 >= len -> 5 // java's tzdb compiler stores "lastSun" as "Sun>=25"
            else -> (dom + 6) / 7 // not exactly representable; nearest week
        }
        return "M${month.value}.$week.$d"
    }
}
