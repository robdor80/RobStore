package es.robertodorado.robstore.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import es.robertodorado.robstore.R
import es.robertodorado.robstore.catalog.CatalogApp
import es.robertodorado.robstore.catalog.ReleaseArtifact
import es.robertodorado.robstore.download.*
import es.robertodorado.robstore.ui.robstyle.*

@Composable
internal fun DownloadActions(app: ReleaseArtifact, state: DownloadState, onDownload: (Long) -> Unit,
    onCancel: () -> Unit, onConfirm: () -> Unit, onInstall: (Long) -> Unit, onPermission: () -> Unit,
    actions: ApplicationActions = ApplicationActions("Descargar APK", false),
    selectionId: Long = (app as? CatalogApp)?.repositoryId ?: DownloadCoordinator.SELF_UPDATE_ID) {
    val selected = state.repositoryId == selectionId
    val phase = if (selected) state.phase else DownloadPhase.AVAILABLE
    RobCard {
        if (!selected && state.phase == DownloadPhase.INSTALLER_OPEN) {
            Text(stringResource(R.string.other_installer, state.appName))
        } else if (!selected && state.busy) {
            Text(stringResource(R.string.other_download, state.appName))
            RobPrimaryButton(stringResource(R.string.cancel_download), onClick = onCancel)
        } else {
            val status = when (phase) {
                DownloadPhase.AVAILABLE -> R.string.download_available
                DownloadPhase.CHECKING -> R.string.download_checking
                DownloadPhase.DOWNLOADING -> R.string.download_running
                DownloadPhase.CANCELLING -> R.string.download_cancelling
                DownloadPhase.VERIFYING, DownloadPhase.PREPARING_INSTALL -> R.string.download_verifying
                DownloadPhase.OFFICIAL_VERIFIED -> R.string.download_official
                DownloadPhase.NO_OFFICIAL_SHA -> R.string.download_no_sha
                DownloadPhase.READY -> R.string.download_ready
                DownloadPhase.PERMISSION_PENDING -> R.string.install_permission_explanation
                DownloadPhase.INSTALLER_OPEN -> R.string.installer_open
                DownloadPhase.UNCONFIRMED -> R.string.install_unconfirmed
                DownloadPhase.INSTALL_ERROR, DownloadPhase.ERROR -> R.string.download_error
                DownloadPhase.SECURITY_ERROR -> R.string.download_security_error
            }
            // Only phase text is a live region. Byte updates are queryable without repeated announcements.
            Text(if (phase == DownloadPhase.AVAILABLE && actions.downloadLabel == null)
                "No hay una actualización del catálogo que instalar desde aquí." else stringResource(status), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium)
            if (selected) state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (phase == DownloadPhase.DOWNLOADING) {
                val progress = if (state.expected > 0) (state.received.toFloat() / state.expected).coerceIn(0f, 1f) else 0f
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.download_progress, state.received, state.expected, (progress * 100).toInt()),
                    style = MaterialTheme.typography.bodySmall)
            }
            if (phase in setOf(DownloadPhase.CHECKING, DownloadPhase.VERIFYING, DownloadPhase.PREPARING_INSTALL)) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            when (phase) {
                DownloadPhase.AVAILABLE -> actions.downloadLabel?.let { label -> RobPrimaryButton(label) { onDownload(selectionId) } }
                DownloadPhase.CHECKING, DownloadPhase.DOWNLOADING, DownloadPhase.VERIFYING,
                DownloadPhase.PREPARING_INSTALL -> RobPrimaryButton(stringResource(R.string.cancel_download), onClick = onCancel)
                DownloadPhase.ERROR, DownloadPhase.SECURITY_ERROR -> if (actions.downloadLabel != null) RobPrimaryButton(stringResource(R.string.retry)) { onDownload(selectionId) }
                DownloadPhase.OFFICIAL_VERIFIED -> RobPrimaryButton(stringResource(R.string.continue_install), onClick = onConfirm)
                DownloadPhase.NO_OFFICIAL_SHA -> RobPrimaryButton(stringResource(R.string.confirm_without_sha), onClick = onConfirm)
                DownloadPhase.READY, DownloadPhase.UNCONFIRMED, DownloadPhase.INSTALL_ERROR ->
                    RobPrimaryButton(stringResource(R.string.install_apk)) { onInstall(selectionId) }
                DownloadPhase.PERMISSION_PENDING -> RobPrimaryButton(stringResource(R.string.open_install_settings), onClick = onPermission)
                else -> Unit
            }
            if (selected && state.localSha256 != null) Text(stringResource(R.string.local_sha, state.localSha256),
                style = MaterialTheme.typography.bodySmall)
            if (phase in setOf(DownloadPhase.OFFICIAL_VERIFIED, DownloadPhase.NO_OFFICIAL_SHA, DownloadPhase.READY)) {
                Text(stringResource(R.string.integrity_limits), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
