package es.robertodorado.robstore

import es.robertodorado.robstore.ui.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class CatalogFormattingTest {
    private val spanish = Locale.forLanguageTag("es-ES")
    private fun date(iso: String, zone: String, locale: Locale = spanish) =
        displayDate(Instant.parse(iso).toEpochMilli(), ZoneId.of(zone), locale)!!

    @Test fun madridWinterUsesLocalTime() {
        val text = date("2026-10-29T21:36:58.589Z", "Europe/Madrid")
        assertTrue(text.contains("29 oct 2026")); assertTrue(text.endsWith("22:36")); assertFalse(text.contains("T21:"))
    }
    @Test fun madridSummerUsesDaylightSaving() {
        assertTrue(date("2026-07-29T21:36:00Z", "Europe/Madrid").endsWith("23:36"))
        assertTrue(date("2026-07-29T21:36:00Z", "UTC").endsWith("21:36"))
    }
    @Test fun daylightSavingBoundaryAndDateRollover() {
        assertTrue(date("2026-03-29T00:30:00Z", "Europe/Madrid").endsWith("1:30"))
        assertTrue(date("2026-03-29T01:30:00Z", "Europe/Madrid").endsWith("3:30"))
        assertTrue(date("2026-07-29T23:36:00Z", "Europe/Madrid").contains("30 jul 2026"))
    }
    @Test fun localeControlsDateAndClock() {
        val us = date("2026-10-29T21:36:00Z", "UTC", Locale.US)
        assertTrue(us.contains("Oct 29, 2026")); assertTrue(us.contains("9:36")); assertTrue(us.contains("PM"))
    }
    @Test fun missingAndInvalidDatesAreOmitted() {
        assertNull(displayDate(0)); assertNull(displayDate(-1)); assertNull(displayReleaseDate("invalid"))
        assertNull(displayReleaseDate("1970-01-01T00:00:00Z"))
    }
    @Test fun refreshDeadlineUsesDeterministicClockAcrossTimeZones() {
        val now = Instant.parse("2026-03-29T00:59:59Z")
        for (zone in listOf("UTC", "Europe/Madrid", "America/Los_Angeles", "Asia/Tokyo")) {
            val clock = Clock.fixed(now, ZoneId.of(zone))
            assertEquals(RefreshAvailability.Blocked, refreshAvailability(now.toEpochMilli() + 1000, clock))
            assertEquals(RefreshAvailability.Allowed, refreshAvailability(now.toEpochMilli(), clock))
            assertEquals(RefreshAvailability.Allowed, refreshAvailability(now.toEpochMilli() - 1, clock))
            assertEquals(RefreshAvailability.Unknown, refreshAvailability(0, clock))
            assertEquals(RefreshAvailability.Unknown, refreshAvailability(-1, clock))
        }
    }

    @Test fun blockedDeadlineRetainsLocalizedFormattingAcrossDstAndDateRollover() {
        val deadline = Instant.parse("2026-03-29T01:00:00Z").toEpochMilli()
        val clock = Clock.fixed(Instant.ofEpochMilli(deadline - 1), ZoneId.of("UTC"))
        assertEquals(RefreshAvailability.Blocked, refreshAvailability(deadline, clock))
        assertTrue(displayDate(deadline, ZoneId.of("Europe/Madrid"), spanish)!!.endsWith("3:00"))
        val pacific = displayDate(deadline, ZoneId.of("America/Los_Angeles"), Locale.US)!!
        assertTrue(pacific.contains("Mar 28, 2026"))
        assertTrue(pacific.contains("6:00 PM"))
        assertEquals(RefreshAvailability.Allowed, refreshAvailability(deadline, Clock.offset(clock, java.time.Duration.ofMillis(1))))
    }

    @Test fun apkSizesAreReadableWithoutIntOverflow() {
        assertEquals("1.2 KiB", displayApkSize(1234, Locale.US))
        assertEquals("4,3 MiB", displayApkSize(4_500_000, spanish))
        assertEquals("3.0 GiB", displayApkSize(3L * 1024 * 1024 * 1024, Locale.US))
    }
}
