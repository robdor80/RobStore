package es.robertodorado.robstore

import es.robertodorado.robstore.catalog.*
import es.robertodorado.robstore.inventory.*
import es.robertodorado.robstore.ui.*
import org.junit.Assert.*
import org.junit.Test

class UpdateActionsTest {
    private val app = fixtureSnapshot().apps.single().let { it.copy(release = it.release.copy(versionCode = 20)) }
    private fun local(code: Long?, launcher: LauncherQuery = LauncherQuery.Unavailable) =
        InstallationQuery.Installed(InstalledApplication(app.packageName, code, "unrelated-name", launcher))
    @Test fun provenVersionsSelectUpdateOpenOrNoDownload() {
        for ((code, label) in listOf(19L to "Actualizar", 20L to null, 21L to null)) {
            val status = CatalogApplicationStatus(app, local(code))
            assertEquals(label, applicationActions(app, status).downloadLabel)
            assertFalse(applicationActions(app, status).canOpen)
        }
    }
    @Test fun absenceIsTheOnlyStateOfferingInstall() {
        assertEquals("Instalar", applicationActions(app, CatalogApplicationStatus(app, InstallationQuery.Absent)).downloadLabel)
        for (query in listOf(InstallationQuery.Unknown, local(null))) {
            val status = CatalogApplicationStatus(app, query)
            assertEquals("Descargar APK", applicationActions(app, status).downloadLabel)
            assertFalse(applicationStatusText(status).contains("no instalada"))
        }
    }
    @Test fun pendingAndLegacyManifestDoNotAdvertiseUpdate() {
        assertEquals("Consulta local pendiente.", applicationStatusText(null))
        assertEquals("Descargar APK", applicationActions(app, null).downloadLabel)
        val legacy = app.copy(release = app.release.copy(versionCode = null))
        val status = CatalogApplicationStatus(legacy, local(1))
        assertEquals(VersionComparison.PUBLISHED_UNKNOWN, status.comparison)
        assertEquals("Descargar APK", applicationActions(legacy, status).downloadLabel)
    }
    @Test fun allComparisonStatesHaveDistinctReadableDescriptions() {
        val messages = VersionComparison.entries.map { applicationStatusText(CatalogApplicationStatus(app, local(1), it)) }
        assertEquals(VersionComparison.entries.size, messages.toSet().size)
        assertTrue(messages.all { it.length > 10 })
    }
    @Test fun selfUpdateIsExcludedForBothIdentities() {
        for (name in listOf("es.robertodorado.robstore", "es.robertodorado.robstore.debug")) {
            val self = app.copy(packageName = name)
            assertNull(applicationActions(self, CatalogApplicationStatus(self, local(1))).downloadLabel)
        }
    }
}
