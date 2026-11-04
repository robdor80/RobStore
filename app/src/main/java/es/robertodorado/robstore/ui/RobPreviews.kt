package es.robertodorado.robstore.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import es.robertodorado.robstore.catalog.*
import es.robertodorado.robstore.inventory.*
import es.robertodorado.robstore.ui.robstyle.*

// Development fixtures are private to previews; production never reads them.
private val previewApp = CatalogApp(
    repositoryId = 1, repositoryName = "Preview", repositoryUrl = "https://github.com/robdor80/Preview",
    name = "RobNotas", description = "Tus ideas, siempre a mano. Organiza tus notas con una experiencia sencilla y cuidada.",
    packageName = "es.robertodorado.preview",
    release = CatalogRelease(2, "1.4.0", "2026-10-29T20:36:00Z", "Mejoras de accesibilidad.\n\nUna nueva forma de organizar tus notas.\nMayor estabilidad sin conexión."),
    apk = CatalogApk(3, "preview-release.apk", 4_500_000, "https://github.com/robdor80/Preview/releases/download/1.4.0/preview-release.apk"),
    icon = null,
)
private val previewState = CatalogState(loading = false, snapshot = CatalogSnapshot(
    syncedAt = 1_793_303_760_000,
    apps = listOf(previewApp, previewApp.copy(repositoryId = 2, name = "RobTiempo"),
        previewApp.copy(repositoryId = 3, name = "Un nombre de aplicación especialmente largo", release = previewApp.release.copy(tag = "edition-october-stable-2026")),
        previewApp.copy(repositoryId = 4, name = "RobLectura")),
))

@Preview(name = "Home compacta", widthDp = 320, heightDp = 640)
@Preview(name = "Home grande", widthDp = 1100, heightDp = 900)
@Preview(name = "Home horizontal", widthDp = 800, heightDp = 360)
@Preview(name = "Home texto ampliado", widthDp = 360, heightDp = 800, fontScale = 2f)
@Composable
private fun HomePreview() { RobStyleTheme { HomeScreen(onSettings = {}, state = previewState) } }

@Preview(name = "Home vacía", widthDp = 360, heightDp = 700)
@Composable
private fun EmptyPreview() { RobStyleTheme { HomeScreen(onSettings = {}, state = previewState.copy(
    snapshot = previewState.snapshot!!.copy(apps = emptyList()))) } }

@Preview(name = "Home caché y error", widthDp = 360, heightDp = 800)
@Composable
private fun CachedErrorPreview() { RobStyleTheme { HomeScreen(onSettings = {}, state = previewState.copy(
    stale = true, failed = true, message = "Sin conexión", nextRefreshAt = 1_793_304_060_000)) } }

@Preview(name = "Detalle", widthDp = 360, heightDp = 900)
@Composable
private fun DetailPreview() { RobStyleTheme { AppDetailScreen(onBack = {}, app = previewApp) } }

@Preview(name = "Detalle texto largo", widthDp = 320, heightDp = 900, fontScale = 1.5f)
@Composable
private fun LongDetailPreview() { RobStyleTheme { AppDetailScreen(onBack = {}, app = previewApp.copy(
    name = "Una aplicación con un nombre especialmente largo", description = previewApp.description.repeat(15),
    release = previewApp.release.copy(notes = previewApp.release.notes.repeat(15)))) } }

@Preview(name = "Ajustes", widthDp = 360, heightDp = 900)
@Composable
private fun SettingsPreview() { RobStyleTheme { SettingsScreen(onBack = {}, state = previewState.copy(
    message = "Sin conexión. Se conserva la instantánea anterior.", failed = true,
    nextRefreshAt = 1_793_304_060_000, snapshot = previewState.snapshot!!.copy(diagnostics = listOf("Repositorio excluido: metadatos incompletos.")))) } }

@Preview(name = "Error inicial", widthDp = 320, heightDp = 700)
@Composable
private fun ErrorPreview() { RobStyleTheme { HomeScreen(onSettings = {}, state = CatalogState(loading = false, failed = true)) } }

@Preview(name = "Carga inicial", widthDp = 320, heightDp = 700)
@Composable
private fun LoadingPreview() { RobStyleTheme { HomeScreen(onSettings = {}, state = CatalogState()) } }

@Preview(showBackground = true)
@Composable
private fun SuccessPreview() { RobStyleTheme { RobStatus(RobVisualState.Success) } }

private val updatePreviewApp = previewApp.copy(release = previewApp.release.copy(versionCode = 2, versionName = "1.4.0"))
private val updatePreviewStatus = CatalogApplicationStatus(updatePreviewApp, InstallationQuery.Installed(
    InstalledApplication(updatePreviewApp.packageName, 1, "1.3.0", LauncherQuery.Available(
        android.content.ComponentName(updatePreviewApp.packageName, "Main")))))

@Preview(name = "P02 Realme vertical aprox.", widthDp = 393, heightDp = 873)
@Preview(name = "P02 Tab S9+ vertical aprox.", widthDp = 876, heightDp = 1370)
@Preview(name = "P02 Tab S9+ horizontal 200%", widthDp = 1370, heightDp = 876, fontScale = 2f)
@Preview(name = "P02 compacta 200%", widthDp = 320, heightDp = 900, fontScale = 2f)
@Composable
private fun UpdatePreview() { RobStyleTheme {
    AppDetailScreen({}, updatePreviewApp, status = updatePreviewStatus)
} }

@Preview(name = "P02 Home estados", widthDp = 876, heightDp = 1370, fontScale = 2f)
@Composable
private fun UpdateHomePreview() { RobStyleTheme {
    val statuses = VersionComparison.entries.mapIndexed { index, comparison ->
        val app = updatePreviewApp.copy(repositoryId = index.toLong() + 1,
            release = updatePreviewApp.release.copy(versionCode = if (comparison == VersionComparison.PUBLISHED_UNKNOWN) null else 2))
        val query = when (comparison) {
            VersionComparison.ABSENT -> InstallationQuery.Absent
            VersionComparison.INSTALLATION_UNKNOWN -> InstallationQuery.Unknown
            else -> InstallationQuery.Installed(InstalledApplication(app.packageName,
                when (comparison) {
                    VersionComparison.SAME -> 2L
                    VersionComparison.PUBLISHED_OLDER -> 3L
                    VersionComparison.INSTALLED_VERSION_UNREADABLE -> null
                    else -> 1L
                }, "1.3.0", LauncherQuery.Unavailable))
        }
        CatalogApplicationStatus(app, query)
    }
    val apps = statuses.map { it.published } + updatePreviewApp.copy(repositoryId = 99, name = "Consulta pendiente")
    HomeScreen({}, state = previewState.copy(snapshot = previewState.snapshot!!.copy(apps = apps)),
        installed = statuses.associateBy { it.published.repositoryId })
} }
