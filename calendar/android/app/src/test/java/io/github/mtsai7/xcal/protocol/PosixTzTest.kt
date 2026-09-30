package io.github.mtsai7.xcal.protocol

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** Expectations from docs/ble-protocol.md §12 (JVM tzdb; Android should match). */
class PosixTzTest {
    private val now = Instant.parse("2026-09-29T14:00:00Z")

    private fun tz(id: String) = PosixTz.forZone(ZoneId.of(id), now)

    @Test fun losAngeles() = assertEquals("STD8DST,M3.2.0,M11.1.0", tz("America/Los_Angeles"))
    @Test fun berlin() = assertEquals("STD-1DST,M3.5.0,M10.5.0/3", tz("Europe/Berlin"))
    @Test fun sydney() = assertEquals("STD-10DST,M10.1.0,M4.1.0/3", tz("Australia/Sydney"))
    @Test fun dublin() = assertEquals("STD0DST,M3.5.0/1,M10.5.0", tz("Europe/Dublin"))
    @Test fun kolkata() = assertEquals("STD-5:30", tz("Asia/Kolkata"))
    @Test fun utc() = assertEquals("STD0", tz("UTC"))
    @Test fun lordHowe() = assertEquals("STD-10:30DST-11,M10.1.0,M4.1.0", tz("Australia/Lord_Howe"))

    /** The next-transitions fallback must agree with the rule-based path. */
    @Test
    fun fallbackMatchesRules() {
        for (id in listOf("America/Los_Angeles", "Europe/Berlin", "Australia/Sydney", "Europe/Dublin")) {
            assertEquals(id, tz(id), PosixTz.fromUpcomingTransitions(ZoneId.of(id), now))
        }
    }
}
