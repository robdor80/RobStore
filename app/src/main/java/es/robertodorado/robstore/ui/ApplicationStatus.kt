package es.robertodorado.robstore.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import es.robertodorado.robstore.catalog.CatalogApp
import es.robertodorado.robstore.inventory.*

internal data class ApplicationActions(val downloadLabel: String?, val canOpen: Boolean)
internal fun applicationActions(app: CatalogApp, status: CatalogApplicationStatus?): ApplicationActions {
    val local = (status?.installation as? InstallationQuery.Installed)?.application
    val label = if (app.packageName in setOf("es.robertodorado.robstore", "es.robertodorado.robstore.debug")) null
    else when (status?.comparison) {
        VersionComparison.PUBLISHED_NEWER -> "Actualizar"
        VersionComparison.ABSENT -> "Instalar"
        VersionComparison.SAME, VersionComparison.PUBLISHED_OLDER -> null
        else -> "Descargar APK"
    }
    return ApplicationActions(label, local?.launcher is LauncherQuery.Available)
}

internal fun applicationStatusText(status: CatalogApplicationStatus?): String = when (status?.comparison) {
    null -> "Consulta local pendiente."
    VersionComparison.SAME -> "Instalada y actualizada."
    VersionComparison.PUBLISHED_NEWER -> "Actualización disponible."
    VersionComparison.PUBLISHED_OLDER -> "Versión instalada superior a la publicada."
    VersionComparison.ABSENT -> "Aplicación no instalada."
    VersionComparison.INSTALLATION_UNKNOWN -> "Instalación desconocida: Android limita la consulta."
    VersionComparison.PUBLISHED_UNKNOWN -> "Versión remota desconocida: no se puede demostrar una actualización."
    VersionComparison.INSTALLED_VERSION_UNREADABLE -> "Instalada; versión local ilegible."
}

@Composable
internal fun ApplicationStatus(app: CatalogApp, status: CatalogApplicationStatus?, detail: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(applicationStatusText(status), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (detail) {
            val local = (status?.installation as? InstallationQuery.Installed)?.application
            if (local != null) {
                Text("Instalada: ${local.versionName ?: "nombre desconocido"} · versionCode ${local.versionCode ?: "ilegible"}",
                    style = MaterialTheme.typography.bodySmall)
                if (local.launcher !is LauncherQuery.Available) Text("Sin actividad de inicio accesible.",
                    style = MaterialTheme.typography.bodySmall)
            }
            Text("Publicada: ${app.release.versionName ?: "nombre desconocido"} · versionCode ${app.release.versionCode ?: "desconocido"}",
                style = MaterialTheme.typography.bodySmall)
            if (applicationActions(app, status).downloadLabel == "Descargar APK") Text(
                "Puedes descargar voluntariamente. Esto no confirma ausencia ni actualización; Android decidirá si puede instalarse.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
