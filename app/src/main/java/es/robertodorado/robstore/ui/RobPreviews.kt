package es.robertodorado.robstore.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import es.robertodorado.robstore.catalog.*
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
