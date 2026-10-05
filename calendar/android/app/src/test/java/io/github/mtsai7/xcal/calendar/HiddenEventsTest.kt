package io.github.mtsai7.xcal.calendar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HiddenEventsTest {
    private val hidden = HiddenEvents.parse("  Office \n\nreach out to\n")

    @Test
    fun hidesAllDayByTitleOrLocationIgnoringCase() {
        assertTrue(hidden.hides("Home office (Office)", "", allDay = true))
        assertTrue(hidden.hides("Working location", "Main OFFICE", allDay = true))
        assertTrue(hidden.hides("Reach out to Sam for schedule", null, allDay = true))
    }

    @Test
    fun keepsTimedEventsAndNonMatches() {
        assertFalse(hidden.hides("Office hours", "Office", allDay = false))
        assertFalse(hidden.hides("Holiday", "", allDay = true))
    }

    @Test
    fun blankListHidesNothing() {
        assertFalse(HiddenEvents.parse(" \n \n").hides("anything", "", allDay = true))
        assertFalse(HiddenEvents.NONE.hides("anything", "", allDay = true))
    }
}
