package io.github.mtsai7.xcal.calendar

import android.content.ContentUris
import android.content.Context
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import android.util.Log
import io.github.mtsai7.xcal.protocol.AgendaEvent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * Reads the agenda for local days [day0, day0 + dayCount) from the phone's
 * calendar provider (every account synced to the phone), applying the
 * inclusion rules of spec §4.2 plus the user's [HiddenEvents] list.
 */
class AgendaSource(private val context: Context) {

    fun load(zone: ZoneId, day0: LocalDate, dayCount: Int, hidden: HiddenEvents = HiddenEvents.NONE): List<AgendaEvent> {
        // One day of margin on both sides: all-day instances are stored in UTC
        // and may sit just outside a local-time window. Exact overlap is
        // decided below on computed local minutes.
        val begin = day0.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = day0.plusDays(dayCount + 1L).atStartOfDay(zone).toInstant().toEpochMilli()
        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, begin)
            ContentUris.appendId(it, end)
        }.build()

        val events = ArrayList<AgendaEvent>()
        val windowEnd = dayCount * MINUTES_PER_DAY
        context.contentResolver.query(
            uri, PROJECTION, "${Instances.VISIBLE} = 1", null, "${Instances.BEGIN} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val status = if (c.isNull(COL_STATUS)) -1 else c.getInt(COL_STATUS)
                val self = if (c.isNull(COL_SELF_STATUS)) -1 else c.getInt(COL_SELF_STATUS)
                if (status == Events.STATUS_CANCELED) continue
                if (self == Attendees.ATTENDEE_STATUS_DECLINED) continue

                val allDay = c.getInt(COL_ALL_DAY) != 0
                val title = c.getString(COL_TITLE) ?: ""
                val location = c.getString(COL_LOCATION) ?: ""
                if (hidden.hides(title, location, allDay)) continue

                val beginMs = c.getLong(COL_BEGIN)
                val endMs = if (c.isNull(COL_END)) beginMs else c.getLong(COL_END)
                val startMin: Int
                val endMin: Int
                if (allDay) {
                    // All-day instances are UTC midnights; use the calendar dates.
                    val startDate = Instant.ofEpochMilli(beginMs).atZone(ZoneOffset.UTC).toLocalDate()
                    var endDate = Instant.ofEpochMilli(endMs).atZone(ZoneOffset.UTC).toLocalDate()
                    if (!endDate.isAfter(startDate)) endDate = startDate.plusDays(1)
                    startMin = dayMinutes(day0, startDate, 0, dayCount)
                    endMin = dayMinutes(day0, endDate, 0, dayCount)
                } else {
                    val s = Instant.ofEpochMilli(beginMs).atZone(zone)
                    val e = Instant.ofEpochMilli(endMs).atZone(zone)
                    startMin = dayMinutes(day0, s.toLocalDate(), s.hour * 60 + s.minute, dayCount)
                    endMin = dayMinutes(day0, e.toLocalDate(), e.hour * 60 + e.minute, dayCount)
                }
                val overlaps = if (endMin > startMin) {
                    endMin > 0 && startMin < windowEnd
                } else {
                    startMin in 0 until windowEnd // zero-length event: include if it starts inside
                }
                if (!overlaps) continue

                val tentative = self == Attendees.ATTENDEE_STATUS_TENTATIVE || status == Events.STATUS_TENTATIVE
                events += AgendaEvent(
                    startMin = startMin,
                    endMin = endMin,
                    title = title,
                    location = location,
                    allDay = allDay,
                    tentative = tentative,
                )
            }
        } ?: Log.w(TAG, "Instances query returned null")
        return events
    }

    /** Local wall-clock minutes relative to day0 00:00, kept well inside int16 (spec clamps further). */
    private fun dayMinutes(day0: LocalDate, date: LocalDate, minuteOfDay: Int, dayCount: Int): Int {
        val days = ChronoUnit.DAYS.between(day0, date).coerceIn(-15L, dayCount + 15L).toInt()
        return days * MINUTES_PER_DAY + minuteOfDay
    }

    companion object {
        private const val TAG = "AgendaSource"
        private const val MINUTES_PER_DAY = 1440

        private val PROJECTION = arrayOf(
            Instances.BEGIN,
            Instances.END,
            Instances.TITLE,
            Instances.EVENT_LOCATION,
            Instances.ALL_DAY,
            Instances.STATUS,
            Instances.SELF_ATTENDEE_STATUS,
        )
        private const val COL_BEGIN = 0
        private const val COL_END = 1
        private const val COL_TITLE = 2
        private const val COL_LOCATION = 3
        private const val COL_ALL_DAY = 4
        private const val COL_STATUS = 5
        private const val COL_SELF_STATUS = 6
    }
}
