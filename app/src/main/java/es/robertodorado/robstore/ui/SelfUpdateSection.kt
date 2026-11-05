package es.robertodorado.robstore.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import es.robertodorado.robstore.download.*
import es.robertodorado.robstore.update.*
import es.robertodorado.robstore.ui.robstyle.*

@Composable
internal fun SelfUpdateSection(state: SelfUpdateState, download: DownloadState,
    onCheck: () -> Unit, onDownload: () -> Unit, onCancel: () -> Unit,
    onConfirm: () -> Unit, onInstall: () -> Unit, onPermission: () -> Unit) {
    RobCard {
        RobSectionHeader("Actualización de RobStore")
        Text("Versión instalada: ${state.installed?.versionName ?: "desconocida"} (${state.installed?.versionCode ?: "?"})")
        Text("Canal: ${state.channel?.let { if (it == SelfUpdateChannel.RELEASE) "Release" else "DEV" } ?: "desconocido"}")
        val status = when {
            !state.enabled -> "Autoactualización no disponible para este canal."
            state.checking -> "Comprobando actualizaciones…"
            state.available -> "Nueva versión de RobStore disponible"
            state.checkedAt == 0L -> "Sin comprobación válida."
            state.candidate == null -> "Sin Release válida o con información insuficiente; no hay actualización comprobable."
            state.installed?.versionCode == null -> "Información insuficiente para comparar versiones."
            state.candidate.release.versionCode == state.installed.versionCode -> "RobStore está actualizada."
            else -> "La versión publicada es inferior; no se permite actualizar."
        }
        Text(status, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (state.checkedAt > 0) {
            Text("Última comprobación válida: ${displayDate(state.checkedAt) ?: "fecha desconocida"}")
            if (state.failed) Text("Información anterior conservada; puede estar desactualizada.")
        }
        state.message?.let { Text(it) }
        if (state.enabled) {
            val timing = refreshTiming(state.nextCheckAt)
            if (!state.checking && (state.nextCheckAt <= 0 || timing == RefreshAvailability.Allowed)) RobPrimaryButton("Comprobar actualizaciones", onClick = onCheck)
            else if (state.nextCheckAt > 0) Text("Próxima comprobación permitida: ${displayDate(state.nextCheckAt) ?: "fecha desconocida"}")
        }
        if (state.available) state.candidate?.let { candidate ->
            Text("Versión disponible: ${candidate.release.versionName} (${candidate.release.versionCode})")
            Text(candidate.release.notes.ifBlank { "Sin novedades publicadas." })
            DownloadActions(candidate, download, { onDownload() }, onCancel, onConfirm, { onInstall() }, onPermission,
                actions = ApplicationActions("Actualizar RobStore", false))
            Text("Android comprobará la firma y la compatibilidad. Abrir el instalador no confirma la actualización; se comprobará la versión instalada al volver a abrir RobStore.")
        }
    }
}
