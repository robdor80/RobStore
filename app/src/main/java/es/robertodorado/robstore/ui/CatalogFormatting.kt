package es.robertodorado.robstore.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle
import java.util.Locale

/** Structured timestamps only; zero/invalid metadata never becomes a displayed date. */
internal fun displayDate(time: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String? {
    if (time <= 0) return null
    val local = Instant.ofEpochMilli(time).atZone(zone)
    return DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(local) +
        " · " + DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(local)
}

internal fun displayReleaseDate(value: String): String? = try {
    displayDate(Instant.parse(value).toEpochMilli())
} catch (_: DateTimeParseException) { null } catch (_: ArithmeticException) { null }

internal fun displayApkSize(bytes: Long, locale: Locale = Locale.getDefault()): String {
    val units = listOf("B", "KiB", "MiB", "GiB")
    var size = bytes.toDouble()
    var unit = 0
    while (size >= 1024 && unit < units.lastIndex) { size /= 1024; unit++ }
    return if (unit == 0) "$bytes B" else String.format(locale, "%.1f %s", size, units[unit])
}
