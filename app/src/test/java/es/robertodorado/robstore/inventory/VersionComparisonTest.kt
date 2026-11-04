package es.robertodorado.robstore.inventory

import es.robertodorado.robstore.catalog.CatalogRelease
import org.junit.Assert.*
import org.junit.Test

class VersionComparisonTest {
    private val published = CatalogRelease(1, "v999.0", "2026-10-28T00:00:00Z", "", 5, "0.1")
    private fun installed(code: Long?) = InstallationQuery.Installed(
        InstalledApplication("es.example.catalog", code, "999.0", LauncherQuery.Unavailable),
    )
    @Test fun comparesLongCodesWithoutOrderingNamesOrTags() {
        assertEquals(VersionComparison.PUBLISHED_NEWER, compareVersions(published, installed(4)))
        assertEquals(VersionComparison.SAME, compareVersions(published, installed(5)))
        assertEquals(VersionComparison.PUBLISHED_OLDER, compareVersions(published, installed(Long.MAX_VALUE)))
        assertEquals(VersionComparison.PUBLISHED_NEWER, compareVersions(published.copy(versionCode = Long.MAX_VALUE), installed(5)))
    }
    @Test fun retainsDistinctUnknownUnreadableAndAbsentStates() {
        assertEquals(VersionComparison.PUBLISHED_UNKNOWN, compareVersions(published.copy(versionCode = null), installed(5)))
        assertEquals(VersionComparison.INSTALLED_VERSION_UNREADABLE, compareVersions(published, installed(null)))
        assertEquals(VersionComparison.INSTALLATION_UNKNOWN, compareVersions(published, InstallationQuery.Unknown))
        assertEquals(VersionComparison.ABSENT, compareVersions(published, InstallationQuery.Absent))
    }
}
