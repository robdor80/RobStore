package es.robertodorado.robstore

import es.robertodorado.robstore.ui.*
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
    @Test fun apkSizesAreReadableWithoutIntOverflow() {
        assertEquals("1.2 KiB", displayApkSize(1234, Locale.US))
        assertEquals("4,3 MiB", displayApkSize(4_500_000, spanish))
        assertEquals("3.0 GiB", displayApkSize(3L * 1024 * 1024 * 1024, Locale.US))
    }
}
